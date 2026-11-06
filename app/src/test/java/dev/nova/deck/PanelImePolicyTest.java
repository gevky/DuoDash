package dev.nova.deck;

import android.view.WindowManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class PanelImePolicyTest {
    @Test public void handheldPanelKeepsUnderlyingImeTargetWithoutTakingKeyFocus() {
        int flags = PanelImePolicy.flags(false);
        assertTrue((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, flags);
        assertEquals(0, flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    @Test public void settingsAndExternalControllerPanelRemainImeFocusable() {
        int flags = PanelImePolicy.flags(true);
        assertEquals(0, flags);
    }
}
