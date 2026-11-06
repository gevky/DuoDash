package dev.nova.deck.bridge;

/** Computes aggregate device CPU utilization from successive Linux /proc/stat samples. */
final class CpuLoadSampler {
    private static final String WAITING="Waiting for CPU sample";
    private static final String UNAVAILABLE="CPU counters unavailable";
    private long[] previous;

    static final class Reading {
        private final double percent;
        private final String reason;
        private Reading(double percent,String reason){this.percent=percent;this.reason=reason;}
        boolean available(){return Double.isFinite(percent);}
        double percent(){return percent;}
        String reason(){return reason;}
    }

    Reading sample(String procStat){
        long[] current=parseAggregate(procStat);
        if(current==null)return unavailable(UNAVAILABLE);
        if(previous==null){previous=current;return unavailable(WAITING);}
        long[] baseline=previous;
        previous=current;
        for(int i=0;i<current.length;i++)if(current[i]<baseline[i])return unavailable("CPU counters restarted");

        long oldTotal=sum(baseline),newTotal=sum(current);
        long oldIdle=baseline[3]+baseline[4],newIdle=current[3]+current[4];
        long deltaTotal=newTotal-oldTotal,deltaIdle=newIdle-oldIdle;
        if(deltaTotal<=0)return unavailable(WAITING);
        long deltaBusy=deltaTotal-deltaIdle;
        if(deltaBusy<0||deltaBusy>deltaTotal)return unavailable(UNAVAILABLE);
        return new Reading(100.0*deltaBusy/deltaTotal,"");
    }

    static Reading unavailable(String reason){return new Reading(Double.NaN,reason);}

    private static long[] parseAggregate(String text){
        if(text==null)return null;
        for(String line:text.split("\\R")){
            String trimmed=line.trim();
            String[] fields=trimmed.split("\\s+");
            if(fields.length==0||!fields[0].equals("cpu"))continue;
            if(fields.length<5)return null;
            long[] values=new long[8];
            try{
                for(int i=0;i<values.length&&i+1<fields.length;i++){
                    values[i]=Long.parseLong(fields[i+1]);
                    if(values[i]<0)return null;
                }
                sum(values);
            }catch(NumberFormatException|ArithmeticException malformed){return null;}
            return values;
        }
        return null;
    }

    private static long sum(long[] values){
        long total=0;
        for(long value:values)total=Math.addExact(total,value);
        return total;
    }
}
