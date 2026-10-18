package dev.local.fuxicontrol.service;

import android.os.Binder;
import android.os.IBinder;
import dev.local.fuxicontrol.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** ActivityManager owns the OOM adjustment; Binder death automatically releases our claims. */
public final class ResidentController {
    private final IBinder token=new Binder();
    private final Set<Integer> owned=new HashSet<>();
    private final Object activity;
    private final java.lang.reflect.Method important;
    public ResidentController() throws Exception {
        activity=android.app.ActivityManager.class.getMethod("getService").invoke(null);
        important=Class.forName("android.app.IActivityManager").getMethod("setProcessImportant",IBinder.class,int.class,boolean.class,String.class);
    }
    public synchronized void apply(JSONObject effective,boolean enforce) throws Exception {
        Set<Integer> residentUids=new HashSet<>(),desired=new HashSet<>();
        if(enforce) for(String name:Json.keys(effective)) {
            JSONObject p=effective.getJSONObject(name);
            if(p.getBoolean("resident")) residentUids.add(p.getInt("uid"));
        }
        if(!residentUids.isEmpty()) try(var processes=Files.list(Path.of("/proc"))) {
            for(Path process:processes.filter(p->p.getFileName().toString().matches("[0-9]+")).toList()) {
                try {
                    for(String line:Text.read(process.resolve("status")).split("\n")) if(line.startsWith("Uid:")) {
                        int uid=Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                        if(residentUids.contains(uid)) desired.add(Integer.parseInt(process.getFileName().toString()));
                        break;
                    }
                } catch(java.nio.file.NoSuchFileException exited) { /* Process exited during enumeration. */ }
            }
        }
        for(int pid:desired) if(!owned.contains(pid)) {
            important.invoke(activity,token,pid,true,"Fuxi Control resident policy");owned.add(pid);
        }
        for(int pid:new HashSet<>(owned)) if(!desired.contains(pid)) {
            important.invoke(activity,token,pid,false,"Fuxi Control policy released");owned.remove(pid);
        }
    }
}
