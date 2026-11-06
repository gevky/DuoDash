package dev.nova.deck;

import android.view.accessibility.AccessibilityEvent;

/** Guards shade probes and binds a confirmation to the display that issued it. */
final class SystemUiShadePolicy {
    private SystemUiShadePolicy() { }

    static boolean shouldProbe(CharSequence packageName, int eventType) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && "com.android.systemui".equals(String.valueOf(packageName));
    }

    static boolean confirmedOnDisplay(boolean focusedShade, int reportedDisplay, int requestedDisplay) {
        return focusedShade && matchesDisplay(reportedDisplay,requestedDisplay);
    }

    static boolean matchesDisplay(int reportedDisplay,int requestedDisplay) {
        return requestedDisplay >= 0 && reportedDisplay == requestedDisplay;
    }

    static boolean mayYieldOffline(boolean helperReady,int panelDisplayId,CharSequence eventClass) {
        return !helperReady&&panelDisplayId==0&&"android.widget.FrameLayout".contentEquals(String.valueOf(eventClass));
    }
}
