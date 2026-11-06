package dev.nova.deck;

import static org.junit.Assert.*;
import org.junit.Test;

public class ShortcutTogglePolicyTest {
    @Test public void firstShortcutAtUptimeZeroIsAccepted() {
        assertEquals(ShortcutTogglePolicy.Decision.CONTINUE,
            ShortcutTogglePolicy.decide(true,false,false,0L,0L,false));
    }

    @Test public void duplicateShortcutIsIgnoredEvenWhenPanelIsAlreadyOpen() {
        // A first shortcut can finish its asynchronous open before a duplicate
        // native/accessibility event reaches OverlayService.
        assertEquals(ShortcutTogglePolicy.Decision.IGNORE_DUPLICATE,
            ShortcutTogglePolicy.decide(true,true,false,120L,30L,true));
    }

    @Test public void shortcutAfterWindowIsAcceptedAndOrdinaryToggleIsNeverDebounced() {
        assertEquals(ShortcutTogglePolicy.Decision.IGNORE_DUPLICATE,
            ShortcutTogglePolicy.decide(true,true,false,280L,30L,true));
        assertEquals(ShortcutTogglePolicy.Decision.CLOSE,
            ShortcutTogglePolicy.decide(true,true,false,281L,30L,true));
        assertEquals(ShortcutTogglePolicy.Decision.CLOSE,
            ShortcutTogglePolicy.decide(false,true,false,40L,30L,true));
        assertEquals(ShortcutTogglePolicy.Decision.CONTINUE,
            ShortcutTogglePolicy.decide(false,false,false,40L,30L,true));
    }
}
