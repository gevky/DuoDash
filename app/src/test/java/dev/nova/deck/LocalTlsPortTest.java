package dev.nova.deck;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class LocalTlsPortTest {
    @Test public void acceptsOnlyValidLocalPorts(){
        assertEquals(34623,OnDeviceBridge.parseLocalTlsPort(" 34623\n"));
        assertEquals(0,OnDeviceBridge.parseLocalTlsPort(""));
        assertEquals(0,OnDeviceBridge.parseLocalTlsPort("0"));
        assertEquals(0,OnDeviceBridge.parseLocalTlsPort("65536"));
        assertEquals(0,OnDeviceBridge.parseLocalTlsPort("34623; id"));
        assertEquals(0,OnDeviceBridge.parseLocalTlsPort(null));
    }
}
