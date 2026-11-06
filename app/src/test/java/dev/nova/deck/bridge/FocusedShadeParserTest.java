package dev.nova.deck.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class FocusedShadeParserTest {
    private static final String OPEN = """
        WINDOW MANAGER DISPLAY CONTENTS
          Display: mDisplayId=32 (organized)
            mCurrentFocus=Window{abc123 u0 dev.nova.deck/dev.nova.deck.MirrorActivity}
            displayId=32
          Display: mDisplayId=0
            mCurrentFocus=Window{fed456 u0 NotificationShade}
            displayId=0
        """;

    @Test public void matchesOnlyFocusedShadeOnRequestedDisplay() {
        assertTrue(FocusedShadeParser.isNotificationShadeFocused(OPEN, 0));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused(OPEN, 32));
    }

    @Test public void closedShadeAndVolumeHudDoNotMatch() {
        assertFalse(FocusedShadeParser.isNotificationShadeFocused("""
            Display: mDisplayId=0
              mCurrentFocus=Window{abc123 u0 com.android.settings/.Settings}
            """, 0));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused("""
            Display: mDisplayId=0
              mCurrentFocus=Window{abc123 u0 com.android.systemui.volume.VolumeDialogImpl$CustomDialog}
            """, 0));
    }

    @Test public void exactTitleOnlyAndMalformedOrAmbiguousDumpsFailClosed() {
        assertFalse(FocusedShadeParser.isNotificationShadeFocused("""
            Display: mDisplayId=0
              mCurrentFocus=Window{abc123 u0 MyNotificationShade}
            """, 0));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused("Display: mDisplayId=0\n mCurrentFocus=Window{abc123 u0 NotificationShade", 0));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused(OPEN + "\nDisplay: mDisplayId=0\n mCurrentFocus=Window{987 u0 NotificationShade}\n", 0));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused(OPEN, -1));
        assertFalse(FocusedShadeParser.isNotificationShadeFocused(null, 0));
    }
}
