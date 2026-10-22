package dev.local.fuxicontrol.core;

import java.util.ArrayDeque;

/** Only contiguous measured intervals contribute; suspend and missing samples invalidate the window. */
public final class CpuWindow {
    private record Point(long at,Double percent) {}
    private final ArrayDeque<Point> points=new ArrayDeque<>();
    public void add(long at,Double percent) {
        if(!points.isEmpty() && (at<=points.getLast().at || at-points.getLast().at>5000)) points.clear();
        points.addLast(new Point(at,percent));
        while(points.size()>1 && points.stream().skip(1).findFirst().orElseThrow().at<=at-30000) points.removeFirst();
    }
    public Double average(long now) {
        if(points.size()<2 || now-points.getLast().at>5000 || points.getLast().at-points.getFirst().at<30000) return null;
        long start=points.getLast().at-30000,previous=points.getFirst().at;double weighted=0;
        boolean first=true;
        for(Point point:points) {
            if(first) {first=false;continue;}
            long duration=point.at-Math.max(previous,start);previous=point.at;
            if(duration<=0) continue;
            if(point.percent==null || !Double.isFinite(point.percent)) return null;
            weighted+=point.percent*duration;
        }
        return weighted/30000;
    }
}
