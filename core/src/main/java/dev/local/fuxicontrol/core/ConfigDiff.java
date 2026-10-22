package dev.local.fuxicontrol.core;
import org.json.*;
import java.util.*;
public final class ConfigDiff {
    public static JSONArray between(JSONObject before,JSONObject after) {
        JSONArray result=new JSONArray();walk("",before,after,result);return result;
    }
    private static void walk(String path,JSONObject before,JSONObject after,JSONArray result) {
        var keys=new TreeSet<>(Json.keys(before));keys.addAll(Json.keys(after));
        for(String key:keys) {
            if(path.isEmpty()&&Set.of("revision","profiles").contains(key))continue;
            String next=path+"/"+key.replace("~","~0").replace("/","~1");
            Object a=before.opt(key),b=after.opt(key);
            if(a instanceof JSONObject x&&b instanceof JSONObject y)walk(next,x,y,result);
            else if(!Objects.equals(String.valueOf(a),String.valueOf(b)))result.put(new JSONObject().put("path",next).put("before",a==null?JSONObject.NULL:a).put("after",b==null?JSONObject.NULL:b));
        }
    }
}
