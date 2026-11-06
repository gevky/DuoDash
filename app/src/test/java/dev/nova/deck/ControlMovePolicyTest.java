package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlMovePolicyTest {
    @Test public void dashboardEditAllowsReorderingWithinEveryVisibleGroup(){
        assertTrue(ControlMovePolicy.canDrop("wifi","bluetooth"));
        assertTrue(ControlMovePolicy.canDrop("main","upper"));
        assertTrue(ControlMovePolicy.canDrop("audio_main","volume"));
        assertTrue(ControlMovePolicy.canDrop("cpu","fps"));
        assertTrue(ControlMovePolicy.canDrop("app_0123456789abcdef01234567","apps"));
    }

    @Test public void dashboardEditRejectsCrossGroupAndUnknownDrops(){
        assertFalse(ControlMovePolicy.canDrop("wifi","main"));
        assertFalse(ControlMovePolicy.canDrop("volume","gpu"));
        assertFalse(ControlMovePolicy.canDrop("unknown","wifi"));
        assertFalse(ControlMovePolicy.canDrop(null,"wifi"));
    }
}
