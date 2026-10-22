package dev.local.fuxicontrol.service;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import dev.local.fuxicontrol.core.ResourceSample;
import dev.local.fuxicontrol.core.Json;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Sampling runs independently of policy IPC. No wake locks or wakeup alarms. */
public final class ResourceMonitor {
    private final AppDiscovery discovery;
    private final Catalog catalog;
    private final RuntimeEngine engine;
    private final Path directory;
    private final ResourceHistory storage;
    private final String boot;
    private final long ticksPerSecond=Os.sysconf(OsConstants._SC_CLK_TCK);
    private final long cpuCount=Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN);
    private final long pageSize=Os.sysconf(OsConstants._SC_PAGESIZE);
    private volatile Map<String,JSONObject> previous=Map.of();
    private volatile JSONObject snapshot=new JSONObject().put("available",false);
    private volatile String error="";
    private volatile boolean history=true;
    private volatile long visibleUntil;
    private final Map<String,dev.local.fuxicontrol.core.CpuWindow> cpuWindows=new HashMap<>();
    public void prepareCleanup() {visibleUntil=SystemClock.elapsedRealtime()+45000;}
    private long sampledAt,totalBefore,lastPruned;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    public ResourceMonitor(Path directory,RuntimeEngine engine,Catalog catalog) throws Exception {
        this.directory=directory;this.engine=engine;this.catalog=catalog;discovery=new AppDiscovery(catalog);
        boot=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        Path settings=directory.resolve("resource-settings.json");
        if(Files.exists(settings)) history=new JSONObject(Text.read(settings)).getBoolean("history");
        storage=new ResourceHistory(directory);
        worker.scheduleWithFixedDelay(()-> {
            long now=SystemClock.elapsedRealtime();
            if(now>visibleUntil && (!history || now-sampledAt<60000)) return;
            try {sample();error="";} catch(Exception failure) {error=failure.toString();android.util.Log.e("QixuResources","Sampling failed",failure);}
        },0,2,TimeUnit.SECONDS);
    }
    public JSONObject settings(Boolean enabled) throws Exception {
        if(enabled!=null) {Store.atomic(directory.resolve("resource-settings.json"),new JSONObject().put("history",enabled).toString());history=enabled;if(!history)storage.flush();}
        return new JSONObject().put("history",history).put("retentionDays",7);
    }
    public JSONObject snapshot() throws JSONException {
        visibleUntil=SystemClock.elapsedRealtime()+10000;
        return new JSONObject(snapshot.toString()).put("error",error).put("ageMs",sampledAt==0?JSONObject.NULL:SystemClock.elapsedRealtime()-sampledAt);
    }
    private static Map<String,Long> fields(Path file) throws Exception {
        Map<String,Long> result=new HashMap<>();
        for(String line:Text.read(file).split("\n")) {
            if(!line.matches("^[A-Za-z][A-Za-z0-9_()]*:.*"))continue;
            String[] parts=line.trim().split("[:\\s]+");
            if(parts.length>=2) result.put(parts[0],Long.parseLong(parts[1]));
        }
        return result;
    }
    private void rates(JSONObject current,String key,long now,long cpuTotal) throws JSONException {
        JSONObject before=previous.get(key);
        long interval=before==null?0:now-before.optLong("observedAtElapsedMs",sampledAt);
        current.put("observedAtElapsedMs",now);
        current.put("cpuPercent",JSONObject.NULL);
        if(before!=null && interval>0) {
            double cpu=ResourceSample.cpu(current.optLong("ticks"),before.optLong("ticks"),cpuTotal,totalBefore);
            if(Double.isFinite(cpu)) current.put("cpuPercent",cpu);
            for(String name:new String[]{"readBytes","writeBytes","uploadBytes","downloadBytes"}) {
                if(current.has(name) && before.has(name)) {
                    long delta=current.getLong(name)-before.getLong(name);
                    current.put(name+"PerSecond",delta>=0?delta*1000.0/interval:JSONObject.NULL);
                }
            }
        }
    }
    private static void counters(JSONObject target,JSONArray rows,long id,boolean process) throws JSONException {
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            if((process?row.optLong("cgroupId"):row.getInt("uid"))!=id || (process?row.optLong("cgroupId")==0:row.optLong("cgroupId")!=0)) continue;
            String dir=row.getString("direction");
            target.put(dir+"Bytes",row.getLong("passedBytes"));
            target.put(dir+"DroppedBytes",row.getLong("droppedBytes"));
        }
    }
    private JSONObject inventory() throws Exception {
        JSONObject groups=discovery.running(engine.activeTaskUids()).getJSONObject("apps");
        Set<Integer> known=new HashSet<>();
        for(String pkg:Json.keys(groups)) {
            JSONArray rows=groups.getJSONObject(pkg).getJSONArray("processes");
            for(int i=0;i<rows.length();i++)known.add(rows.getJSONObject(i).getInt("pid"));
        }
        Map<Integer,java.util.List<String>> packages=new HashMap<>();
        JSONArray installed=catalog.applications();
        for(int i=0;i<installed.length();i++){JSONObject app=installed.getJSONObject(i);packages.computeIfAbsent(app.getInt("uid"),k->new ArrayList<>()).add(app.getString("package"));}
        try(var paths=Files.list(Path.of("/proc"))) {
            for(Path path:paths.filter(p->p.getFileName().toString().matches("[0-9]+")).toList()) {
                int pid=Integer.parseInt(path.getFileName().toString());if(known.contains(pid))continue;
                try {
                    String status=Text.read(path.resolve("status"));
                    int uid=Integer.parseInt(status.lines().filter(l->l.startsWith("Uid:")).findFirst().orElseThrow().substring(4).trim().split("\\s+")[0]);
                    int owner=engine.ownerUid(uid);java.util.List<String> names=packages.get(owner);if(names==null)continue;
                    String name=Text.read(path.resolve("cmdline")).split("\\x00",2)[0];if(name.isEmpty())continue;
                    JSONObject member=new JSONObject().put("pid",pid).put("uid",uid).put("name",name).put("state","native").put("shared",names.size()>1).put("packages",new JSONArray(names));
                    for(String pkg:names) {
                        JSONObject group=groups.optJSONObject(pkg);
                        if(group==null){group=new JSONObject().put("uid",owner).put("processes",new JSONArray());groups.put(pkg,group);}
                        group.getJSONArray("processes").put(member);
                    }
                } catch(NoSuchFileException exited) { /* Snapshot races process exit. */ }
            }
        }
        return groups;
    }
    private void sample() throws Exception {
        long now=SystemClock.elapsedRealtime();
        String[] cpu=Text.read(Path.of("/proc/stat")).split("\n")[0].trim().split("\\s+");
        long total=0;for(int i=1;i<=8;i++) total+=Long.parseLong(cpu[i]);
        long idle=Long.parseLong(cpu[4])+Long.parseLong(cpu[5]);
        JSONObject system=new JSONObject().put("ticks",total-idle);
        Map<String,Long> mem=fields(Path.of("/proc/meminfo"));
        system.put("memoryTotalBytes",mem.get("MemTotal")*1024).put("memoryAvailableBytes",mem.get("MemAvailable")*1024)
                .put("swapTotalBytes",mem.get("SwapTotal")*1024)
                .put("swapUsedBytes",(mem.get("SwapTotal")-mem.get("SwapFree"))*1024);
        Set<String> external=new HashSet<>();
        var connectivity=catalog.context().getSystemService(android.net.ConnectivityManager.class);
        for(var network:connectivity.getAllNetworks()) {
            var capabilities=connectivity.getNetworkCapabilities(network);
            var links=connectivity.getLinkProperties(network);
            if(capabilities!=null && links!=null && !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) external.add(links.getInterfaceName());
        }
        JSONArray interfaces=new JSONArray();long rx=0,tx=0;
        for(String line:Text.read(Path.of("/proc/net/dev")).split("\n")) {
            if(!line.contains(":")) continue;
            String[] values=line.trim().split("[:\\s]+");String name=values[0];
            JSONObject iface=new JSONObject().put("name",name).put("downloadBytes",Long.parseLong(values[1])).put("uploadBytes",Long.parseLong(values[9]));
            boolean physical=external.contains(name);
            iface.put("physical",physical);interfaces.put(iface);
            if(physical) {rx+=iface.getLong("downloadBytes");tx+=iface.getLong("uploadBytes");}
        }
        system.put("downloadBytes",rx).put("uploadBytes",tx).put("interfaces",interfaces);
        long reads=0,writes=0;
        for(String line:Text.read(Path.of("/proc/diskstats")).split("\n")) {
            String[] v=line.trim().split("\\s+");String name=v[2];
            if(!Files.exists(Path.of("/sys/block",name,"device"))) continue;
            reads+=Long.parseLong(v[5])*512;writes+=Long.parseLong(v[9])*512;
        }
        system.put("readBytes",reads).put("writeBytes",writes);rates(system,"system",now,total);
        JSONObject groups=inventory();
        if(now-lastPruned>=60000) {
            java.util.ArrayList<Long> ids=new java.util.ArrayList<>();
            try(var dirs=Files.walk(Path.of("/sys/fs/cgroup/apps"),2)) {
                for(Path path:dirs.filter(Files::isDirectory).toList()) {
                    try {ids.add(Native.cgroupId(path.toString()));}catch(NoSuchFileException gone) { /* Exited cgroup. */ }
                }
            }
            Native.networkPrune(ids.stream().mapToLong(Long::longValue).toArray());lastPruned=now;
        }
        JSONObject net=new JSONObject(Native.networkStats(-1));
        JSONArray counters=net.optJSONArray("counters");if(counters==null)counters=new JSONArray();
        Map<String,JSONObject> next=new HashMap<>();next.put("system",system);
        JSONObject processes=new JSONObject(),apps=new JSONObject();
        for(String pkg:Json.keys(groups)) {
            JSONObject group=groups.getJSONObject(pkg);JSONArray members=group.getJSONArray("processes"),live=new JSONArray();
            JSONObject app=new JSONObject().put("package",pkg).put("rssBytes",0L).put("ticks",0L).put("readBytes",0L).put("writeBytes",0L);
            double appCpu=0;boolean cpuKnown=false;
            for(int i=0;i<members.length();i++) {
                JSONObject member=members.getJSONObject(i);int pid=member.getInt("pid");String pidKey=Integer.toString(pid);
                JSONObject p=processes.optJSONObject(pidKey);
                if(p==null) {
                    Path proc=Path.of("/proc",pidKey);
                    try {
                        ResourceSample stat=ResourceSample.parse(Text.read(proc.resolve("stat")),pageSize);
                        p=new JSONObject(member.toString()).put("startTicks",stat.start()).put("ticks",stat.ticks()).put("rssBytes",stat.rssBytes())
                                .put("identity",boot+":"+member.getInt("uid")+":"+pid+":"+stat.start());
                        Map<String,Long> io=fields(proc.resolve("io"));
                        p.put("readBytes",io.get("read_bytes")).put("writeBytes",io.get("write_bytes"));
                        String path=Text.read(proc.resolve("cgroup")).lines().filter(l->l.startsWith("0::")).findFirst().orElseThrow().substring(3);
                        Path cg=Path.of("/sys/fs/cgroup"+path);
                        long id=Native.cgroupId(cg.toString());p.put("cgroupId",id);
                        p.put("memoryCurrentBytes",Long.parseLong(Text.read(cg.resolve("memory.current")).trim()));
                        counters(p,counters,id,true);
                        long observed=SystemClock.elapsedRealtime();
                        rates(p,p.getString("identity"),observed,total);
                        JSONObject before=previous.get(p.getString("identity"));
                        if(before!=null) {
                            double usage=ResourceSample.processCpu(stat.ticks(),before.getLong("ticks"),observed-before.getLong("observedAtElapsedMs"),ticksPerSecond,cpuCount);
                            p.put("cpuPercent",Double.isFinite(usage)?usage:JSONObject.NULL);
                        }
                        next.put(p.getString("identity"),p);processes.put(pidKey,p);
                    } catch(NoSuchFileException exited) {continue;}
                }
                live.put(p);
                for(String field:new String[]{"rssBytes","ticks","readBytes","writeBytes"}) app.put(field,app.getLong(field)+p.getLong(field));
                if(!p.isNull("cpuPercent")) {appCpu+=p.getDouble("cpuPercent");cpuKnown=true;}
            }
            rates(app,"app:"+pkg,now,total);app.put("cpuPercent",cpuKnown?appCpu:JSONObject.NULL).put("processes",live);
            if(live.length()>0) {int uid=group.getInt("uid");app.put("uid",uid);counters(app,counters,uid,false);rates(app,"app:"+pkg,now,total);app.put("cpuPercent",cpuKnown?appCpu:JSONObject.NULL);}
            var window=cpuWindows.computeIfAbsent(pkg,k->new dev.local.fuxicontrol.core.CpuWindow());
            window.add(now,app.isNull("cpuPercent")?null:app.getDouble("cpuPercent"));
            Double average=window.average(now);app.put("cpu30sPercent",average==null?JSONObject.NULL:average);
            apps.put(pkg,app);next.put("app:"+pkg,app);
        }
        cpuWindows.keySet().removeIf(pkg->!apps.has(pkg));
        JSONObject result=new JSONObject().put("available",true).put("bootId",boot).put("capturedAtElapsedMs",now).put("capturedAt",System.currentTimeMillis())
                .put("system",system).put("apps",apps).put("networkAvailable",net.getBoolean("loaded")).put("coverage","sampled-live-processes");
        if(history)storage.record(System.currentTimeMillis(),system,apps);
        previous=Map.copyOf(next);totalBefore=total;sampledAt=now;snapshot=result;
    }
    public JSONObject history(String scope,String identity,long since) throws JSONException {return storage.history(scope,identity,since);}
    private final Map<String,JSONObject> details=new HashMap<>();
    public synchronized JSONObject detail(String name,String process) throws Exception {
        JSONObject app=snapshot.getJSONObject("apps").getJSONObject(name);
        JSONArray list=app.getJSONArray("processes");JSONArray result=new JSONArray();
        for(int i=0;i<list.length();i++) {
            JSONObject member=list.getJSONObject(i);
            if(!process.isEmpty()&&!member.getString("name").equals(process))continue;
            String identity=member.getString("identity");JSONObject value=details.get(identity);
            if(value==null || SystemClock.elapsedRealtime()-value.getLong("sampledAt")>=10000) {
                Path proc=Path.of("/proc",Integer.toString(member.getInt("pid")));
                try {
                    ResourceSample stat=ResourceSample.parse(Text.read(proc.resolve("stat")),pageSize);
                    if(stat.start()!=member.getLong("startTicks"))continue;
                    Map<String,Long> rollup=fields(proc.resolve("smaps_rollup"));
                    value=new JSONObject().put("identity",identity).put("pssBytes",rollup.get("Pss")*1024).put("sampledAt",SystemClock.elapsedRealtime());
                    details.put(identity,value);
                } catch(NoSuchFileException exited) {continue;}
            }
            JSONObject observed=new JSONObject(value.toString());
            int pid=member.getInt("pid");
            Path proc=Path.of("/proc",Integer.toString(pid));
            try {
                if(ResourceSample.parse(Text.read(proc.resolve("stat")),pageSize).start()!=member.getLong("startTicks"))continue;
                observed.put("nice",android.os.Process.getThreadPriority(pid)).put("uclampMax",Native.getClamp(pid)[1]);
                observed.put("effective",engine.processState(name,member.getString("name")));
                observed.put("appCpuQuota",engine.packageStatus(name).optJSONObject("cpuQuota"));
                String cg=Text.read(proc.resolve("cgroup")).lines().filter(l->l.startsWith("0::")).findFirst().orElseThrow().substring(3);
                Path cpuMax=Path.of("/sys/fs/cgroup"+cg,"cpu.max");
                if(Files.exists(cpuMax)) {
                    observed.put("cpuMax",Text.read(cpuMax).trim());
                    JSONObject cpuStat=new JSONObject();
                    for(String line:Text.read(cpuMax.resolveSibling("cpu.stat")).lines().toList()) {
                        String[] fields=line.trim().split("\\s+");cpuStat.put(fields[0],Long.parseLong(fields[1]));
                    }
                    observed.put("cpuStat",cpuStat);
                }
                observed.put("memoryHigh",Text.read(Path.of("/sys/fs/cgroup"+cg,"memory.high")).trim()).put("memoryMax",Text.read(Path.of("/sys/fs/cgroup"+cg,"memory.max")).trim());
            } catch(NoSuchFileException exited) {continue;}
            result.put(observed);
        }
        details.keySet().removeIf(id->!previous.containsKey(id));
        return new JSONObject().put("processes",result);
    }
    private final Map<String,long[]> threadSamples=new HashMap<>();
    public synchronized JSONObject threads(String name,String process) throws Exception {
        dev.local.fuxicontrol.core.Policy.packageName(name);
        // Thread viewers need the same live process inventory as the resource page.
        visibleUntil=SystemClock.elapsedRealtime()+10000;
        JSONObject app=snapshot.optJSONObject("apps")==null?null:snapshot.getJSONObject("apps").optJSONObject(name);
        JSONArray output=new JSONArray();long now=SystemClock.elapsedRealtime();
        JSONObject runtime=engine.cpuStatus(name);JSONArray applied=runtime.getJSONArray("threads");
        if(app!=null)for(int i=0;i<app.getJSONArray("processes").length();i++) {
            JSONObject member=app.getJSONArray("processes").getJSONObject(i);
            if(!member.getString("name").equals(process))continue;
            int pid=member.getInt("pid");Path proc=Path.of("/proc/"+pid);
            try {
                if(!AffinityController.identity(proc).equals(Long.toString(member.getLong("startTicks"))))continue;
                try(var tasks=Files.list(proc.resolve("task"))) {
                    for(Path task:tasks.toList())try {
                        int tid=Integer.parseInt(task.getFileName().toString());String raw=Text.read(task.resolve("stat"));
                        ResourceSample stat=ResourceSample.parse(raw,pageSize);String key=member.getString("identity")+":"+tid+":"+stat.start();
                        long[] before=threadSamples.put(key,new long[]{stat.ticks(),now});
                        JSONObject value=new JSONObject().put("pid",pid).put("tid",tid).put("identity",key).put("name",Text.read(task.resolve("comm")).stripTrailing()).put("main",pid==tid)
                            .put("allowed",new JSONArray(Native.getAffinity(tid))).put("lastCpu",Integer.parseInt(raw.substring(raw.lastIndexOf(')')+2).trim().split(" +")[36]));
                        double cpu=before==null?Double.NaN:ResourceSample.processCpu(stat.ticks(),before[0],now-before[1],ticksPerSecond,cpuCount);
                        value.put("cpuPercent",Double.isFinite(cpu)?cpu:JSONObject.NULL);
                        for(int j=0;j<applied.length();j++) {
                            JSONObject observed=applied.getJSONObject(j);
                            if(observed.getInt("tid")==tid&&observed.getString("startTicks").equals(Long.toString(stat.start()))) {value.put("scheduling",observed);break;}
                        }
                        if(AffinityController.identity(task).equals(Long.toString(stat.start())))output.put(value);
                    } catch(NoSuchFileException exited) { /* thread exited */ }
                      catch(android.system.ErrnoException exited){if(exited.errno!=android.system.OsConstants.ESRCH)throw exited;}
                }
            } catch(NoSuchFileException exited) { /* process exited */ }
        }
        threadSamples.entrySet().removeIf(entry->now-entry.getValue()[1]>60000);
        return new JSONObject().put("package",name).put("process",process).put("threads",output).put("capturedAtElapsedMs",now).put("runtime",runtime);
    }
    public JSONObject clear() throws JSONException {return storage.clear();}
}
