package dev.nova.deck.bridge;

import java.util.ArrayList;
import java.util.List;

/** Chooses the foreground app's presentation surface, including apps that render in their window layer. */
final class SurfaceLayerSelector {
    private SurfaceLayerSelector() {}

    static String select(String packageName, List<String> layers) {
        if (packageName == null || packageName.isEmpty() || layers == null) return null;
        List<String> blast = new ArrayList<>();
        List<String> surfaceViews = new ArrayList<>();
        List<String> activityWindows = new ArrayList<>();
        String surfacePrefix = "SurfaceView[" + packageName + "/";
        String activityPrefix = packageName + "/";
        for (String raw : layers) {
            if (raw == null) continue;
            String layer = raw.trim();
            if (layer.startsWith(surfacePrefix) && hasLayerId(layer)) {
                surfaceViews.add(layer);
                if (layer.contains("(BLAST)")) blast.add(layer);
            } else if (layer.startsWith(activityPrefix) && hasLayerId(layer)) {
                activityWindows.add(layer);
            }
        }
        if (!blast.isEmpty()) return newest(blast);
        if (!surfaceViews.isEmpty()) return newest(surfaceViews);
        return activityWindows.isEmpty() ? null : newest(activityWindows);
    }

    private static boolean hasLayerId(String layer) {
        int marker = layer.lastIndexOf('#');
        if (marker < 0 || marker == layer.length() - 1) return false;
        for (int i = marker + 1; i < layer.length(); i++) {
            if (!Character.isDigit(layer.charAt(i))) return false;
        }
        return true;
    }

    private static String newest(List<String> layers) {
        String selected = null;
        long selectedId = Long.MIN_VALUE;
        for (String layer : layers) {
            try {
                long id = Long.parseLong(layer.substring(layer.lastIndexOf('#') + 1));
                if (id > selectedId) {
                    selected = layer;
                    selectedId = id;
                }
            } catch (NumberFormatException ignored) { }
        }
        return selected;
    }
}
