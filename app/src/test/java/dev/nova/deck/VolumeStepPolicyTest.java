package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeStepPolicyTest {
    @Test public void mapsBothSupportedEndpointsAndClampsRequests() {
        assertEquals(4, VolumeStepPolicy.indexForPercent(0, 4, 16));
        assertEquals(16, VolumeStepPolicy.indexForPercent(100, 4, 16));
        assertEquals(0, VolumeStepPolicy.indexForPercent(-20, 0, 15));
        assertEquals(15, VolumeStepPolicy.indexForPercent(120, 0, 15));
        assertEquals(0, VolumeStepPolicy.percentForIndex(0, 4, 16));
        assertEquals(100, VolumeStepPolicy.percentForIndex(99, 4, 16));
    }

    @Test public void quantizesToMonotonicSupportedStepsAndRoundTrips() {
        int previous = -1;
        for (int requested = 0; requested <= 100; requested++) {
            int index = VolumeStepPolicy.indexForPercent(requested, 3, 17);
            assertTrue(index >= 3 && index <= 17);
            assertTrue(index >= previous);
            previous = index;
            int represented = VolumeStepPolicy.percentForIndex(index, 3, 17);
            assertEquals(index, VolumeStepPolicy.indexForPercent(represented, 3, 17));
            assertEquals(represented, VolumeStepPolicy.representedPercent(requested, 3, 17));
        }
        for (int index = 3; index <= 17; index++) {
            assertEquals(index, VolumeStepPolicy.indexForPercent(
                    VolumeStepPolicy.percentForIndex(index, 3, 17), 3, 17));
        }
    }

    @Test public void fixedRangeNeverDividesByZero() {
        for (int requested = 0; requested <= 100; requested++) {
            assertEquals(7, VolumeStepPolicy.indexForPercent(requested, 7, 7));
            assertEquals(0, VolumeStepPolicy.percentForIndex(7, 7, 7));
        }
    }
}
