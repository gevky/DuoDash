package dev.nova.deck;

/** Timing policy for the screenshot tile's tap and hold gesture. */
final class ScreenshotHoldPolicy {
    static final long TAP_CYCLE_LIMIT_MS = 500L;
    static final long HOLD_RING_DELAY_MS = 500L;
    static final long CAPTURE_HOLD_MS = 1500L;

    enum Outcome { NONE, CYCLE, CAPTURE }

    private long startedAt = -1L;
    private boolean captureIssued;

    static boolean canBegin(boolean screenshotTile, boolean editing) {
        return screenshotTile && !editing;
    }

    void begin(long uptimeMillis) {
        startedAt = uptimeMillis;
        captureIssued = false;
    }

    boolean isActive() {
        return startedAt >= 0L;
    }

    boolean hasCaptured() {
        return captureIssued;
    }

    float progress(long uptimeMillis) {
        if (!isActive() || captureIssued) return 0f;
        long holdElapsed = uptimeMillis - startedAt - HOLD_RING_DELAY_MS;
        long holdDuration = CAPTURE_HOLD_MS - HOLD_RING_DELAY_MS;
        return Math.max(0f, Math.min(1f, holdElapsed / (float) holdDuration));
    }

    Outcome update(long uptimeMillis) {
        if (!isActive() || captureIssued || uptimeMillis - startedAt < CAPTURE_HOLD_MS) return Outcome.NONE;
        captureIssued = true;
        return Outcome.CAPTURE;
    }

    Outcome release(long uptimeMillis) {
        if (!isActive()) return Outcome.NONE;
        Outcome update = update(uptimeMillis);
        Outcome result = update == Outcome.CAPTURE
                ? Outcome.CAPTURE
                : captureIssued ? Outcome.NONE
                : uptimeMillis - startedAt <= TAP_CYCLE_LIMIT_MS ? Outcome.CYCLE : Outcome.NONE;
        reset();
        return result;
    }

    void cancel() {
        reset();
    }

    private void reset() {
        startedAt = -1L;
        captureIssued = false;
    }
}
