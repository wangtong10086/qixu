package dev.local.fuxicontrol.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Cli {
    private static final String USAGE="""
            fuxictl status|capabilities|apps|explain PACKAGE
            fuxictl cpu topology|status PACKAGE
            fuxictl cpu background-little true|false
            fuxictl thread list PACKAGE PROCESS | apply PACKAGE PROCESS FILE | remove PACKAGE PROCESS ID
            fuxictl app export PACKAGE FILE | apply PACKAGE FILE [--replace] | unset PACKAGE FIELD
            fuxictl process export PACKAGE PROCESS FILE
            fuxictl profile list | create NAME | activate ID
            Writes: --dry-run, --expected-revision NUMBER. FILE may be - to read stdin.
            fuxictl search QUERY [all|name|declared|granted|policy]
            fuxictl resources snapshot|settings|clear
            fuxictl resources history SCOPE IDENTITY [SINCE_MILLIS]
            fuxictl resources settings true|false
            fuxictl process list PACKAGE | apply PACKAGE PROCESS FILE | inherit PACKAGE PROCESS
            fuxictl apps batch-apply PACKAGES_JSON_FILE PATCH_JSON_FILE
            fuxictl cleanup preview|status|quick | run PREVIEW_ID REVISION
            fuxictl cleanup app PACKAGE [--override-protection]
            fuxictl cleanup process PACKAGE PID START_TICKS [--override-protection]
            fuxictl running
            fuxictl services PACKAGE
            fuxictl service block|restore|stop PACKAGE CLASS
            fuxictl ads status | enable PACKAGE | disable PACKAGE | retry PACKAGE [--dry-run]
            fuxictl defaults show|apply FILE [--dry-run]
            fuxictl app show|inherit PACKAGE
            fuxictl app set PACKAGE FIELD VALUE
            fuxictl limit set PACKAGE --upload RATE --download RATE
            fuxictl mode observe|enforce|disabled
            fuxictl config export FILE | import FILE [--dry-run]
            fuxictl rules list|apply FILE
            fuxictl migration import DIRECTORY [--dry-run]
            fuxictl stats [UID] | audit | reconcile
            fuxictl recover
            fuxictl rpc JSON
            Rates: positive bps/kbps/mbps/gbps, or unlimited; decimal bits/second.
            """;
    private static long rate(String text) {
        if(text.equals("unlimited")) return 0;
        var m=java.util.regex.Pattern.compile("([0-9]+)(bps|kbps|mbps|gbps)").matcher(text.toLowerCase(java.util.Locale.ROOT));
        if(!m.matches()) throw new IllegalArgumentException("Expected rate with bps/kbps/mbps/gbps unit");
        long scale=switch(m.group(2)) {case "kbps"->1000;case "mbps"->1000000;case "gbps"->1000000000;default->1;};
        long value=Math.multiplyExact(Long.parseLong(m.group(1)),scale);
        if(value<=0) throw new IllegalArgumentException("Use unlimited to remove a rate limit");
        return value;
    }
    public static JSONObject request(String[] arguments) throws Exception {
        var positional=new java.util.ArrayList<String>();JSONObject r=new JSONObject();boolean replace=false;
        for(int i=0;i<arguments.length;i++) {
            switch(arguments[i]) {
                case "--override-protection" -> r.put("overrideProtection",true);
                case "--dry-run" -> r.put("dryRun",true);
                case "--expected-revision" -> {if(++i==arguments.length)throw new IllegalArgumentException("Missing revision");r.put("expectedRevision",Long.parseLong(arguments[i]));}
                case "--replace" -> replace=true;
                default -> positional.add(arguments[i]);
            }
        }
        String[] a=positional.toArray(String[]::new);
        if(a.length==0)throw new IllegalArgumentException(USAGE);
        if(replace&&!(a.length>1&&a[0].equals("app")&&a[1].equals("apply")))throw new IllegalArgumentException("--replace is for app apply");
        if(r.has("overrideProtection") && !(a.length>1 && a[0].equals("cleanup") && (a[1].equals("app")||a[1].equals("process")))) throw new IllegalArgumentException("--override-protection is for single-target cleanup");
        switch(a[0]) {
            case "cpu" -> {
                switch(a[1]) {
                    case "topology" -> r.put("command","cpu.topology");
                    case "status" -> r.put("command","cpu.status").put("package",a[2]);
                    case "background-little" -> {dev.local.fuxicontrol.core.Policy.oneOf(a[2],"true","false");r.put("command","scheduling.patch").put("patch",new JSONObject().put("backgroundSmallCoresOnly",Boolean.parseBoolean(a[2])));}
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            case "thread" -> {
                r.put("package",a[2]).put("process",a[3]);
                switch(a[1]) {
                    case "list" -> r.put("command","threads.list");
                    case "apply" -> r.put("command","thread.policy.put").put("rule",readObject(a[4]));
                    case "remove" -> r.put("command","thread.policy.remove").put("id",a[4]);
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            case "profile" -> {
                dev.local.fuxicontrol.core.Policy.oneOf(a[1],"list","create","activate");
                r.put("command",a[1].equals("list")?"config.get":"profile."+a[1]);
                if(!a[1].equals("list"))r.put(a[1].equals("create")?"name":"id",a[2]);
            }
            case "cleanup" -> {
                dev.local.fuxicontrol.core.Policy.oneOf(a[1],"preview","run","status","quick","app","process");
                int expected=switch(a[1]) {case "app"->3;case "process"->5;case "run"->4;default->2;};
                if(a.length!=expected) throw new IllegalArgumentException(USAGE);
                if(a[1].equals("app")||a[1].equals("process")) r.put("package",a[2]);
                if(a[1].equals("process")) r.put("pid",Integer.parseInt(a[3])).put("startTicks",Long.parseLong(a[4]));
                r.put("command","cleanup."+a[1]);
                if(a[1].equals("run")) r.put("id",a[2]).put("expectedRevision",Long.parseLong(a[3]));
            }
            case "status","capabilities","audit" -> r.put("command",a[0]);
            case "resources" -> {
                dev.local.fuxicontrol.core.Policy.oneOf(a[1],"snapshot","history","settings","clear");
                r.put("command","resources."+a[1]);
                if(a[1].equals("history")) {r.put("scope",a[2]).put("identity",a[3]);if(a.length>4)r.put("since",Long.parseLong(a[4]));}
                if(a[1].equals("settings")&&a.length>2) {dev.local.fuxicontrol.core.Policy.oneOf(a[2],"true","false");r.put("history",Boolean.parseBoolean(a[2]));}
            }
            case "process" -> {
                dev.local.fuxicontrol.core.Policy.oneOf(a[1],"list","apply","inherit","export");
                r.put("package",a[2]);
                if(a[1].equals("list"))r.put("command","resources.snapshot");
                else if(a[1].equals("export"))r.put("command","app.get");
                else {r.put("command",a[1].equals("apply")?"process.policy.patch":"process.policy.inherit").put("process",a[3]);if(a[1].equals("apply"))r.put("rule",readObject(a[4]));}
            }
            case "apps" -> {
                if(a.length==1)r.put("command","apps");
                else {dev.local.fuxicontrol.core.Policy.oneOf(a[1],"batch-apply");r.put("command","apps.batch.patch").put("packages",new JSONArray(Text.read(Path.of(a[2])))).put("patch",readObject(a[3]));}
            }
            case "ads" -> {
                if(a[1].equals("status")) r.put("command","ads.status");
                else if(a[1].equals("retry")) r.put("command","ads.retry").put("package",a[2]);
                else {
                    dev.local.fuxicontrol.core.Policy.oneOf(a[1],"enable","disable");
                    r.put("command","app.patch").put("package",a[2]).put("patch",new JSONObject().put("blockSplashAds",a[1].equals("enable")));
                }
            }
            case "running" -> r.put("command","apps.running");
            case "services" -> r.put("command","services.list").put("package",a[1]);
            case "service" -> {
                dev.local.fuxicontrol.core.Policy.oneOf(a[1],"block","restore","stop");
                r.put("command",a[1].equals("stop")?"service.stop":"service.policy").put("package",a[2]).put("class",a[3]);
                if(!a[1].equals("stop")) r.put("mode",a[1].equals("block")?"block":"system");
            }
            case "search" -> r.put("command","apps.index").put("query",a[1]).put("scope",a.length>2?a[2]:"all");
            case "rpc" -> {return new JSONObject(a[1]);}
            case "explain" -> r.put("command","explain").put("package",a[1]);
            case "reconcile" -> r.put("command","runtime.reconcile");
            case "mode" -> r.put("command","mode.set").put("mode",a[1]);
            case "stats" -> r.put("command","network.stats").put("uid",a.length>1?Integer.parseInt(a[1]):-1);
            case "defaults" -> {
                if(a[1].equals("show")) r.put("command","defaults.get");
                else if(a[1].equals("apply")) r.put("command","defaults.patch").put("patch",readObject(a[2]));
                else throw new IllegalArgumentException(USAGE);
            }
            case "app" -> {
                r.put("package",a[2]);
                switch(a[1]) {
                    case "show","export" -> r.put("command","app.get");
                    case "apply" -> r.put("command",replace?"app.replace":"app.apply").put("patch",readObject(a[3]));
                    case "unset" -> r.put("command","app.unset").put("field",a[3]);
                    case "inherit" -> r.put("command","app.inherit");
                    case "set" -> {
                        Object value=switch(a[3]) {
                            case "screenOffDelaySeconds","cpuNice","uclampMax","memoryHighMiB","memoryMaxMiB","burstBytes" -> Long.parseLong(a[4]);
                            case "blockSplashAds","protected","cleanupOnTaskRemoved","resident" -> {
                                if(!a[4].equals("true")&&!a[4].equals("false")) throw new IllegalArgumentException("Expected true or false");
                                yield Boolean.parseBoolean(a[4]);
                            }
                            case "uploadBps","downloadBps" -> rate(a[4]);
                            case "cpuAffinity","processes","cpuQuota","manualCleanup","services","permissions","appOps" -> new JSONObject(a[4]);
                            case "wakeLocks" -> new JSONArray(a[4]);
                            default -> a[4];
                        };
                        r.put("command","app.patch").put("patch",new JSONObject().put(a[3],value));
                    }
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            case "limit" -> {
                if(!a[1].equals("set")) throw new IllegalArgumentException(USAGE);
                JSONObject patch=new JSONObject();
                for(int i=3;i<a.length;i+=2) {
                    String key=switch(a[i]) {case "--upload"->"uploadBps";case "--download"->"downloadBps";default->throw new IllegalArgumentException("Unknown limit flag");};
                    patch.put(key,rate(a[i+1]));
                }
                r.put("command","app.patch").put("package",a[2]).put("patch",patch);
            }
            case "config" -> {
                if(a[1].equals("import")) r.put("command","config.replace").put("config",readObject(a[2]));
                else if(a[1].equals("export")) r.put("command","config.get");
                else throw new IllegalArgumentException(USAGE);
            }
            case "rules" -> {
                if(a[1].equals("list")) r.put("command","rules.get");
                else if(a[1].equals("apply")) r.put("command","rules.replace").put("rules",new JSONArray(Text.read(Path.of(a[2]))));
                else throw new IllegalArgumentException(USAGE);
            }
            case "migration" -> {
                if(!a[1].equals("import")) throw new IllegalArgumentException(USAGE);
                r.put("command","migration.import").put("directory",a[2]);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }

        return r;
    }
    private static JSONObject readObject(String file) throws Exception {
        if(!file.equals("-"))return new JSONObject(Text.read(Path.of(file)));
        byte[] bytes=System.in.readNBytes(262145);
        if(bytes.length>262144)throw new IllegalArgumentException("Input exceeds 256 KiB");
        return new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }
    private static boolean mutation(String command) {
        return java.util.Set.of("defaults.patch","app.patch","app.replace","app.apply","app.unset","app.inherit","config.replace","mode.set","rules.replace","migration.import","profile.create","profile.activate","process.policy.patch","process.policy.inherit","thread.policy.put","thread.policy.remove","apps.batch.patch","service.policy","scheduling.patch").contains(command);
    }
    public static void main(String[] args) {
        if(args.length==0 || args[0].equals("help")) {System.out.print(USAGE);return;}
        try {
            if(android.system.Os.getuid()!=0) throw new SecurityException("CLI requires root");
            if(args[0].equals("recover")) {
                System.out.print(Shell.run("sh","/data/adb/fuxicontrol/recover.sh"));return;
            }
            JSONObject request=request(args);
            if(mutation(request.getString("command"))&&!request.has("expectedRevision"))request.put("expectedRevision",Wire.call(new JSONObject().put("command","status")).getLong("revision"));
            JSONObject result=Wire.call(request);
            if(args[0].equals("app")&&args[1].equals("export")) {
                result=result.getJSONObject("overrides");Text.write(Path.of(args[3]),result.toString(2)+"\n",StandardOpenOption.CREATE_NEW);
            }
            if(args[0].equals("process")&&args[1].equals("export")) {
                JSONObject rules=result.getJSONObject("overrides").optJSONObject("processes");
                result=rules!=null&&rules.has(args[3])?rules.getJSONObject(args[3]):new JSONObject();
                Text.write(Path.of(args[4]),result.toString(2)+"\n",StandardOpenOption.CREATE_NEW);
            }
            if(args[0].equals("profile")&&args[1].equals("list")) {
                JSONObject profiles=result.optJSONObject("profiles"),items=new JSONObject();
                if(profiles!=null)for(String id:dev.local.fuxicontrol.core.Json.keys(profiles))items.put(id,profiles.getJSONObject(id).getString("name"));
                result=new JSONObject().put("activeProfile",result.optString("activeProfile","default")).put("profiles",items);
            }
            if(args[0].equals("config") && args[1].equals("export"))
                Text.write(Path.of(args[2]),result.toString(2)+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println(result.toString(2));
        } catch(Exception e) {
            System.err.println(new JSONObject(java.util.Map.of("ok",false,"error",e.getClass().getSimpleName()+": "+e.getMessage())));
            System.exit(1);
        }
    }
}
