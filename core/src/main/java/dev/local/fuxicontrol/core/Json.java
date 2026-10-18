package dev.local.fuxicontrol.core;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Json {
    public static List<String> keys(JSONObject object) {
        List<String> keys=new ArrayList<>();
        var iterator=object.keys();
        while(iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }
}
