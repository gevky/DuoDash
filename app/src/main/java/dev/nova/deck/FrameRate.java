package dev.nova.deck;
import java.util.*;

/** Android presentation cadence from SurfaceFlinger latency records, never panel Hz. */
public final class FrameRate {
    private FrameRate(){}
    public static float fromLatency(String text,long nowNanos){
        TreeSet<Long> times=new TreeSet<>();String[] rows=text.split("\n");
        for(int i=1;i<rows.length;i++){String[] columns=rows[i].trim().split("\\s+");if(columns.length<3)continue;try{long time=Long.parseLong(columns[1]);if(time>0&&time<Long.MAX_VALUE&&time<=nowNanos+100000000L)times.add(time);}catch(NumberFormatException ignored){}}
        if(times.size()<3)return Float.NaN;
        long last=times.last();if(nowNanos-last>1500000000L)return 0f;
        SortedSet<Long> recent=times.tailSet(last-1000000000L);if(recent.size()<3)return Float.NaN;
        float fps=(recent.size()-1)*1000000000f/(last-recent.first());return fps>=0&&fps<=500?fps:Float.NaN;
    }
}
