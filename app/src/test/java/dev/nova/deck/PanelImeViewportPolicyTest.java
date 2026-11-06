package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelImeViewportPolicyTest {
    @Test public void hidesTheOverlayWhileImeIsVisibleAndRestoresItAfterward() {
        assertEquals(0f, PanelImeViewportPolicy.panelAlpha(true), 0f);
        assertEquals(1f, PanelImeViewportPolicy.panelAlpha(false), 0f);
    }

    @Test public void hidesFloatingHandleWhileDashboardShadeOrKeyboardOwnsItsArea() {
        assertTrue(PanelImeViewportPolicy.showHandle(true, false, false, false));
        assertFalse(PanelImeViewportPolicy.showHandle(false, false, false, false));
        assertFalse(PanelImeViewportPolicy.showHandle(true, true, false, false));
        assertFalse(PanelImeViewportPolicy.showHandle(true, false, true, false));
        assertFalse(PanelImeViewportPolicy.showHandle(true, false, false, true));
    }
}
