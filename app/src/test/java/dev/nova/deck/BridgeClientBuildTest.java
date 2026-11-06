package dev.nova.deck;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class BridgeClientBuildTest {
    @Test public void acceptsOnlyTheExpectedHelperBuildAndSwapStrategy(){
        assertTrue(BridgeClient.isCurrentHelper(BridgeClient.EXPECTED_BRIDGE_BUILD,BridgeClient.EXPECTED_SWAP_STRATEGY));
        assertFalse(BridgeClient.isCurrentHelper(19,BridgeClient.EXPECTED_SWAP_STRATEGY));
        assertFalse(BridgeClient.isCurrentHelper(BridgeClient.EXPECTED_BRIDGE_BUILD,"sequential"));
        assertFalse(BridgeClient.isCurrentHelper(-1,""));
    }
}
