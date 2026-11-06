package dev.nova.deck;

import static org.junit.Assert.*;
import org.junit.Test;

public class HiddenSurfaceTouchPolicyTest {
    @Test public void externalTouchCannotActivateASurfaceHiddenFromConnectedOutput() {
        assertTrue(HiddenSurfaceTouchPolicy.shouldConsume(true,true,false));
    }

    @Test public void visibleExternalSurfaceAndBuiltinTouchRemainUsable() {
        assertFalse(HiddenSurfaceTouchPolicy.shouldConsume(true,true,true));
        assertFalse(HiddenSurfaceTouchPolicy.shouldConsume(false,true,false));
    }

    @Test public void onlyExternalTouchscreenSourcesAreGuarded() {
        assertTrue(HiddenSurfaceTouchPolicy.isExternalTouchscreen(true,true));
        assertFalse(HiddenSurfaceTouchPolicy.isExternalTouchscreen(false,true));
        assertFalse(HiddenSurfaceTouchPolicy.isExternalTouchscreen(true,false));
    }

    @Test public void detachedOutputDoesNotChangeInputHandling() {
        assertFalse(HiddenSurfaceTouchPolicy.shouldConsume(true,false,false));
    }

    @Test public void concurrentBuiltinTouchDoesNotReleaseBlockedExternalGesture() {
        HiddenSurfaceTouchPolicy.Gesture guard=new HiddenSurfaceTouchPolicy.Gesture();
        assertTrue(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.DOWN,true,true,false));
        assertFalse(guard.shouldConsume(2,HiddenSurfaceTouchPolicy.Phase.DOWN,false,true,false));
        // A mode/connection change or an internal device's DOWN cannot release it.
        assertTrue(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.CONTINUE,false,false,true));
        assertTrue(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.UP,true,true,false));
        assertFalse(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.DOWN,true,true,true));
    }

    @Test public void cancelEndsOnlyTheMatchingDeviceGesture() {
        HiddenSurfaceTouchPolicy.Gesture guard=new HiddenSurfaceTouchPolicy.Gesture();
        assertTrue(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.DOWN,true,true,false));
        assertTrue(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.CANCEL,true,true,false));
        assertFalse(guard.shouldConsume(34,HiddenSurfaceTouchPolicy.Phase.DOWN,true,true,true));
    }
}
