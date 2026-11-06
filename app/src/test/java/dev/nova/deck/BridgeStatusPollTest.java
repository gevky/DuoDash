package dev.nova.deck;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class BridgeStatusPollTest {
    @Test public void successfulStatusNeedsNoFallback() {
        BridgeStatusPoll.Result<String> result=BridgeStatusPoll.read(()->"status",()->{throw new AssertionError("Unnecessary probe");});
        assertEquals("status",result.status);
        assertTrue(result.helperReady);
        assertNull(result.failure);
    }
    @Test public void failedTelemetryDoesNotDisconnectLiveHelper() {
        IOException failure=new IOException("Display information unavailable");
        BridgeStatusPoll.Result<String> result=BridgeStatusPoll.read(()->{throw failure;},()->true);
        assertNull(result.status);
        assertTrue(result.helperReady);
        assertSame(failure,result.failure);
    }
    @Test public void deadOrOutdatedHelperIsDisconnected() {
        BridgeStatusPoll.Result<String> result=BridgeStatusPoll.read(()->{throw new IOException("status failed");},()->false);
        assertFalse(result.helperReady);
    }
    @Test public void failedProbeDoesNotHideOriginalStatusFailure() {
        IOException statusFailure=new IOException("status failed");
        BridgeStatusPoll.Result<String> result=BridgeStatusPoll.read(()->{throw statusFailure;},()->{throw new IOException("socket closed");});
        assertFalse(result.helperReady);
        assertSame(statusFailure,result.failure);
    }
}
