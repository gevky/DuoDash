package dev.nova.deck;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeModesTest {
    @Test public void usesNovaSystemUiFanModesRatherThanLegacyRetroidSettingsModes(){
        assertArrayEquals(new int[]{0,1,4,5,6},NativeModes.fanValues());
        assertEquals("Sport",NativeModes.fanLabels()[3]);
        assertTrue(NativeModes.validFan(5));
        assertFalse(NativeModes.validFan(2));assertFalse(NativeModes.validFan(3));
    }
    @Test public void refreshCyclesOnlySupportedUniqueRates(){
        assertEquals(120,NativeModes.nextRefresh(60,new int[]{120,60,120}));
        assertEquals(60,NativeModes.nextRefresh(120,new int[]{120,60}));
        assertEquals(60,NativeModes.nextRefresh(60,new int[]{60}));
        assertEquals(0,NativeModes.nextRefresh(60,new int[]{}));
    }
}
