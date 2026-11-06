package dev.nova.deck.bridge;

/** Small policy for preserving Nova's native joystick LED brightness across the boolean toggle. */
final class LedBrightnessPolicy {
    private LedBrightnessPolicy() { }

    static boolean isUsableBrightness(String value) {
        if (value == null) return false;
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) && parsed > 0d && parsed <= 1d;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Null means the live key is already off/invalid, so keep the previous restore value. */
    static String brightnessToCaptureWhenTurningOff(String current) {
        return isUsableBrightness(current) ? current.trim() : null;
    }

    /** Restore an existing native level, or the legacy full-brightness default. */
    static String valueToRestore(String current, String saved) {
        if (isUsableBrightness(current)) return current.trim();
        return isUsableBrightness(saved) ? saved.trim() : "1.0";
    }
}
