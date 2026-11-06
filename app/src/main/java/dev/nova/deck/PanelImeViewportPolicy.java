package dev.nova.deck;

/** Keeps an IME-covered overlay transparent and non-touchable without resizing its window. */
final class PanelImeViewportPolicy {
    private PanelImeViewportPolicy() { }

    static float panelAlpha(boolean imeVisible) {
        return imeVisible ? 0f : 1f;
    }

    static boolean showHandle(boolean enabled, boolean panelOpen, boolean shadeOpen, boolean imeVisible) {
        return enabled && !panelOpen && !shadeOpen && !imeVisible;
    }
}
