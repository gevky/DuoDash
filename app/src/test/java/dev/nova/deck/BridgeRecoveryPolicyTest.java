package dev.nova.deck;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class BridgeRecoveryPolicyTest {
    @Test public void ownedDebuggingIsRestoredAfterStartingAndDisconnecting() throws Exception {
        FakeOperations operations=new FakeOperations();
        assertEquals("System controls connected.",BridgeRecoveryPolicy.runWithTemporaryDebugging(0,true,true,operations));
        assertEquals(0,operations.debuggingState);
        assertEquals(Arrays.asList("enable","start","disconnect","restore:0","read","helper"),operations.calls);
    }

    @Test public void existingDebuggingStaysOnWithoutAnySettingWrite() throws Exception {
        FakeOperations operations=new FakeOperations();
        operations.debuggingState=1;
        assertEquals("System controls connected.",BridgeRecoveryPolicy.runWithTemporaryDebugging(1,false,false,operations));
        assertEquals(1,operations.debuggingState);
        assertEquals(Arrays.asList("start","disconnect","helper"),operations.calls);
    }

    @Test public void failedStartupStillDisconnectsAndRestoresOwnedDebugging() throws Exception {
        FakeOperations operations=new FakeOperations();
        operations.startFailure=new IllegalStateException("startup failed");
        try {
            BridgeRecoveryPolicy.runWithTemporaryDebugging(0,true,true,operations);
            fail("Expected startup failure");
        } catch(IllegalStateException expected) {
            assertEquals("startup failed",expected.getMessage());
        }
        assertEquals(0,operations.debuggingState);
        assertEquals(Arrays.asList("enable","start","disconnect","restore:0","read","helper"),operations.calls);
    }

    @Test public void interruptedStartupRunsTheSameCleanupBeforePropagating() throws Exception {
        FakeOperations operations=new FakeOperations();
        operations.startFailure=new InterruptedException("cancelled");
        try {
            BridgeRecoveryPolicy.runWithTemporaryDebugging(0,true,true,operations);
            fail("Expected cancellation");
        } catch(InterruptedException expected) {
            assertEquals("cancelled",expected.getMessage());
        }
        assertEquals(0,operations.debuggingState);
        assertEquals(Arrays.asList("enable","start","disconnect","restore:0","read","helper"),operations.calls);
    }

    @Test public void cleanupFailureNeverReturnsConnected() throws Exception {
        FakeOperations operations=new FakeOperations();
        operations.restoreFailure=new IllegalStateException("restore failed");
        try {
            BridgeRecoveryPolicy.runWithTemporaryDebugging(0,true,true,operations);
            fail("Expected cleanup failure");
        } catch(IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("could not restore"));
        }
        assertEquals(Arrays.asList("enable","start","disconnect","restore:0"),operations.calls);
    }

    @Test public void missingPermissionOrLocalNetworkDoesNotMutateAnything() {
        for(boolean granted:new boolean[]{false,true}) {
            FakeOperations operations=new FakeOperations();
            try {
                BridgeRecoveryPolicy.runWithTemporaryDebugging(0,granted,false,operations);
                fail("Expected preflight failure");
            } catch(IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Wi-Fi"));
            } catch(SecurityException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("pair"));
            } catch(Exception unexpected) {
                fail(unexpected.toString());
            }
            assertTrue(operations.calls.isEmpty());
        }
    }

    @Test public void helperMustSurviveClosingAnAlreadyEnabledAdbSession() throws Exception {
        FakeOperations operations=new FakeOperations();
        operations.debuggingState=1;
        operations.healthy=false;
        assertThrows(IllegalStateException.class,()->BridgeRecoveryPolicy.runWithTemporaryDebugging(1,true,true,operations));
        assertEquals(Arrays.asList("start","disconnect","helper"),operations.calls);
        assertEquals(1,operations.debuggingState);
    }

    private static final class FakeOperations implements BridgeRecoveryPolicy.RecoveryOperations {
        final List<String> calls=new ArrayList<>();
        int debuggingState;
        boolean healthy=true;
        Exception startFailure,restoreFailure;
        @Override public void enableWirelessDebugging(){calls.add("enable");debuggingState=1;}
        @Override public String connectAndStart() throws Exception{calls.add("start");if(startFailure!=null)throw startFailure;return "System controls connected.";}
        @Override public void disconnectAdb(){calls.add("disconnect");}
        @Override public void restoreWirelessDebugging(int value) throws Exception{calls.add("restore:"+value);if(restoreFailure!=null)throw restoreFailure;debuggingState=value;}
        @Override public int readWirelessDebugging(){calls.add("read");return debuggingState;}
        @Override public boolean helperHealthy(){calls.add("helper");return healthy;}
    }
}
