package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class MetricFooterPolicyTest {
    @Test public void increasesScrollContentReservationWithPinnedFooterMargin(){
        assertEquals(132,MetricFooterPolicy.scrollReserve(96,12,24,true));
        assertEquals(172,MetricFooterPolicy.scrollReserve(96,12,64,true));
        assertEquals(0,MetricFooterPolicy.scrollReserve(96,12,64,false));
    }
}
