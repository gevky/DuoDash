package dev.nova.deck.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioStatusRecoveryTest {
    @Test public void transientPlayerQueryFailureCanRecover() {
        AudioStatusRecovery recovery = new AudioStatusRecovery(true);
        assertTrue(recovery.available());
        recovery.recordQuery(false);
        assertFalse(recovery.available());
        recovery.recordQuery(true);
        assertTrue(recovery.available());
    }

    @Test public void missingApiNeverAppearsAvailable() {
        AudioStatusRecovery recovery = new AudioStatusRecovery(false);
        recovery.recordQuery(true);
        assertFalse(recovery.available());
    }
}
