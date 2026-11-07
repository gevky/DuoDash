package dev.nova.deck;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PortableDefaultsTest {
    private static boolean hidden(String id){return Arrays.asList(PortableDefaults.HIDDEN.split(",")).contains(id);}
    @Test public void seedsOnlyAnEmptyFreshInstall() {
        assertTrue(PortableDefaults.isFreshInstall(100,100,false));
        assertFalse(PortableDefaults.isFreshInstall(100,200,false));
        assertFalse(PortableDefaults.isFreshInstall(100,100,true));
        assertFalse(PortableDefaults.isFreshInstall(0,0,false));
        assertTrue(PortableDefaults.shouldSeedProfile(true,false));
        assertFalse(PortableDefaults.shouldSeedProfile(true,true));
        assertFalse(PortableDefaults.shouldSeedProfile(false,false));
    }

    @Test public void freshTemplateUsesNineRequestedControlsAndLoadMetrics() {
        assertTrue(PortableDefaults.FULL_SCREEN);
        assertFalse(PortableDefaults.FLOATING_HANDLE);
        assertFalse(PortableDefaults.SHOW_REFRESH_RATE);
        assertTrue(PortableDefaults.SHOW_WIFI);
        assertTrue(PortableDefaults.SHOW_CLOCK);
        assertEquals(MetricModePolicy.USAGE,PortableDefaults.CPU_METRIC_MODE);
        assertEquals(MetricModePolicy.USAGE,PortableDefaults.GPU_METRIC_MODE);
        assertEquals(3,PortableDefaults.ACTION_COLUMNS);
        assertEquals(59,PortableDefaults.MAIN_BRIGHTNESS);
        assertEquals(59,PortableDefaults.EXTERNAL_BRIGHTNESS);
        assertEquals(100,PortableDefaults.MASTER_VOLUME);
        assertTrue(PortableDefaults.ORDER.contains("quick_settings,wifi,bluetooth,screenshot,apps,clear_recents,controller_style,night,dark"));
        assertEquals("wifi,bluetooth",PortableDefaults.SMALL_CONTROLS);
        assertTrue(PortableDefaults.apps().isEmpty());
        assertEquals(9,ControlLogic.actionCount(PortableDefaults.ORDER,PortableDefaults.HIDDEN));
        for(String id:new String[]{"quick_settings","wifi","bluetooth","screenshot","apps","clear_recents","controller_style","night","dark"})
            assertFalse(id+" should be a fresh default control",hidden(id));
        for(String id:new String[]{"swap","up","down","fan","performance","charging"})
            assertTrue(id+" should remain opt-in for fresh installs",hidden(id));
        assertFalse("The refresh-Hz monitor should be visible on fresh installs",hidden("refresh"));
        assertEquals("Settings",ControlCatalog.find("quick_settings").label);
        assertEquals("Open Android system settings",ControlCatalog.find("quick_settings").description);
        ControlCatalog.Entry quickPanel=ControlCatalog.find("quick_panel");
        assertNotNull(quickPanel);
        assertEquals("Quick settings",quickPanel.label);
        assertEquals("Open Android quick-settings panel",quickPanel.description);
        assertEquals("Tools",quickPanel.group);
        assertEquals("action",quickPanel.kind);
        assertFalse(quickPanel.defaultShown);
        assertTrue(ControlCatalog.defaultHidden().contains("quick_panel"));
        assertFalse(PortableDefaults.ORDER.contains("quick_panel"));
        assertTrue("Existing catalog defaults remain stable for saved-layout migrations",ControlCatalog.defaultHidden().contains("screenshot"));
        assertFalse(ControlCatalog.defaultHidden().contains("fan"));
    }
}
