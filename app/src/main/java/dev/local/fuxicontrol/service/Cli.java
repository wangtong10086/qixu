package dev.local.fuxicontrol.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Cli {
    private static final String USAGE="""
            fuxictl status|capabilities|apps|explain PACKAGE
            fuxictl search QUERY [all|name|declared|granted|policy]
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
    public static JSONObject request(String[] a) throws Exception {
        JSONObject r=new JSONObject();
        switch(a[0]) {
            case "status","capabilities","apps","audit" -> r.put("command",a[0]);
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
                else if(a[1].equals("apply")) r.put("command","defaults.patch").put("patch",new JSONObject(Text.read(Path.of(a[2]))));
                else throw new IllegalArgumentException(USAGE);
            }
            case "app" -> {
                r.put("package",a[2]);
                switch(a[1]) {
                    case "show" -> r.put("command","app.get");
                    case "inherit" -> r.put("command","app.inherit");
                    case "set" -> {
                        Object value=switch(a[3]) {
                            case "screenOffDelaySeconds","cpuNice","uclampMax","memoryHighMiB","memoryMaxMiB","burstBytes" -> Long.parseLong(a[4]);
                            case "blockSplashAds","protected","cleanupOnTaskRemoved","resident" -> {
                                if(!a[4].equals("true")&&!a[4].equals("false")) throw new IllegalArgumentException("Expected true or false");
                                yield Boolean.parseBoolean(a[4]);
                            }
                            case "uploadBps","downloadBps" -> rate(a[4]);
                            case "services","permissions","appOps" -> new JSONObject(a[4]);
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
                if(a[1].equals("import")) r.put("command","config.replace").put("config",new JSONObject(Text.read(Path.of(a[2]))));
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
        for(String arg:a) if(arg.equals("--dry-run")) r.put("dryRun",true);
        return r;
    }
    public static void main(String[] args) {
        if(args.length==0 || args[0].equals("help")) {System.out.print(USAGE);return;}
        try {
            if(args[0].equals("recover")) {
                if(android.system.Os.getuid()!=0) throw new SecurityException("Root required");
                System.out.print(Shell.run("sh","/data/adb/fuxicontrol/recover.sh"));return;
            }
            JSONObject result=Wire.call(request(args));
            if(args[0].equals("config") && args[1].equals("export"))
                Text.write(Path.of(args[2]),result.toString(2)+"\n",StandardOpenOption.CREATE_NEW);
            System.out.println(result.toString(2));
        } catch(Exception e) {
            System.err.println(new JSONObject(java.util.Map.of("ok",false,"error",e.getClass().getSimpleName()+": "+e.getMessage())));
            System.exit(1);
        }
    }
}
