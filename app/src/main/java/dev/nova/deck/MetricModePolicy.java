package dev.nova.deck;

/** Shared global choice for each clock/load telemetry tile. */
final class MetricModePolicy {
    static final String CLOCK="clock";
    static final String USAGE="usage";
    static final String CPU_PREF="cpu_metric_mode";
    static final String GPU_PREF="gpu_metric_mode";

    private MetricModePolicy() { }

    static String normalize(String value){return USAGE.equalsIgnoreCase(value==null?"":value.trim())?USAGE:CLOCK;}
    static String toggle(String value){return normalize(value).equals(CLOCK)?USAGE:CLOCK;}
    static boolean isValidUsage(float value){return !Float.isNaN(value)&&!Float.isInfinite(value)&&value>=0f&&value<=100f;}
}
