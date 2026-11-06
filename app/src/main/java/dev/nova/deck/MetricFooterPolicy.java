package dev.nova.deck;

/** Reserves scrolling space for the monitoring footer. */
final class MetricFooterPolicy {
    private MetricFooterPolicy() { }

    static int scrollReserve(int stripHeight,int gap,int bottomMargin,boolean visible){
        return visible?Math.max(0,stripHeight)+Math.max(0,gap)+Math.max(0,bottomMargin):0;
    }
}
