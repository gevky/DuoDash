package dev.nova.deck;
import android.content.Context;
import android.graphics.Typeface;

final class ControlStyle {
    private ControlStyle() {}
    static final float REMOVE_BADGE_TARGET_DP = 12f, REMOVE_BADGE_RADIUS_DP = 8f;
    static final int BACKGROUND = 0xff101012, SURFACE = 0xff1c1c20, ELEVATED = 0xff27262b;
    static final int TEXT = 0xfff0edf3, MUTED = 0xffaaa6b0, AQUA = 0xffd2c6df, VIOLET = 0xffc9b6d8,
                     BLUE = 0xffd2cedc;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    static final int BOTH_FILL = 0xffded5e7, SLIDER_FILL = 0xffcebfdb, MASTER_FILL = 0xffdedbe2;
    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static String label(String value, String fallback) {
        String text = value == null || value.trim().isEmpty() ? fallback : value;
        return text.replaceAll("(?i)\\bupper (screen|display|app|audio)\\b", "external $1");
    }
}
