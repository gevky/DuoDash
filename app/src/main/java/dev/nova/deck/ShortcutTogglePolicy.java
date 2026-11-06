package dev.nova.deck;

/** Debounces duplicate shortcut notifications without delaying explicit toggles. */
final class ShortcutTogglePolicy {
    private static final long DEBOUNCE_MS = 250L;

    enum Decision { IGNORE_DUPLICATE, CLOSE, CONTINUE }

    private ShortcutTogglePolicy() {}

    static Decision decide(boolean shortcut, boolean panelShowing, boolean dialogShowing,
                           long now, long previous, boolean hasPrevious) {
        if (shortcut && hasPrevious && now >= previous && now - previous <= DEBOUNCE_MS) {
            return Decision.IGNORE_DUPLICATE;
        }
        if (panelShowing || dialogShowing) return Decision.CLOSE;
        return Decision.CONTINUE;
    }
}
