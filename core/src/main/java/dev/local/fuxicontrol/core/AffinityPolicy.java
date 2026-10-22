package dev.local.fuxicontrol.core;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** CPU selection is independent of time budgets and activity-task exemptions. */
public final class AffinityPolicy {
    public static final Set<String> GROUPS=Set.of("system","little","big","prime");
    public static boolean isForeground(int processState) {return processState==1||processState==2;}
    public static JSONObject defaults() {return new JSONObject().put("foreground","system").put("background","system");}
    public static SortedSet<Integer> parse(String list) {
        var cpus=new TreeSet<Integer>();
        if(!list.matches("[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*")) throw new IllegalArgumentException("Expected CPU list, e.g. 0-2,4");
        for(String part:list.split(",")) {
            String[] pair=part.split("-");int first=Integer.parseInt(pair[0]),last=pair.length==1?first:Integer.parseInt(pair[1]);
            if(first>last || last>4095)throw new IllegalArgumentException("Invalid CPU range");
            for(int i=first;i<=last;i++)cpus.add(i);
        }
        return cpus;
    }
    public static String format(Collection<Integer> cpus) {return String.join(",",new TreeSet<>(cpus).stream().map(String::valueOf).toList());}
    public static void selector(String value) {if(!GROUPS.contains(value))parse(value);}
    public static void phases(JSONObject value) {
        for(String phase:Json.keys(value)) {Policy.oneOf(phase,"foreground","background");selector(value.getString(phase));}
    }
    public static void threads(JSONArray rules) {
        var ids=new HashSet<String>();
        for(int i=0;i<rules.length();i++) {
            JSONObject rule=rules.getJSONObject(i);String id=rule.getString("id");
            if(!id.matches("[A-Za-z0-9_-]{1,64}")||!ids.add(id))throw new IllegalArgumentException("Thread rule IDs must be unique, 1-64 letters/digits/_/-");
            for(String key:Json.keys(rule))Policy.oneOf(key,"id","match","foreground","background");
            JSONObject match=rule.getJSONObject("match");String type=match.getString("type");Policy.oneOf(type,"main","exact","glob");
            for(String key:Json.keys(match))Policy.oneOf(key,"type","name");
            if(type.equals("main")) {if(match.has("name"))throw new IllegalArgumentException("Main-thread match has no name");}
            else {String name=match.getString("name");if(name.isEmpty()||name.length()>128||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid thread name pattern");}
            boolean any=false;
            for(String phase:List.of("foreground","background"))if(rule.has(phase)) {
                JSONObject fields=rule.getJSONObject(phase);
                for(String key:Json.keys(fields))Policy.oneOf(key,"cpuAffinity");
                selector(fields.getString("cpuAffinity"));any=true;
            }
            if(!any)throw new IllegalArgumentException("Thread rule needs a foreground or background selection");
        }
    }
    public static boolean matches(JSONObject match,boolean main,String name) {
        return switch(match.getString("type")) {
            case "main" -> main;
            case "exact" -> name.equals(match.getString("name"));
            case "glob" -> {
                StringBuilder regex=new StringBuilder();
                for(char c:match.getString("name").toCharArray())regex.append(c=='*'?".*":c=='?'?".":Pattern.quote(String.valueOf(c)));
                yield name.matches(regex.toString());
            }
            default -> throw new IllegalArgumentException("Unknown match type");
        };
    }
    public static boolean requiresBinding(JSONObject app,String phase,boolean backgroundCeiling) {
        if(backgroundCeiling||!app.getJSONObject("cpuAffinity").getString(phase).equals("system"))return true;
        JSONObject processes=app.optJSONObject("processes");
        if(processes!=null)for(String key:Json.keys(processes)) {
            JSONObject process=processes.getJSONObject(key),stage=process.optJSONObject(phase);
            if(stage!=null&&!stage.optString("cpuAffinity","system").equals("system"))return true;
            JSONArray threads=process.optJSONArray("threads");
            if(threads!=null)for(int i=0;i<threads.length();i++) {
                JSONObject value=threads.getJSONObject(i).optJSONObject(phase);
                if(value!=null&&!value.getString("cpuAffinity").equals("system"))return true;
            }
        }
        return false;
    }
    public static JSONObject inheritedBaseline(JSONObject entries,int pid,String processStart,long threadStart,String current) {
        for(String key:Json.keys(entries)) {
            JSONObject parent=entries.getJSONObject(key);
            if(parent.getInt("pid")==pid&&parent.getString("processStart").equals(processStart)
                    &&threadStart>parent.optLong("boundAtTicks",Long.MAX_VALUE)&&parent.getString("applied").equals(current))return parent;
        }
        return null;
    }
    public static JSONObject resolve(JSONObject app,JSONObject process,String phase,boolean main,String name) {
        String selected=app.getJSONObject("cpuAffinity").getString(phase);
        String source=app.getJSONObject("sources").optString("cpuAffinity."+phase,app.getJSONObject("sources").optString("cpuAffinity","default"));
        JSONObject stage=process.optJSONObject(phase);
        if(stage!=null&&stage.has("cpuAffinity")){selected=stage.getString("cpuAffinity");source="process";}
        JSONArray threads=process.optJSONArray("threads");
        if(threads!=null)for(int i=0;i<threads.length();i++) {
            JSONObject rule=threads.getJSONObject(i);
            if(rule.has(phase)&&matches(rule.getJSONObject("match"),main,name)) {
                selected=rule.getJSONObject(phase).getString("cpuAffinity");source="thread:"+rule.getString("id");break;
            }
        }
        return new JSONObject().put("selection",selected).put("source",source);
    }
    /** File apply merges objects, but replaces arrays. Existing app.patch stays shallow. */
    public static JSONObject merge(JSONObject target,JSONObject patch) {
        JSONObject result=Policy.copy(target);
        for(String key:Json.keys(patch)) {
            Object value=patch.get(key);
            if(value==JSONObject.NULL)throw new IllegalArgumentException("null is not deletion; use app unset or --replace");
            result.put(key,value instanceof JSONObject object && result.optJSONObject(key)!=null?merge(result.getJSONObject(key),object):value);
        }
        return result;
    }
}
