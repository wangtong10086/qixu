package dev.local.fuxicontrol.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Synthetic history in a separate temporary database; never touches user history. */
public final class ResourceHistoryProbe {
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory(Path.of("/data/local/tmp"),"qixu-history-");
        long now=System.currentTimeMillis()/900000*900000;
        try {
            try(ResourceHistory history=new ResourceHistory(directory)) {
                JSONObject apps=new JSONObject();
                history.record(now-8L*86400000,new JSONObject().put("cpuPercent",99),apps);
                history.record(now-2L*86400000,new JSONObject().put("cpuPercent",10).put("swapTotalBytes",4294967296L).put("swapUsedBytes",1073741824L),apps);
                history.record(now-2L*86400000+60000,new JSONObject().put("cpuPercent",20),apps);
                history.record(now-2L*86400000+120000,new JSONObject().put("cpuPercent",30),apps);
                history.record(now,new JSONObject().put("cpuPercent",40),apps);
                history.flush();
                var values=history.history("system","",0).getJSONArray("samples");
                if(values.length()!=2 || Math.abs(values.getJSONObject(1).getDouble("cpuPercent")-20)>0.001)
                    throw new IllegalStateException("History rollup/expiry failed: "+values);
                if(values.getJSONObject(1).getInt("periodSeconds")!=900)throw new IllegalStateException("Expected quarter-hour rollup");
                if(values.getJSONObject(1).getLong("swapTotalBytes")!=4294967296L || values.getJSONObject(1).getLong("swapUsedBytes")!=1073741824L)
                    throw new IllegalStateException("Swap capacity lost during rollup with legacy samples");
                if(values.getJSONObject(0).has("swapTotalBytes"))throw new IllegalStateException("Missing Swap capacity became a value");
                history.clear();
                if(history.history("system","",0).getJSONArray("samples").length()!=0)throw new IllegalStateException("Clear failed");
                history.record(now,new JSONObject().put("cpuPercent",10),apps);
                history.record(now+2000,new JSONObject().put("cpuPercent",20),apps);
                history.record(now+4000,new JSONObject().put("cpuPercent",40),apps);
                history.flush();
                double average=history.history("system","",0).getJSONArray("samples").getJSONObject(0).getDouble("cpuPercent");
                if(Math.abs(average-(10+20*2000+40*2000)/4001.0)>0.001)throw new IllegalStateException("Minute weighted average failed");
            }
            try(ResourceHistory reopened=new ResourceHistory(directory)) {
                if(reopened.history("system","",0).getJSONArray("samples").length()!=1)throw new IllegalStateException("Persistence failed");
            }
            System.out.println("PASS minute average, 15-minute rollup, seven-day expiry, clear and reopen");
        } finally {
            try(var files=Files.list(directory)) {for(Path file:files.toList())Files.delete(file);}
            Files.delete(directory);
        }
    }
}
