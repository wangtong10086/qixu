package dev.local.fuxicontrol.service;

import dev.local.fuxicontrol.core.AffinityPolicy;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class CpuTopology {
    public final SortedSet<Integer> possible;
    public final Map<String,SortedSet<Integer>> groups=new LinkedHashMap<>();
    private final JSONObject capacities=new JSONObject();
    public CpuTopology() throws Exception {
        possible=AffinityPolicy.parse(Text.read(Path.of("/sys/devices/system/cpu/possible")).trim());
        var byCapacity=new TreeMap<Integer,SortedSet<Integer>>();
        for(int cpu:possible) {
            Path file=Path.of("/sys/devices/system/cpu/cpu"+cpu+"/cpu_capacity");
            if(!Files.exists(file))continue;
            int capacity=Integer.parseInt(Text.read(file).trim());capacities.put(Integer.toString(cpu),capacity);
            byCapacity.computeIfAbsent(capacity,k->new TreeSet<>()).add(cpu);
        }
        groups.put("little",new TreeSet<>());groups.put("big",new TreeSet<>());groups.put("prime",new TreeSet<>());
        if(capacities.length()==possible.size()) {
            groups.get("little").addAll(byCapacity.firstEntry().getValue());
            if(byCapacity.size()>1)groups.get("prime").addAll(byCapacity.lastEntry().getValue());
            for(var entry:byCapacity.entrySet())if(entry.getKey()!=byCapacity.firstKey().intValue()&&entry.getKey()!=byCapacity.lastKey().intValue())groups.get("big").addAll(entry.getValue());
        }
    }
    public SortedSet<Integer> online() throws Exception {return AffinityPolicy.parse(Text.read(Path.of("/sys/devices/system/cpu/online")).trim());}
    public SortedSet<Integer> select(String selector) {
        if(selector.equals("system"))return new TreeSet<>(possible);
        return new TreeSet<>(groups.containsKey(selector)?groups.get(selector):AffinityPolicy.parse(selector));
    }
    public void validate(String selector,boolean background) {
        var selected=select(selector);
        if(selected.isEmpty()||!possible.containsAll(selected))throw new IllegalArgumentException("Unavailable CPU selection: "+selector);
        if(background) {selected.retainAll(groups.get("little"));if(selected.isEmpty())throw new IllegalArgumentException("后台规则必须包含小核："+selector);}
    }
    public JSONObject json() throws Exception {
        JSONArray cores=new JSONArray();var online=online();
        for(int cpu:possible) {
            JSONObject core=new JSONObject().put("id",cpu).put("online",online.contains(cpu)).put("capacity",capacities.opt(Integer.toString(cpu)));
            Path frequency=Path.of("/sys/devices/system/cpu/cpu"+cpu+"/cpufreq/cpuinfo_max_freq");
            if(Files.exists(frequency))core.put("maxFrequencyKHz",Long.parseLong(Text.read(frequency).trim()));
            cores.put(core);
        }
        JSONObject values=new JSONObject();for(var entry:groups.entrySet())values.put(entry.getKey(),new JSONArray(entry.getValue()));
        return new JSONObject().put("cores",cores).put("groups",values).put("possible",new JSONArray(possible)).put("online",new JSONArray(online)).put("backgroundSmallCoresOnlySupported",!groups.get("little").isEmpty());
    }
}
