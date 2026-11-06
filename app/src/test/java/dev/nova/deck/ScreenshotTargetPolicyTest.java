package dev.nova.deck;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ScreenshotTargetPolicyTest {
    @Test public void normalizesPersistedTargetsAndDefaultsUnknownValuesToMain(){
        assertEquals(ScreenshotTargetPolicy.MAIN,ScreenshotTargetPolicy.normalize(null));
        assertEquals(ScreenshotTargetPolicy.EXTERNAL,ScreenshotTargetPolicy.normalize(" EXTERNAL "));
        assertEquals(ScreenshotTargetPolicy.BOTH,ScreenshotTargetPolicy.normalize("Both"));
        assertEquals(ScreenshotTargetPolicy.MAIN,ScreenshotTargetPolicy.normalize("primary"));
    }

    @Test public void cyclesTargetsInStableMainExternalBothOrder(){
        assertEquals(ScreenshotTargetPolicy.EXTERNAL,ScreenshotTargetPolicy.cycle(ScreenshotTargetPolicy.MAIN));
        assertEquals(ScreenshotTargetPolicy.BOTH,ScreenshotTargetPolicy.cycle(ScreenshotTargetPolicy.EXTERNAL));
        assertEquals(ScreenshotTargetPolicy.MAIN,ScreenshotTargetPolicy.cycle(ScreenshotTargetPolicy.BOTH));
        assertEquals(ScreenshotTargetPolicy.EXTERNAL,ScreenshotTargetPolicy.cycle("invalid"));
    }

    @Test public void selectsOnlyRequestedDisplaysAndRequiresAnExternalDisplayWhenNeeded(){
        assertArrayEquals(new int[]{0},ScreenshotTargetPolicy.selectDisplayIds("main",-1));
        assertArrayEquals(new int[]{7},ScreenshotTargetPolicy.selectDisplayIds("external",7));
        assertArrayEquals(new int[]{0,7},ScreenshotTargetPolicy.selectDisplayIds("both",7));
        assertThrows(IllegalStateException.class,()->ScreenshotTargetPolicy.selectDisplayIds("external",-1));
        assertThrows(IllegalStateException.class,()->ScreenshotTargetPolicy.selectDisplayIds("both",-1));
    }
}
