package dev.nova.deck;

import android.view.WindowManager;

/** Touch entry preserves the app's IME target; controller entry requests key focus. */
final class PanelImePolicy {
    private PanelImePolicy() { }

    static int flags(boolean needsKeyFocus) {
        return needsKeyFocus ? 0
                : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    }
}
