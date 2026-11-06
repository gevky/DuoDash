package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStatusPolicyTest {
    @Test public void successCopyClearsOnlyAfterLiveHelperStatusArrives(){
        assertEquals(ConnectionStatusPolicy.RESTORE_VERIFYING,
                ConnectionStatusPolicy.afterBridgeStatus(ConnectionStatusPolicy.RESTORE_VERIFYING,false));
        assertEquals("",ConnectionStatusPolicy.afterBridgeStatus(ConnectionStatusPolicy.RESTORE_VERIFYING,true));
    }

    @Test public void failuresAreNeverClearedByBridgeStatus(){
        assertEquals("Could not restore controls",ConnectionStatusPolicy.afterBridgeStatus("Could not restore controls",false));
        assertEquals("Could not restore controls",ConnectionStatusPolicy.afterBridgeStatus("Could not restore controls",true));
    }
}
