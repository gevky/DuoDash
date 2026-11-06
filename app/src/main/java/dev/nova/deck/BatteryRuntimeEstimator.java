package dev.nova.deck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Conservative remaining-time estimate from reported charge and recent discharge current. */
public final class BatteryRuntimeEstimator {
    private static final long WINDOW_MS=30_000;
    private static final long MIN_WINDOW_MS=20_000;
    private static final int MIN_SAMPLES=5;
    private static final int MIN_DRAIN_UA=100_000;
    private static final int MAX_DRAIN_UA=8_000_000;
    private static final long MAX_CHARGE_UAH=30_000_000;
    private static final int MAX_MINUTES=72*60;
    private final Deque<Sample> samples=new ArrayDeque<>();

    /** Prefer the hardware average; use the instantaneous value only when the average is unavailable or implausible. */
    public static int chooseDischargeCurrent(int averageMicroAmps,int instantaneousMicroAmps){
        return validDischargeCurrent(averageMicroAmps)?averageMicroAmps:
            validDischargeCurrent(instantaneousMicroAmps)?instantaneousMicroAmps:Integer.MIN_VALUE;
    }
    private static boolean validDischargeCurrent(int current){return current!=Integer.MIN_VALUE&&current < -MIN_DRAIN_UA&&current>=-MAX_DRAIN_UA;}

    /** Returns rounded minutes, or -1 until readings are valid and have warmed up. */
    public synchronized int update(boolean discharging,boolean powered,long remainingChargeMicroAh,
                                   int averageCurrentMicroAmps,long elapsedRealtimeMillis){
        if(!discharging||powered||remainingChargeMicroAh<=0||remainingChargeMicroAh>MAX_CHARGE_UAH
                ||averageCurrentMicroAmps==Integer.MIN_VALUE||averageCurrentMicroAmps>=-MIN_DRAIN_UA
                ||averageCurrentMicroAmps < -MAX_DRAIN_UA){samples.clear();return -1;}
        if(!samples.isEmpty()&&elapsedRealtimeMillis<=samples.peekLast().time){samples.clear();return -1;}
        samples.addLast(new Sample(elapsedRealtimeMillis,-averageCurrentMicroAmps));
        while(!samples.isEmpty()&&elapsedRealtimeMillis-samples.peekFirst().time>WINDOW_MS)samples.removeFirst();
        if(samples.size()<MIN_SAMPLES||elapsedRealtimeMillis-samples.peekFirst().time<MIN_WINDOW_MS)return -1;

        List<Integer> current=new ArrayList<>();for(Sample sample:samples)current.add(sample.drainMicroAmps);
        Collections.sort(current);int trim=current.size()/5;long sum=0;for(int i=trim;i<current.size()-trim;i++)sum+=current.get(i);
        int count=current.size()-trim*2;if(count<=0)return -1;
        double drainMicroAmps=(double)sum/count;
        double rawMinutes=remainingChargeMicroAh/drainMicroAmps*60.0;
        if(!Double.isFinite(rawMinutes)||rawMinutes<1||rawMinutes>MAX_MINUTES)return -1;
        if(rawMinutes<5)return 1;
        return Math.min(MAX_MINUTES,((int)Math.round(rawMinutes/5.0))*5);
    }

    private static final class Sample {final long time;final int drainMicroAmps;Sample(long time,int drain){this.time=time;drainMicroAmps=drain;}}
}
