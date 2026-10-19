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
    public ResourceController(Path root) throws Exception {
        journalFile=root.resolve("resources.json");
        bootId=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        JSONObject saved=Files.exists(journalFile)?new JSONObject(Text.read(journalFile)):new JSONObject();
        journal=dev.local.fuxicontrol.core.ResourceJournal.entries(saved,bootId);
    }
    private void persist() throws Exception {Store.atomic(journalFile,new JSONObject().put("bootId",bootId).put("entries",journal).toString());}
    public synchronized void memory(int uid,long highMiB,long maxMiB) throws Exception {
        Path group=Path.of("/sys/fs/cgroup/apps/uid_"+uid);
        if(!Files.isDirectory(group)) return; // Android removes empty UID groups.
        applyFile(group.resolve("memory.high"), highMiB==0?null:Long.toString(Math.multiplyExact(highMiB,1048576)));
        applyFile(group.resolve("memory.max"), maxMiB==0?null:Long.toString(Math.multiplyExact(maxMiB,1048576)));
    }
    private void applyFile(Path path,String desired) throws Exception {
        String key=path.toString();String current=Text.read(path).trim();
        JSONObject previous=journal.optJSONObject(key);
        if(desired==null) {
            if(previous==null) return;
            if(current.equals(previous.getString("applied"))) Text.write(path,previous.getString("before"));
            journal.remove(key);persist();return;
        }
        if(previous!=null && !current.equals(previous.getString("applied"))) previous.put("before",current);
        if(previous!=null && current.equals(desired)) return;
        if(previous==null) {
            previous=new JSONObject().put("before",current);journal.put(key,previous);
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
                for(Path task:tids.toList()) updateTask(task,nice,clamp,eligible && (nice!=0 || clamp!=1024));
            } catch(java.nio.file.NoSuchFileException gone) { /* Process exited during reconciliation. */ }
        }
    }
    private void updateTask(Path task,int nice,int clamp,boolean eligible) throws Exception {
        int tid=Integer.parseInt(task.getFileName().toString());
        String key="task:"+tid;String identity=start(task);
        JSONObject entry=journal.optJSONObject(key);
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
        entry.put("appliedNice",applied).put("appliedClamp",clamp).put("appliedMin",minimum);persist();
        Process.setThreadPriority(tid,applied);Native.clamp(tid,minimum,clamp);
    }
    public synchronized void restoreAll() throws Exception {
        if(journal.length()==0) return;
        for(String key:new HashSet<>(dev.local.fuxicontrol.core.Json.keys(journal))) {
            if(key.startsWith("task:")) {
                int tid=Integer.parseInt(key.substring(5));
                Path task=Path.of("/proc/"+tid);
                if(Files.exists(task)) updateTask(task,0,1024,false);else journal.remove(key);
            } else {
                Path path=Path.of(key);
                if(Files.exists(path)) applyFile(path,null);else journal.remove(key);
            }
        }
        persist();
    }
    public void flush() throws Exception {persist();}
}
