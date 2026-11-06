package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BootReconnectPolicyTest {
    @Test public void bootRecoveryRetriesLongEnoughForLateWirelessDebugStartup(){
        assertEquals(6,BootReconnectPolicy.attempts(true));
        assertEquals(0,BootReconnectPolicy.delayBeforeAttempt(true,0));
        assertEquals(3_000,BootReconnectPolicy.delayBeforeAttempt(true,1));
        assertEquals(5_000,BootReconnectPolicy.delayBeforeAttempt(true,2));
        assertEquals(10_000,BootReconnectPolicy.delayBeforeAttempt(true,4));
        assertEquals(10_000,BootReconnectPolicy.delayBeforeAttempt(true,5));
    }
    @Test public void manualReconnectStillRunsOnceWithoutDelay(){
        assertEquals(1,BootReconnectPolicy.attempts(false));
        assertEquals(0,BootReconnectPolicy.delayBeforeAttempt(false,1));
    }
    @Test public void acceptedPairingGetsOnlyBoundedHelperRecovery(){
        assertEquals(2,BootReconnectPolicy.MAX_PAIR_HELPER_RECOVERY_ATTEMPTS);
        assertEquals(0,BootReconnectPolicy.delayBeforePairHelperRecovery(0));
        assertEquals(2_500,BootReconnectPolicy.delayBeforePairHelperRecovery(1));
    }
    @Test public void automaticBootFailureDoesNotPublishAnAlertNotification(){
        assertFalse(BootReconnectPolicy.shouldPublishFailureNotification(true));
        assertTrue(BootReconnectPolicy.shouldPublishFailureNotification(false));
    }
}
