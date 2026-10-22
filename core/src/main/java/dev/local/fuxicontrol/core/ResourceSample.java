package dev.local.fuxicontrol.core;

/** /proc stat fields after the parenthesized comm, which may contain spaces or ')'. */
public record ResourceSample(long ticks,long start,long rssBytes) {
    public static ResourceSample parse(String stat,long pageSize) {
        String[] fields=stat.substring(stat.lastIndexOf(')')+2).trim().split("\\s+");
        return new ResourceSample(Long.parseLong(fields[11])+Long.parseLong(fields[12]),Long.parseLong(fields[19]),Long.parseLong(fields[21])*pageSize);
    }
    public static double processCpu(long ticks,long before,long elapsedMs,long ticksPerSecond,long cpus) {
        return elapsedMs>0 && ticks>=before?100000.0*(ticks-before)/(elapsedMs*ticksPerSecond*cpus):Double.NaN;
    }
    public static double cpu(long ticks,long previous,long system,long beforeSystem) {
        return system>beforeSystem && ticks>=previous?100.0*(ticks-previous)/(system-beforeSystem):Double.NaN;
    }
}
