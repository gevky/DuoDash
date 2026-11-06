package dev.nova.deck;

import android.view.accessibility.AccessibilityEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemUiShadePolicyTest {
    @Test public void probesOnlySystemUiWindowStateEventsAndHandlesNullPackage() {
        assertTrue(SystemUiShadePolicy.shouldProbe("com.android.systemui", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertFalse(SystemUiShadePolicy.shouldProbe("com.android.systemui", AccessibilityEvent.TYPE_VIEW_CLICKED));
        assertFalse(SystemUiShadePolicy.shouldProbe("com.android.settings", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertFalse(SystemUiShadePolicy.shouldProbe(null, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
    }

    @Test public void acceptsOnlyFocusedShadeConfirmedOnTheRequestedDisplay() {
        assertTrue(SystemUiShadePolicy.confirmedOnDisplay(true, 0, 0));
        assertFalse(SystemUiShadePolicy.confirmedOnDisplay(false, 0, 0));
        assertFalse(SystemUiShadePolicy.confirmedOnDisplay(true, 4, 0));
        assertFalse(SystemUiShadePolicy.confirmedOnDisplay(true, 0, -1));
        assertFalse(SystemUiShadePolicy.matchesDisplay(4,0));
        assertFalse(SystemUiShadePolicy.matchesDisplay(0,-1));
    }

    @Test public void offlineFallbackIsLimitedToTheBuiltInDisplayShadeFrame(){
        assertTrue(SystemUiShadePolicy.mayYieldOffline(false,0,"android.widget.FrameLayout"));
        assertFalse(SystemUiShadePolicy.mayYieldOffline(true,0,"android.widget.FrameLayout"));
        assertFalse(SystemUiShadePolicy.mayYieldOffline(false,4,"android.widget.FrameLayout"));
        assertFalse(SystemUiShadePolicy.mayYieldOffline(false,0,"com.android.systemui.volume.VolumeDialogImpl$CustomDialog"));
    }
}
