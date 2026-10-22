package dev.local.fuxicontrol.service;

import android.system.ErrnoException;
import android.system.OsConstants;
import dev.local.fuxicontrol.core.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Root-owned affinity, with task identity and compare-before-restore ownership. */
public final class AffinityController {
    public final CpuTopology topology;
    private final Path file;
    private final String boot;
    private final JSONObject journal;
    private final Set<String> seen=new HashSet<>();
    private final Map<Integer,JSONArray> states=new HashMap<>();
    private final Map<Integer,Integer> processCounts=new HashMap<>();
    private final Map<Integer,JSONObject> processEntries=new HashMap<>();
    private String persisted;
    private boolean dirty;
    private final long ticksPerSecond=android.system.Os.sysconf(OsConstants._SC_CLK_TCK);
    public AffinityController(Path root) throws Exception {
        topology=new CpuTopology();file=root.resolve("affinity.json");
        boot=Text.read(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        journal=ResourceJournal.entries(Files.exists(file)?new JSONObject(Text.read(file)):new JSONObject().put("bootId",boot).put("entries",new JSONObject()),boot);
    }
    private void remember(String key,JSONObject entry) throws JSONException {
        journal.put(key,entry);
        processEntries.computeIfAbsent(entry.getInt("pid"),pid->new JSONObject()).put(key,entry);
        dirty=true;
    }
    private void remove(String key) throws JSONException {
        JSONObject entry=journal.optJSONObject(key);
        if(entry!=null){processEntries.get(entry.getInt("pid")).remove(key);journal.remove(key);dirty=true;}
    }
    private void applied(JSONObject entry,String value) throws JSONException {
        if(!value.equals(entry.optString("applied"))){entry.put("applied",value);dirty=true;}
    }
    private void save() throws Exception {
        if(!dirty)return;
        String next=new JSONObject().put("bootId",boot).put("entries",journal).toString();
        if(!next.equals(persisted)){Store.atomic(file,next);persisted=next;}
        dirty=false;
    }
    static SortedSet<Integer> mask(int tid) throws ErrnoException {var value=new TreeSet<Integer>();for(int cpu:Native.getAffinity(tid))value.add(cpu);return value;}
    static String identity(Path task) throws Exception {String stat=Text.read(task.resolve("stat"));return stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];}
    static SortedSet<Integer> allowed(Path task,Set<Integer> online) throws Exception {
        String path=Text.read(task.resolve("cpuset")).trim();
        Path group=Path.of("/dev/cpuset"+path);
        Path effective=group.resolve("effective_cpus");
        var cpus=AffinityPolicy.parse(Text.read(Files.exists(effective)?effective:group.resolve("cpus")).trim());
        cpus.retainAll(online);return cpus;
    }
    public void begin() throws JSONException {
        seen.clear();states.clear();processCounts.clear();processEntries.clear();
        // Inheritance is process-local. Avoid scanning every app's journal for every thread.
        for(String key:Json.keys(journal)) {
            JSONObject entry=journal.getJSONObject(key);
            processEntries.computeIfAbsent(entry.getInt("pid"),pid->new JSONObject()).put(key,entry);
        }
    }
    private record Change(Path task,String start,SortedSet<Integer> target,JSONObject state,boolean restore) {}
    public void apply(int uid,List<Path> processes,JSONObject app,boolean foreground,boolean backgroundCeiling) throws Exception {
        JSONArray result=new JSONArray();states.put(uid,result);
        String phase=foreground?"foreground":"background";processCounts.put(uid,processes.size());
        if(!AffinityPolicy.requiresBinding(app,phase,!foreground&&backgroundCeiling))return;
        var online=topology.online();
        List<Change> changes=new ArrayList<>();
        for(Path process:processes) {
            try {
                int pid=Integer.parseInt(process.getFileName().toString());String processStart=identity(process);
                String name=Text.read(process.resolve("cmdline")).split("\\x00",2)[0];
                JSONObject rules=app.optJSONObject("processes"),rule=rules==null?null:rules.optJSONObject(name);
                if(rule==null)rule=new JSONObject();
                try(var tasks=Files.list(process.resolve("task"))) {
                    for(Path task:tasks.toList()) {
                        try {
                            int tid=Integer.parseInt(task.getFileName().toString());String key=Integer.toString(tid),start=identity(task);
                            String comm=Text.read(task.resolve("comm")).stripTrailing();
                            var current=mask(tid);var permitted=allowed(task,online);
                            JSONObject selection=AffinityPolicy.resolve(app,rule,phase,pid==tid,comm);
                            String selector=selection.getString("selection");
                            JSONObject state=selection.put("pid",pid).put("tid",tid).put("process",name).put("name",comm).put("startTicks",start).put("processStartTicks",processStart)
                                .put("phase",phase).put("systemAllowed",new JSONArray(permitted)).put("actual",new JSONArray(current)).put("sampledAtElapsedMs",android.os.SystemClock.elapsedRealtime());
                            result.put(state);seen.add(key);
                            JSONObject entry=journal.optJSONObject(key);
                            if(entry!=null&&(!entry.getString("start").equals(start)||!entry.getString("processStart").equals(processStart)||entry.getInt("pid")!=pid)) {remove(key);entry=null;}
                            if(entry==null) {
                                JSONObject inherited=AffinityPolicy.inheritedBaseline(processEntries.computeIfAbsent(pid,id->new JSONObject()),pid,processStart,Long.parseLong(start),AffinityPolicy.format(current));
                                if(inherited!=null) {
                                    entry=new JSONObject().put("pid",pid).put("processStart",processStart).put("start",start)
                                        .put("before",inherited.getString("before")).put("systemBefore",inherited.getBoolean("systemBefore"))
                                        .put("applied",AffinityPolicy.format(current)).put("boundAtTicks",android.os.SystemClock.elapsedRealtime()*ticksPerSecond/1000);
                                    remember(key,entry);
                                }
                            }
                            boolean ceiling=!foreground&&backgroundCeiling;
                            state.put("backgroundCeiling",ceiling);
                            boolean managed=ceiling||!selector.equals("system");
                            if(!managed) {
                                state.put("state","system");
                                if(entry!=null)restoreChange(task,start,current,permitted,entry,state,changes);
                                continue;
                            }
                            var base=new TreeSet<>(permitted);if(ceiling)base.retainAll(topology.groups.get("little"));
                            var requested=topology.select(selector);var target=new TreeSet<>(base);target.retainAll(requested);
                            boolean blocked=target.isEmpty();
                            // An unavailable explicit selection must not remove the independent background ceiling.
                            if(blocked)target=new TreeSet<>(base);
                            state.put("requested",new JSONArray(requested)).put("effective",new JSONArray(target))
                                .put("state",blocked?"blocked":target.equals(requested)?"applied":"restricted");
                            if(blocked)state.put("reason","选择的核心与系统／后台允许范围没有交集");
                            if(target.isEmpty()) {state.put("state","blocked").put("reason","当前没有允许使用的在线核心");continue;}
                            if(entry!=null&&!AffinityPolicy.format(current).equals(entry.getString("applied"))) {
                                entry.put("before",AffinityPolicy.format(current)).put("systemBefore",current.equals(permitted));dirty=true;
                            }
                            if(entry==null&&!current.equals(target)) {
                                entry=new JSONObject().put("pid",pid).put("processStart",processStart).put("start",start)
                                    .put("before",AffinityPolicy.format(current)).put("systemBefore",current.equals(permitted))
                                    .put("boundAtTicks",android.os.SystemClock.elapsedRealtime()*ticksPerSecond/1000);
                                remember(key,entry);
                            }
                            if(entry!=null)applied(entry,AffinityPolicy.format(target));
                            if(!current.equals(target))changes.add(new Change(task,start,target,state,false));
                            else state.put("verified",true);
                        } catch(NoSuchFileException exited) { /* task exited */ }
                          catch(ErrnoException exited) {if(exited.errno!=OsConstants.ESRCH)throw exited;}
                    }
                }
            } catch(NoSuchFileException exited) { /* process exited */ }
        }
        save();execute(changes);save();
    }
    private void restoreChange(Path task,String start,Set<Integer> current,SortedSet<Integer> permitted,JSONObject entry,JSONObject state,List<Change> changes) throws JSONException {
        String key=task.getFileName().toString();
        if(!AffinityPolicy.format(current).equals(entry.getString("applied"))) {remove(key);return;}
        var target=entry.getBoolean("systemBefore")?new TreeSet<>(permitted):AffinityPolicy.parse(entry.getString("before"));target.retainAll(permitted);
        if(target.isEmpty()){state.put("state","blocked").put("reason","恢复目标暂无可用核心");return;}
        changes.add(new Change(task,start,target,state,true));
    }
    private void execute(List<Change> changes) throws Exception {
        for(Change change:changes) {
            String key=change.task.getFileName().toString();int tid=Integer.parseInt(key);
            try {
                if(!identity(change.task).equals(change.start)){remove(key);change.state.put("state","exited");continue;}
                Native.setAffinity(tid,change.target.stream().mapToInt(Integer::intValue).toArray());
                var actual=mask(tid);change.state.put("actual",new JSONArray(actual)).put("verified",actual.equals(change.target));
                if(!actual.equals(change.target))change.state.put("state","restricted");
                if(change.restore)remove(key);
                else applied(journal.getJSONObject(key),AffinityPolicy.format(actual));
            } catch(NoSuchFileException exited) {remove(key);change.state.put("state","exited");}
              catch(ErrnoException failure) {
                change.state.put("state",failure.errno==OsConstants.ESRCH?"exited":"error").put("verified",false).put("reason",failure.getMessage());
                if(failure.errno==OsConstants.ESRCH)remove(key);
            }
        }
    }
    public void finish() throws Exception {
        List<Change> changes=new ArrayList<>();var online=topology.online();
        for(String key:new HashSet<>(Json.keys(journal)))if(!seen.contains(key)) {
            JSONObject entry=journal.getJSONObject(key);Path task=Path.of("/proc/"+entry.getInt("pid")+"/task/"+key);
            try {
                if(!identity(task).equals(entry.getString("start"))||!identity(task.getParent().getParent()).equals(entry.getString("processStart"))) {remove(key);continue;}
                restoreChange(task,entry.getString("start"),mask(Integer.parseInt(key)),allowed(task,online),entry,new JSONObject(),changes);
            } catch(NoSuchFileException exited){remove(key);}
              catch(ErrnoException exited){if(exited.errno!=OsConstants.ESRCH)throw exited;remove(key);}
        }
        execute(changes);save();
    }
    public void restoreAll() throws Exception {begin();finish();}
    public int processCount(int uid) {return processCounts.getOrDefault(uid,0);}
    public JSONArray states(int uid) {return states.getOrDefault(uid,new JSONArray());}
    public JSONObject preview(List<Path> processes,JSONObject app,boolean foreground,boolean ceiling) throws Exception {
        JSONObject matches=new JSONObject();int count=0,restricted=0,blocked=0;var online=topology.online();
        String phase=foreground?"foreground":"background";
        for(Path process:processes)try {
            int pid=Integer.parseInt(process.getFileName().toString());
            String name=Text.read(process.resolve("cmdline")).split("\\x00",2)[0];
            JSONObject rules=app.optJSONObject("processes"),rule=rules==null?null:rules.optJSONObject(name);if(rule==null)rule=new JSONObject();
            try(var tasks=Files.list(process.resolve("task"))) {
                for(Path task:tasks.toList())try {
                    JSONObject selected=AffinityPolicy.resolve(app,rule,phase,task.getFileName().toString().equals(Integer.toString(pid)),Text.read(task.resolve("comm")).stripTrailing());
                    String source=name+"/"+selected.getString("source");matches.put(source,matches.optInt(source)+1);
                    var desired=topology.select(selected.getString("selection"));var actual=new TreeSet<>(desired);actual.retainAll(allowed(task,online));
                    if(!foreground&&ceiling)actual.retainAll(topology.groups.get("little"));
                    count++;if(actual.isEmpty())blocked++;else if(!actual.equals(desired))restricted++;
                }catch(NoSuchFileException exited){ /* task exited */ }
            }
        }catch(NoSuchFileException exited){ /* process exited */ }
        return new JSONObject().put("threads",count).put("restricted",restricted).put("blocked",blocked).put("matches",matches);
    }
    public void preflight(JSONObject config) throws Exception {
        JSONObject profiles=config.optJSONObject("profiles");
        if(profiles!=null)for(String id:Json.keys(profiles))preflight(profiles.getJSONObject(id).getJSONObject("config"));
        boolean ceiling=config.getJSONObject("scheduling").getBoolean("backgroundSmallCoresOnly");
        if(ceiling&&topology.groups.get("little").isEmpty())throw new IllegalArgumentException("无法识别小核分组");
        checkApp(config.getJSONObject("defaults"),ceiling);
        for(String pkg:Json.keys(config.getJSONObject("apps")))checkApp(config.getJSONObject("apps").getJSONObject(pkg),ceiling);
    }
    private void checkApp(JSONObject app,boolean ceiling) throws JSONException {
        JSONObject affinity=app.optJSONObject("cpuAffinity");
        if(affinity!=null)for(String phase:Json.keys(affinity))topology.validate(affinity.getString(phase),ceiling&&phase.equals("background"));
        JSONObject processes=app.optJSONObject("processes");
        if(processes!=null)for(String name:Json.keys(processes)) {
            JSONObject process=processes.getJSONObject(name);checkPhases(process,ceiling);
            JSONArray threads=process.optJSONArray("threads");if(threads!=null)for(int i=0;i<threads.length();i++)checkPhases(threads.getJSONObject(i),ceiling);
        }
    }
    private void checkPhases(JSONObject rule,boolean ceiling) throws JSONException {
        for(String phase:List.of("foreground","background")) {
            JSONObject value=rule.optJSONObject(phase);
            if(value!=null&&value.has("cpuAffinity"))topology.validate(value.getString("cpuAffinity"),ceiling&&phase.equals("background"));
        }
    }
}
