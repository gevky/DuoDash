package dev.nova.deck;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DisplayModePolicyTest {
    @Test public void legacyExternalAppPreferenceMigratesWithoutStartingAnExternalHome() {
        assertEquals(DisplayModePolicy.DOCKED, DisplayModePolicy.migrateLegacyMode(true));
        assertEquals(DisplayModePolicy.DUAL, DisplayModePolicy.migrateLegacyMode(false));
        assertEquals(DisplayModePolicy.DUAL, DisplayModePolicy.normalizeMode("unexpected"));
    }

    @Test public void dockedAppsRemainOnDisplayZeroWhileDualModeHonorsSavedTarget() {
        assertEquals(0, DisplayModePolicy.effectiveAppDisplay(true, true, 17));
        assertEquals(17, DisplayModePolicy.effectiveAppDisplay(false, true, 17));
        assertEquals(0, DisplayModePolicy.effectiveAppDisplay(false, false, 17));
        assertEquals(0, DisplayModePolicy.effectiveAppDisplay(false, true, -1));
    }

    @Test public void panelDisplayAndMirrorVisibilityFollowModeAndPhysicalTarget() {
        // Docked external controls join the mirrored default-display session.
        assertEquals(0, DisplayModePolicy.panelWindowDisplay(true, true, true, 17));
        assertFalse(DisplayModePolicy.excludePanelFromMirroring(true, true, true));
        assertTrue(DisplayModePolicy.panelTargetsExternal(true, true));

        // Docked handheld controls remain local and excluded from captures/mirroring.
        assertEquals(0, DisplayModePolicy.panelWindowDisplay(true, false, true, 17));
        assertTrue(DisplayModePolicy.excludePanelFromMirroring(true, false, true));
        assertFalse(DisplayModePolicy.panelTargetsExternal(false, true));

        // Dual external controls keep their separate display and must remain visible there.
        assertEquals(17, DisplayModePolicy.panelWindowDisplay(false, true, true, 17));
        assertFalse(DisplayModePolicy.excludePanelFromMirroring(false, true, true));
        assertTrue(DisplayModePolicy.excludePanelFromMirroring(false, false, true));
        assertEquals(0, DisplayModePolicy.panelWindowDisplay(false, true, false, 17));
    }

    @Test public void mirroredCanvasOffersTvSizesAndAnExplicitFourByThreeCanvas() {
        List<DisplayModePolicy.Canvas> choices = DisplayModePolicy.mirroredCanvases(1280, 960);
        assertEquals(3, choices.size());
        assertCanvas("tv_1080p", 1920, 1080, choices.get(0));
        assertCanvas("tv_720p", 1280, 720, choices.get(1));
        assertCanvas("tv_4_3", 1440, 1080, choices.get(2));
        assertEquals("tv_1080p", DisplayModePolicy.defaultCanvas(3840,2160));
        assertEquals("tv_4_3", DisplayModePolicy.defaultCanvas(1920,1440));
        assertNull(DisplayModePolicy.mirroredCanvas("bad-input", 1280, 960));
    }

    @Test public void mirrorOnlyAppliesWhileTheMatchingExternalDisplayIsConnected() {
        assertTrue(DisplayModePolicy.shouldUseMirroredPrimary(true, true));
        assertFalse(DisplayModePolicy.shouldUseMirroredPrimary(true, false));
        assertFalse(DisplayModePolicy.shouldUseMirroredPrimary(false, true));
    }

    private static void assertCanvas(String id, int width, int height, DisplayModePolicy.Canvas canvas) {
        assertEquals(id, canvas.id);
        assertEquals(width, canvas.width);
        assertEquals(height, canvas.height);
    }
}
