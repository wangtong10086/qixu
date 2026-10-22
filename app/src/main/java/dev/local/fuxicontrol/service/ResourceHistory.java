package dev.local.fuxicontrol.service;

import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import org.json.*;
import java.nio.file.Path;
import java.util.*;
import dev.local.fuxicontrol.core.Json;

/** Minute windows and bounded local history, independent of policy persistence. */
public final class ResourceHistory implements AutoCloseable {
    private final SQLiteDatabase db;
    private long lastCompacted,minute,lastRecorded;
    private static final String[] METRICS={"cpuPercent","rssBytes","memoryCurrentBytes","memoryAvailableBytes","memoryTotalBytes","swapUsedBytes","swapTotalBytes","uploadBytesPerSecond","downloadBytesPerSecond","readBytesPerSecond","writeBytesPerSecond"};
    private record Series(String scope,String identity) {}
    private static final class Window {
        JSONObject latest;
        final Map<String,Double> sum=new HashMap<>(),weights=new HashMap<>();
        Window(JSONObject value) {latest=value;}
        void add(JSONObject value,double weight) throws JSONException {
            latest=value;
            for(String metric:METRICS) if(value.has(metric)&&!value.isNull(metric)) {
                sum.merge(metric,value.getDouble(metric)*weight,Double::sum);weights.merge(metric,weight,Double::sum);
            }
        }
        JSONObject value() throws JSONException {
            JSONObject result=new JSONObject(latest.toString());
            for(String metric:sum.keySet())result.put(metric,sum.get(metric)/weights.get(metric));
            return result;
        }
    }
    private final Map<Series,Window> pending=new LinkedHashMap<>();
    public ResourceHistory(Path directory) {
        db=SQLiteDatabase.openOrCreateDatabase(directory.resolve("resources.db").toFile(),null);
        db.execSQL("CREATE TABLE IF NOT EXISTS samples(time INTEGER NOT NULL,scope TEXT NOT NULL,identity TEXT NOT NULL,payload TEXT NOT NULL,period INTEGER NOT NULL DEFAULT 60)");
        db.execSQL("CREATE INDEX IF NOT EXISTS samples_query ON samples(scope,identity,time)");
        try(var statement=db.compileStatement("PRAGMA journal_size_limit=8388608")) {statement.simpleQueryForLong();}
        db.setMaximumSize(256L*1024*1024);
    }
    private void add(String scope,String id,JSONObject value,double weight) throws JSONException {
        Series key=new Series(scope,id);Window window=pending.computeIfAbsent(key,k->new Window(value));window.add(value,weight);
    }
    public synchronized void record(long time,JSONObject system,JSONObject apps) throws JSONException {
        long bucket=time/60000*60000;
        if(minute!=0 && bucket!=minute)flush();
        minute=bucket;
        double weight=lastRecorded==0?1:Math.max(1,time-lastRecorded);
        add("system","",system,weight);
        for(String pkg:Json.keys(apps)) {
            JSONObject app=apps.getJSONObject(pkg),summary=new JSONObject(app.toString());summary.remove("processes");add("app",pkg,summary,weight);
            JSONArray processes=app.getJSONArray("processes");
            for(int i=0;i<processes.length();i++){JSONObject process=processes.getJSONObject(i);add("process",pkg+"/"+process.getString("name"),process,weight);}
        }
        lastRecorded=time;
        compact(time);
    }
    public synchronized void flush() throws JSONException {
        db.beginTransaction();
        try {
            for(var entry:pending.entrySet())insert(minute,entry.getKey().scope(),entry.getKey().identity(),entry.getValue().value());
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        pending.clear();minute=0;
    }
    private void insert(long time,String scope,String identity,JSONObject payload) {
        ContentValues value=new ContentValues();value.put("time",time);value.put("scope",scope);value.put("identity",identity);value.put("payload",payload.toString());db.insertOrThrow("samples",null,value);
    }
    private long pragma(String name) {
        try(var c=db.rawQuery("PRAGMA "+name,null)) {c.moveToFirst();return c.getLong(0);}
    }
    private void compact(long now) throws JSONException {
        if(now-lastCompacted<900000) return;
        lastCompacted=now;
        long cutoff=(now-86400000)/900000*900000;
        String[] metrics={"cpuPercent","rssBytes","memoryCurrentBytes","memoryAvailableBytes","memoryTotalBytes","swapUsedBytes","swapTotalBytes","uploadBytesPerSecond","downloadBytesPerSecond","readBytesPerSecond","writeBytesPerSecond"};
        Map<String,JSONObject> summaries=new LinkedHashMap<>();
        try(var c=db.rawQuery("SELECT time,scope,identity,payload FROM samples WHERE period=60 AND time<? ORDER BY time",new String[]{Long.toString(cutoff)})) {
            while(c.moveToNext()) {
                long time=c.getLong(0)/900000*900000;String scope=c.getString(1),identity=c.getString(2),key=scope+"/"+identity+"/"+time;
                JSONObject summary=summaries.get(key);
                if(summary==null) {summary=new JSONObject().put("time",time).put("scope",scope).put("identity",identity).put("sum",new JSONObject()).put("count",new JSONObject());summaries.put(key,summary);}
                JSONObject value=new JSONObject(c.getString(3)),sum=summary.getJSONObject("sum"),count=summary.getJSONObject("count");
                for(String metric:metrics) if(value.has(metric)&&!value.isNull(metric)) {sum.put(metric,sum.optDouble(metric,0)+value.getDouble(metric));count.put(metric,count.optInt(metric)+1);}
            }
        }
        db.beginTransaction();
        try {
            for(JSONObject summary:summaries.values()) {
                JSONObject value=summary.getJSONObject("sum"),count=summary.getJSONObject("count");
                for(String key:Json.keys(value))value.put(key,value.getDouble(key)/count.getInt(key));
                ContentValues row=new ContentValues();row.put("time",summary.getLong("time"));row.put("scope",summary.getString("scope"));row.put("identity",summary.getString("identity"));row.put("payload",value.toString());row.put("period",900);
                db.insertOrThrow("samples",null,row);
            }
            db.delete("samples","period=60 AND time<?",new String[]{Long.toString(cutoff)});
            db.delete("samples","time<?",new String[]{Long.toString(now-7L*86400000)});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        long page=pragma("page_size");
        while((pragma("page_count")-pragma("freelist_count"))*page>224L*1024*1024) {
            try(var c=db.rawQuery("SELECT MIN(time) FROM samples",null)) {
                c.moveToFirst();db.delete("samples","time<=?",new String[]{Long.toString(c.getLong(0)+3600000)});
            }
        }
    }
    public synchronized JSONObject history(String scope,String identity,long since) throws JSONException {
        dev.local.fuxicontrol.core.Policy.oneOf(scope,"system","app","process");
        JSONArray rows=new JSONArray();
        try(var cursor=db.rawQuery("SELECT time,payload,period FROM samples WHERE scope=? AND identity=? AND time>=? ORDER BY time DESC LIMIT 2048",new String[]{scope,identity,Long.toString(since)})) {
            while(cursor.moveToNext()) rows.put(new JSONObject(cursor.getString(1)).put("time",cursor.getLong(0)).put("periodSeconds",cursor.getInt(2)));
        }
        long oldest=0;
        try(var cursor=db.rawQuery("SELECT MIN(time) FROM samples",null)) {if(cursor.moveToFirst()&&!cursor.isNull(0))oldest=cursor.getLong(0);}
        return new JSONObject().put("samples",rows).put("oldestTime",oldest).put("retentionDays",7);
    }
    public synchronized JSONObject clear() throws JSONException {pending.clear();minute=0;lastRecorded=0;db.delete("samples",null,null);return new JSONObject().put("cleared",true);}
    @Override public synchronized void close() throws JSONException {flush();db.close();}
}
