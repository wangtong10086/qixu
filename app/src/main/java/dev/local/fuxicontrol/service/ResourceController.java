package dev.local.fuxicontrol.service;

import android.os.Process;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Restore only values this controller still owns; Android also updates task scheduling. */
public final class ResourceController {
    private final Path journalFile;
    private final JSONObject journal;
    private final String bootId;
    private final V1CpuQuota v1;
    final AffinityController affinity;
    public ResourceController(Path root) throws Exception {
        v1=new V1CpuQuota(root);affinity=new AffinityController(root);
        journalFile=root.resolve("resources.json");
        bootId=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        JSONObject saved=Files.exists(journalFile)?new JSONObject(Text.read(journalFile)):new JSONObject();
        journal=dev.local.fuxicontrol.core.ResourceJournal.entries(saved,bootId);
    }
    private String persisted;
    private long persistedAt;
    private void persist() throws Exception {
        String value=new JSONObject().put("bootId",bootId).put("entries",journal).toString();
        if(!value.equals(persisted)) {Store.atomic(journalFile,value);persisted=value;}
        persistedAt=android.os.SystemClock.elapsedRealtime();
    }
    // Pruning records of exited threads needs no write-ahead; stale identities are
    // discarded again after a restart. Coalesce those writes.
    private void persistLazy() throws Exception {
        if(android.os.SystemClock.elapsedRealtime()-persistedAt>=60_000) persist();
    }
    public synchronized void memory(int uid,long highMiB,long maxMiB) throws Exception {
        Path group=Path.of("/sys/fs/cgroup/apps/uid_"+uid);
        if(!Files.isDirectory(group)) return; // Android removes empty UID groups.
        applyFile(group.resolve("memory.high"), highMiB==0?null:Long.toString(Math.multiplyExact(highMiB,1048576)));
        applyFile(group.resolve("memory.max"), maxMiB==0?null:Long.toString(Math.multiplyExact(maxMiB,1048576)));
    }
    public synchronized JSONObject cpuQuota(int uid,JSONObject quota,boolean foreground,boolean exempt,Set<Integer> isolatedUids) throws Exception {
        Path group=Path.of("/sys/fs/cgroup/apps/uid_"+uid),file=group.resolve("cpu.max");
        long budget=dev.local.fuxicontrol.core.CpuQuota.budget(quota,foreground,exempt);
        JSONObject result=new JSONObject().put("configured",quota).put("phase",foreground?"foreground":"background").put("applied",false);
        if(V1CpuQuota.supported()) {
            var members=new java.util.ArrayList<>(processes.getOrDefault(uid,java.util.List.of()));
            for(int isolated:isolatedUids)members.addAll(processes.getOrDefault(isolated,java.util.List.of()));
            // The v1 group belongs to the owner: isolated UIDs share, not multiply, its budget.
            JSONObject applied=v1.apply(uid,budget,quota.getLong("periodUs"),members);
            for(String key:dev.local.fuxicontrol.core.Json.keys(applied))result.put(key,applied.get(key));
            return result;
        }
        if(!Files.exists(file)) return result.put("state",budget==0?"off":"pending-kernel").put("reason","需要启用 CFS bandwidth 的内核及经过验证的 UID CPU 层级");
        // Do not copy an app budget to each isolated UID: that would multiply its allowance.
        if(!isolatedUids.isEmpty()) {applyFile(file,null);return result.put("state","pending-layout").put("reason","隔离进程尚未归入同一个 CPU 预算组");}
        for(Path process:processes.getOrDefault(uid,java.util.List.of())) {
            try(var threads=Files.list(process.resolve("task"))) {
                for(Path task:threads.toList()) {
                    String relative=Text.read(task.resolve("cgroup")).lines().filter(line->line.startsWith("0::")).findFirst().orElseThrow().substring(3);
                    if(!relative.startsWith("/apps/uid_"+uid+"/")) {
                        applyFile(file,null);return result.put("state","pending-layout").put("reason","线程尚未归入统一 UID CPU 层级");
                    }
                }
            } catch(java.nio.file.NoSuchFileException exited) { /* Android removes exited tasks. */ }
        }
        applyFile(file,budget==0?null:budget+" "+quota.getLong("periodUs"));
        JSONObject statistics=new JSONObject();
        for(String line:Text.read(group.resolve("cpu.stat")).lines().toList()) {
            String[] fields=line.trim().split("\\s+");statistics.put(fields[0],Long.parseLong(fields[1]));
        }
        return result.put("state",budget==0?(exempt?"exempt":"off"):"applied").put("applied",budget>0)
                .put("cpuStat",statistics).put("actual",Text.read(file).trim());
    }
    private void applyFile(Path path,String desired) throws Exception {
        String key=path.toString();
        JSONObject previous=journal.optJSONObject(key);
        if(desired==null && previous==null) return;
        String current=Text.read(path).trim();
        long identity=Native.cgroupId(path.getParent().toString());
        if(previous!=null && previous.has("cgroupId") && previous.getLong("cgroupId")!=identity) {journal.remove(key);previous=null;}
        if(desired==null) {
            if(previous==null) return;
            if(current.equals(previous.getString("applied"))) Text.write(path,previous.getString("before"));
            journal.remove(key);persist();return;
        }
        if(previous!=null && !current.equals(previous.getString("applied"))) previous.put("before",current);
        if(previous!=null && current.equals(desired)) return;
        if(previous==null) {
            previous=new JSONObject().put("before",current).put("cgroupId",identity);journal.put(key,previous);
        }
        previous.put("applied",desired);persist();Text.write(path,desired);
    }
    private static String start(Path task) throws Exception {
        String stat=Text.read(task.resolve("stat"));
        return stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
    }
    private final java.util.Map<Integer,java.util.List<Path>> processes=new java.util.HashMap<>();
    public synchronized void scanProcesses() throws Exception {
        processes.clear();
        try(var proc=Files.list(Path.of("/proc"))) {
            for(Path process:proc.filter(p->p.getFileName().toString().matches("[0-9]+")).toList()) {
                try {
                    for(String line:Text.read(process.resolve("status")).split("\n")) if(line.startsWith("Uid:")) {
                        int uid=Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                        processes.computeIfAbsent(uid,k->new java.util.ArrayList<>()).add(process);break;
                    }
                } catch(java.nio.file.NoSuchFileException gone) { /* Process exited during enumeration. */ }
            }
        }
    }
    public synchronized void scheduling(int uid,int nice,int clamp,boolean eligible) throws Exception {
        for(Path process:processes.getOrDefault(uid,java.util.List.of())) {
            try(var tids=Files.list(process.resolve("task"))) {
                var changes=new java.util.ArrayList<TaskChange>();
                for(Path task:tids.toList()) updateTask(task,nice,clamp,eligible && (nice!=0 || clamp!=1024),changes);
                applyTaskChanges(changes);
            } catch(java.nio.file.NoSuchFileException gone) { /* Process exited during reconciliation. */ }
        }
    }
    private final Set<Long> currentNetworks=new HashSet<>();
    public synchronized void beginProcesses() throws Exception {
        currentNetworks.clear();affinity.begin();
        // Exited threads cannot be restored. Keep the journal bounded to live identities.
        for(String key:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(journal))) {
            if(!key.startsWith("task:"))continue;
            try {
                if(!start(Path.of("/proc/"+key.substring(5))).equals(journal.getJSONObject(key).getString("start")))journal.remove(key);
            } catch(java.nio.file.NoSuchFileException exited) {journal.remove(key);}
        }
        persistLazy();
    }
    public synchronized void finishProcesses() throws Exception {
        affinity.finish();
        for(String key:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(journal))) if(key.startsWith("network:")) {
            long id=Long.parseLong(key.substring(8));
            if(!currentNetworks.contains(id)) {Native.processNetworkApply(id,0,0,65536,0);journal.remove(key);}
        }
    }
    public synchronized JSONObject affinityPreview(int uid,JSONObject app,boolean foreground,boolean ceiling) throws Exception {return affinity.preview(processes.getOrDefault(uid,java.util.List.of()),app,foreground,ceiling);}
    public synchronized void affinity(int uid,JSONObject app,boolean foreground,boolean ceiling) throws Exception {
        affinity.apply(uid,processes.getOrDefault(uid,java.util.List.of()),app,foreground,ceiling);
    }
    public synchronized void processRules(int uid,JSONObject policy,boolean foreground,boolean activity,boolean quotaProtected) throws Exception {
        JSONObject rules=policy.optJSONObject("processes");
        for(Path process:processes.getOrDefault(uid,java.util.List.of())) {
            try {
                String name=Text.read(process.resolve("cmdline")).split("\\x00",2)[0];
                JSONObject rule=rules==null?null:rules.optJSONObject(name);
                String relative=Text.read(process.resolve("cgroup")).lines().filter(l->l.startsWith("0::")).findFirst().orElseThrow().substring(3);
                if(!relative.startsWith("/apps/uid_"+uid+"/pid_")) continue;
                Path group=Path.of("/sys/fs/cgroup"+relative);
                JSONObject effective=dev.local.fuxicontrol.core.ProcessPolicy.effective(policy,rule==null?new JSONObject():rule,foreground,activity);
                try(var tids=Files.list(process.resolve("task"))) {
                    var changes=new java.util.ArrayList<TaskChange>();
                    for(Path task:tids.toList()) updateTask(task,effective.getInt("cpuNice"),effective.getInt("uclampMax"),effective.getInt("cpuNice")!=0 || effective.getInt("uclampMax")!=1024,changes);
                    applyTaskChanges(changes);
                }
                Path cpu=group.resolve("cpu.max");
                if(Files.exists(cpu)) {
                    JSONObject quota=effective.getJSONObject("cpuQuota");
                    long budget=dev.local.fuxicontrol.core.CpuQuota.budget(quota,foreground,effective.getBoolean("exempt")||quotaProtected);
                    applyFile(cpu,budget==0?null:budget+" "+quota.getLong("periodUs"));
                }
                long high=effective.getLong("memoryHighMiB"),max=effective.getLong("memoryMaxMiB");
                applyFile(group.resolve("memory.high"),high==0?null:Long.toString(high*1048576));
                applyFile(group.resolve("memory.max"),max==0?null:Long.toString(max*1048576));
                long id=Native.cgroupId(group.toString());
                int deny=effective.getString("network").equals("deny") || (effective.getString("network").equals("background-deny")&&!foreground&&!activity)?3:0;
                if(effective.getLong("uploadBps")>0 || effective.getLong("downloadBps")>0 || deny!=0) {
                    if(!journal.has("network:"+id)) {journal.put("network:"+id,new JSONObject());persist();}
                    Native.processNetworkApply(id,effective.getLong("uploadBps"),effective.getLong("downloadBps"),effective.getInt("burstBytes"),deny);currentNetworks.add(id);
                }
            } catch(java.nio.file.NoSuchFileException exited) { /* Process exited while reconciling. */ }
        }
    }
    private record TaskChange(Path task,String start,int nice,int minimum,int maximum) {}
    private void applyTaskChanges(java.util.List<TaskChange> changes) throws Exception {
        if(changes.isEmpty())return;
        persist(); // One durable recovery snapshot per process, before any scheduling mutation.
        for(TaskChange change:changes) {
            try {
                if(!start(change.task).equals(change.start))continue;
                int tid=Integer.parseInt(change.task.getFileName().toString());
                Process.setThreadPriority(tid,change.nice);Native.clamp(tid,change.minimum,change.maximum);
            } catch(java.nio.file.NoSuchFileException exited) { /* Thread exited before applying. */ }
        }
    }
    private void updateTask(Path task,int nice,int clamp,boolean eligible,java.util.List<TaskChange> changes) throws Exception {
        int tid=Integer.parseInt(task.getFileName().toString());
        String key="task:"+tid;
        JSONObject entry=journal.optJSONObject(key);
        if(!eligible && entry==null) return;
        String identity=start(task);
        if(entry!=null && !identity.equals(entry.getString("start"))) {journal.remove(key);entry=null;}
        int current;
        try {current=Process.getThreadPriority(tid);} catch(IllegalArgumentException gone) {return;}
        if(!eligible) {
            if(entry!=null) {
                if(current==entry.getInt("appliedNice")) Process.setThreadPriority(tid,entry.getInt("beforeNice"));
                int[] active=Native.getClamp(tid);
                int maximum=active[1]==entry.getInt("appliedClamp")?entry.getInt("beforeClamp"):active[1];
                int minimum=active[0]==entry.getInt("appliedMin")?entry.getInt("beforeMin"):active[0];
                if(minimum!=active[0] || maximum!=active[1]) Native.clamp(tid,Math.min(minimum,maximum),maximum);
                journal.remove(key);
            }
            return;
        }
        int[] original=Native.getClamp(tid);
        if(entry==null) {
            if((nice==0 || current>=nice) && original[1]<=clamp) return;
            entry=new JSONObject().put("start",identity).put("beforeNice",current).put("beforeMin",original[0]).put("beforeClamp",original[1]);
            journal.put(key,entry);
        }
        if(entry.has("appliedNice") && current!=entry.getInt("appliedNice")) entry.put("beforeNice",current);
        if(entry.has("appliedClamp") && original[1]!=entry.getInt("appliedClamp")) entry.put("beforeClamp",original[1]);
        if(entry.has("appliedMin") && original[0]!=entry.getInt("appliedMin")) entry.put("beforeMin",original[0]);
        clamp=Math.min(clamp,entry.getInt("beforeClamp"));
        int applied=nice==0?entry.getInt("beforeNice"):Math.max(entry.getInt("beforeNice"),nice);
        int minimum=Math.min(entry.getInt("beforeMin"),clamp);
        if(entry.optInt("appliedNice",-1)==applied && entry.optInt("appliedClamp",-1)==clamp && entry.optInt("appliedMin",-1)==minimum && current==applied) return;
        entry.put("appliedNice",applied).put("appliedClamp",clamp).put("appliedMin",minimum);
        changes.add(new TaskChange(task,identity,applied,minimum,clamp));
    }
    public synchronized void restoreAll() throws Exception {
        v1.restoreAll();
        beginProcesses();finishProcesses();
        if(journal.length()==0) return;
        for(String key:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(journal))) {
            if(key.startsWith("task:")) {
                int tid=Integer.parseInt(key.substring(5));
                Path task=Path.of("/proc/"+tid);
                if(Files.exists(task)) updateTask(task,0,1024,false,java.util.List.of());else journal.remove(key);
            } else {
                Path path=Path.of(key);
                if(Files.exists(path)) applyFile(path,null);else journal.remove(key);
            }
        }
        persist();
    }
    public void flush() throws Exception {persistLazy();}
}
