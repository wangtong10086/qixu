package dev.local.fuxicontrol.service;

import org.json.JSONObject;
import java.nio.file.*;
import java.util.*;

/** One CFS budget per UID, with reversible thread membership on Android's v1 CPU tree. */
final class V1CpuQuota {
    private static final Path ROOT=Path.of("/dev/cpuctl");
    private final Path file;
    private final String boot;
    private final JSONObject groups;
    V1CpuQuota(Path directory) throws Exception {
        file=directory.resolve("cpu-v1.json");boot=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        JSONObject saved=Files.exists(file)?new JSONObject(Text.read(file)):new JSONObject();
        groups=boot.equals(saved.optString("boot"))?saved.getJSONObject("groups"):new JSONObject();
    }
    static boolean supported() {return Files.exists(ROOT.resolve("cpu.cfs_quota_us"));}
    private String persisted;
    private void save() throws Exception {
        String value=new JSONObject().put("boot",boot).put("groups",groups).toString();
        if(!value.equals(persisted)) {Store.atomic(file,value);persisted=value;}
    }
    private static void move(Path destination,String tid) throws Exception {
        java.io.FileDescriptor fd=android.system.Os.open(destination.toString(),android.system.OsConstants.O_WRONLY,0);
        try {
            byte[] bytes=tid.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            android.system.Os.write(fd,bytes,0,bytes.length);
        } catch(android.system.ErrnoException failure) {
            if(failure.errno!=android.system.OsConstants.ESRCH)throw failure;
        } finally {android.system.Os.close(fd);}
    }
    private static String identity(Path task) throws Exception {
        String s=Text.read(task.resolve("stat"));return s.substring(s.lastIndexOf(')')+2).split(" ")[19];
    }
    private static String cpuGroup(Path task) throws Exception {
        for(String line:Text.read(task.resolve("cgroup")).split("\n")) {
            String[] fields=line.split(":",3);
            if(Arrays.asList(fields[1].split(",")).contains("cpu"))return fields[2];
        }
        throw new IllegalStateException("Task has no CPU controller: "+task);
    }
    private static Path group(int uid) {return ROOT.resolve("qixu_uid_"+uid);}
    synchronized JSONObject apply(int uid,long budget,long period,List<Path> processes) throws Exception {
        if(budget==0) {restore(uid);return new JSONObject().put("state","off").put("applied",false);}
        if(uid<10000) throw new IllegalArgumentException("CPU quota requires an application UID");
        String key=Integer.toString(uid);Path group=group(uid);
        JSONObject entry=groups.optJSONObject(key);
        if(entry==null) {
            if(processes.isEmpty())return new JSONObject().put("state","waiting-process").put("applied",false);
            Files.createDirectory(group);
            entry=new JSONObject().put("threads",new JSONObject()).put("fallback","/foreground");groups.put(key,entry);save();
        }
        // Relax first when changing the period: the old quota may exceed a smaller period.
        if(!Text.read(group.resolve("cpu.cfs_period_us")).trim().equals(Long.toString(period))) {
            Text.write(group.resolve("cpu.cfs_quota_us"),"-1");Text.write(group.resolve("cpu.cfs_period_us"),Long.toString(period));
        }
        Text.write(group.resolve("cpu.cfs_quota_us"),Long.toString(budget));
        JSONObject threads=entry.getJSONObject("threads");List<Path> moves=new ArrayList<>();
        String own="/"+group.getFileName();
        for(Path process:processes) {
            try(var tasks=Files.list(process.resolve("task"))) {
                for(Path task:tasks.toList()) {
                    try {
                        String tid=task.getFileName().toString(),id=identity(task),current=cpuGroup(task);
                        JSONObject previous=threads.optJSONObject(tid);
                        if(!current.equals(own)) {
                            entry.put("fallback",current);
                            threads.put(tid,new JSONObject().put("start",id).put("before",current));moves.add(task);
                        } else if(previous==null || !id.equals(previous.getString("start"))) {
                            threads.put(tid,new JSONObject().put("start",id).put("before",entry.getString("fallback")));
                        }
                    } catch(NoSuchFileException exited) { /* Threads can exit during sampling. */ }
                }
            } catch(NoSuchFileException exited) { /* Process exited. */ }
        }
        for(String tid:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(threads))) if(!Files.exists(Path.of("/proc/"+tid)))threads.remove(tid);
        save(); // Membership recovery is durable before moving any task.
        for(Path task:moves) {
            String tid=task.getFileName().toString();
            try {
                if(identity(task).equals(threads.getJSONObject(tid).getString("start")))move(group.resolve("tasks"),tid);
            } catch(NoSuchFileException exited) { /* Exited before migration. */ }
        }
        JSONObject stat=new JSONObject();
        for(String line:Text.read(group.resolve("cpu.stat")).lines().toList()) {
            String[] pair=line.trim().split("\\s+");stat.put(pair[0],Long.parseLong(pair[1]));
        }
        return new JSONObject().put("state","applied").put("applied",true).put("backend","cgroup-v1-uid")
                .put("actual",budget+" "+period).put("cpuStat",stat).put("reassignedThreads",moves.size());
    }
    synchronized void restore(int uid) throws Exception {
        String key=Integer.toString(uid);JSONObject entry=groups.optJSONObject(key);if(entry==null)return;
        Path group=group(uid);String own="/"+group.getFileName();
        if(Files.exists(group)) {
            Text.write(group.resolve("cpu.cfs_quota_us"),"-1");
            for(String tid:Text.read(group.resolve("tasks")).lines().toList()) {
                Path task=Path.of("/proc/"+tid);
                try {
                    if(!cpuGroup(task).equals(own))continue;
                    JSONObject before=entry.getJSONObject("threads").optJSONObject(tid);
                    String destination=before!=null && identity(task).equals(before.getString("start"))?before.getString("before"):entry.getString("fallback");
                    move(ROOT.resolve(destination.substring(1)).resolve("tasks"),tid);
                } catch(NoSuchFileException exited) { /* Thread exited. */ }
            }
            Files.delete(group);
        }
        groups.remove(key);save();
    }
    synchronized void restoreAll() throws Exception {
        for(String uid:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(groups)))restore(Integer.parseInt(uid));
    }
}
