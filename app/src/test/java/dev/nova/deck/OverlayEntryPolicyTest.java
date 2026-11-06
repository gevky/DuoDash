package dev.nova.deck;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class OverlayEntryPolicyTest {
    @Test public void resizeRebindDoesNotReplacePendingUserEntry() {
        assertEquals(OverlayService.SHOW,OverlayEntryPolicy.merge(OverlayService.SHOW,"rebind"));
        assertEquals(OverlayService.TOGGLE,OverlayEntryPolicy.merge(OverlayService.TOGGLE,"background_rebind"));
        assertEquals("settings",OverlayEntryPolicy.merge("settings","rebind"));
    }

    @Test public void newerUserEntryMayReplaceInternalOrOlderEntry() {
        assertEquals(OverlayService.SHOW,OverlayEntryPolicy.merge("rebind",OverlayService.SHOW));
        assertEquals(OverlayService.TOGGLE,OverlayEntryPolicy.merge(OverlayService.SHOW,OverlayService.TOGGLE));
        assertEquals("rebind",OverlayEntryPolicy.merge("rebind","background_rebind"));
        assertEquals("rebind",OverlayEntryPolicy.merge("background_rebind","rebind"));
    }
}
