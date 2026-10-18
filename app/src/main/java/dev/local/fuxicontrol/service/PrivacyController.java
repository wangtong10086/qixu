package dev.local.fuxicontrol.service;

import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import dev.local.fuxicontrol.core.Json;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Journal original settings before mutation; release only settings still owned by us. */
public final class PrivacyController {
    private final Catalog catalog;
    private final Path file;
    private final JSONObject journal;
    public PrivacyController(Catalog catalog,Path directory) throws Exception {
        this.catalog=catalog;file=directory.resolve("privacy.json");
        journal=Files.exists(file)?new JSONObject(Text.read(file)):new JSONObject();
    }
    public void validate(JSONObject effective) throws Exception {
        var pm=catalog.context().getPackageManager();
        for(String name:Json.keys(effective)) {
            var p=effective.getJSONObject(name);
            JSONObject permissions=p.optJSONObject("permissions");
            if(permissions!=null) for(String permission:Json.keys(permissions)) {
                PermissionInfo info=pm.getPermissionInfo(permission,0);
                if((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)!=PermissionInfo.PROTECTION_DANGEROUS)
                    throw new IllegalArgumentException("Only runtime permissions can be revoked: "+permission);
            }
            JSONObject ops=p.optJSONObject("appOps");
            if(ops!=null) for(String op:Json.keys(ops)) {
                try {android.app.AppOpsManager.class.getMethod("strOpToOp",String.class).invoke(null,op);}
                catch(java.lang.reflect.InvocationTargetException invalid) {throw new IllegalArgumentException("Unknown AppOp: "+op,invalid.getCause());}
            }
        }
    }
    private String mode(String name,String op) throws Exception {
        int uid=catalog.context().getPackageManager().getApplicationInfo(name,0).uid;
        String text=Shell.run("cmd","appops","get","--user","0",Integer.toString(uid),op);
        if(text.contains("No operations.")) return "default";
        String shortName=op.substring(op.indexOf(':')+1).toUpperCase(java.util.Locale.ROOT);
        var match=Pattern.compile("(?m)^\\s*(?:Uid mode: )?"+Pattern.quote(shortName)+": (allow|ignore|deny|foreground|default)(?:;|$)").matcher(text);
        if(!match.find()) throw new IllegalStateException("Cannot read package AppOp mode for "+op);
        return match.group(1);
    }
    private boolean granted(String name,String permission) {
        return catalog.context().getPackageManager().checkPermission(permission,name)==PackageManager.PERMISSION_GRANTED;
    }
    public synchronized void apply(JSONObject effective,boolean enforce) throws Exception {
        Set<String> desired=new HashSet<>();
        if(enforce) for(String name:Json.keys(effective)) {
            var p=effective.getJSONObject(name);
            var permissions=p.getJSONObject("permissions");
            Set<String> requested=new HashSet<>();
            if(permissions.length()>0) {
                String[] names=catalog.context().getPackageManager().getPackageInfo(name,PackageManager.GET_PERMISSIONS).requestedPermissions;
                if(names!=null) java.util.Collections.addAll(requested,names);
            }
            for(String permission:Json.keys(permissions)) if(permissions.getString(permission).equals("deny") && requested.contains(permission)) {
                String key=name+"|permission|"+permission;desired.add(key);
                if(!journal.has(key) && granted(name,permission)) {
                    journal.put(key,new JSONObject().put("before","granted").put("applied","denied"));persist();
                }
                if(granted(name,permission)) Shell.run("pm","revoke","--user","0",name,permission);
            }
            var ops=p.getJSONObject("appOps");
            for(String op:Json.keys(ops)) {
                String key=name+"|appop|"+op;desired.add(key);String target=ops.getString(op),current=mode(name,op);
                if(current.equals(target)) continue;
                if(!journal.has(key)) journal.put(key,new JSONObject().put("before",current));
                journal.getJSONObject(key).put("applied",target);persist();
                Shell.run("cmd","appops","set","--user","0","--uid",name,op,target);
            }
        }
        for(String key:new HashSet<>(Json.keys(journal))) if(!desired.contains(key)) {
            String[] parts=key.split("\\|",3);String name=parts[0],kind=parts[1],setting=parts[2];
            var original=journal.getJSONObject(key);
            try {catalog.context().getPackageManager().getApplicationInfo(name,0);}
            catch(PackageManager.NameNotFoundException removed) {journal.remove(key);persist();continue;}
            if(kind.equals("permission")) {
                if(!granted(name,setting)) Shell.run("pm","grant","--user","0",name,setting);
            } else if(mode(name,setting).equals(original.getString("applied")))
                Shell.run("cmd","appops","set","--user","0","--uid",name,setting,original.getString("before"));
            journal.remove(key);persist();
        }
    }
    private void persist() throws Exception {Store.atomic(file,journal.toString(2));}
}
