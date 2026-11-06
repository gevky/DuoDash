package dev.nova.deck;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Per-output display behavior and safe logical sizes for a mirrored TV session. */
public final class DisplayModePolicy {
    public static final String DUAL = "dual";
    public static final String DOCKED = "docked";

    private DisplayModePolicy() {}

    public static String normalizeMode(String value) {
        return DOCKED.equals(value) ? DOCKED : DUAL;
    }

    public static String migrateLegacyMode(boolean useExternalApps) {
        return useExternalApps ? DOCKED : DUAL;
    }

    public static boolean isDocked(String value) {
        return DOCKED.equals(value);
    }

    public static int effectiveAppDisplay(boolean docked, boolean savedExternal, int externalDisplay) {
        return !docked && savedExternal && externalDisplay > 0 ? externalDisplay : 0;
    }

    /** The physical panel follows the selected output, while docked controls live in the mirrored main session. */
    public static int panelWindowDisplay(boolean docked, boolean savedExternal, boolean connected, int externalDisplay) {
        return !docked && savedExternal && connected && externalDisplay > 0 ? externalDisplay : 0;
    }

    public static boolean panelTargetsExternal(boolean savedExternal, boolean connected) {
        return savedExternal && connected;
    }

    /** A panel targeted at the external output must not be marked internal-display-only. */
    public static boolean excludePanelFromMirroring(boolean docked, boolean savedExternal, boolean connected) {
        return !(savedExternal && connected);
    }

    public static boolean shouldUseMirroredPrimary(boolean docked, boolean externalConnected) {
        return docked && externalConnected;
    }

    public static List<Canvas> mirroredCanvases(int nativeLogicalWidth, int nativeLogicalHeight) {
        Canvas fullHd = new Canvas("tv_1080p", "1080p · 16:9", 1920, 1080);
        Canvas hd = new Canvas("tv_720p", "720p · 16:9", 1280, 720);
        Canvas fourThree = new Canvas("tv_4_3", "4:3", 1440, 1080);
        return Collections.unmodifiableList(Arrays.asList(fullHd, hd, fourThree));
    }

    public static String defaultCanvas(int externalNativeWidth,int externalNativeHeight) {
        if(externalNativeWidth<=0||externalNativeHeight<=0)return "tv_1080p";
        long difference=Math.abs((long)externalNativeWidth*3-(long)externalNativeHeight*4);
        return difference<=4L?"tv_4_3":"tv_1080p";
    }

    public static Canvas mirroredCanvas(String optionId, int nativeLogicalWidth, int nativeLogicalHeight) {
        for (Canvas canvas : mirroredCanvases(nativeLogicalWidth, nativeLogicalHeight)) {
            if (canvas.id.equals(optionId)) return canvas;
        }
        return null;
    }

    public static final class Canvas {
        public final String id, label;
        public final int width, height;
        public Canvas(String id, String label, int width, int height) {
            this.id = id;
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }
}
