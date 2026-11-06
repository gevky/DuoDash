package dev.nova.deck.bridge;

/** Parsers for real driver-reported GPU utilization counters; never estimates from clock speed. */
final class GpuUsageParser {
    private GpuUsageParser(){}

    static final class Reading {
        private final double percent;
        private final String reason;
        private Reading(double percent,String reason){this.percent=percent;this.reason=reason;}
        boolean available(){return Double.isFinite(percent);}
        double percent(){return percent;}
        String reason(){return reason;}
    }

    static Reading parsePercentage(String value){
        if(value==null)return unavailable("GPU usage counters unavailable");
        String normalized=value.trim();
        if(normalized.endsWith("%"))normalized=normalized.substring(0,normalized.length()-1).trim();
        try{
            double percent=Double.parseDouble(normalized);
            return valid(percent)?new Reading(percent,""):unavailable("GPU usage counter out of range");
        }catch(NumberFormatException malformed){return unavailable("GPU usage counters unavailable");}
    }

    /** KGSL gpubusy emits two accumulated microsecond counters: busy time, then total time. */
    static Reading parseBusyTime(String value){
        if(value==null)return unavailable("GPU usage counters unavailable");
        String[] fields=value.trim().split("\\s+");
        if(fields.length!=2)return unavailable("GPU usage counters unavailable");
        try{
            long busy=Long.parseLong(fields[0]),total=Long.parseLong(fields[1]);
            if(busy<0||total<0||busy>total)return unavailable("GPU usage counter out of range");
            if(total==0)return unavailable("Waiting for GPU usage sample");
            double percent=100.0*busy/total;
            return valid(percent)?new Reading(percent,""):unavailable("GPU usage counter out of range");
        }catch(NumberFormatException malformed){return unavailable("GPU usage counters unavailable");}
    }

    static Reading unavailable(String reason){return new Reading(Double.NaN,reason);}

    private static boolean valid(double percent){return Double.isFinite(percent)&&percent>=0&&percent<=100;}
}
