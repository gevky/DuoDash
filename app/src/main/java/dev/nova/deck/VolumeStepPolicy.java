package dev.nova.deck;

/** Maps a 0..100 dashboard value to Android's supported stream-volume steps. */
public final class VolumeStepPolicy {
    private VolumeStepPolicy() { }

    public static int indexForPercent(int percent, int minimum, int maximum) {
        int min = Math.max(0, minimum);
        int max = Math.max(min, maximum);
        if (max == min) return min;
        int clamped = Math.max(0, Math.min(100, percent));
        return min + Math.round(clamped * (max - min) / 100f);
    }

    public static int percentForIndex(int index, int minimum, int maximum) {
        int min = Math.max(0, minimum);
        int max = Math.max(min, maximum);
        if (max == min) return 0;
        int clamped = Math.max(min, Math.min(max, index));
        return Math.round((clamped - min) * 100f / (max - min));
    }

    public static int representedPercent(int requestedPercent, int minimum, int maximum) {
        return percentForIndex(indexForPercent(requestedPercent, minimum, maximum), minimum, maximum);
    }
}
