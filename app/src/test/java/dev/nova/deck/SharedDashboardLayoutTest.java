package dev.nova.deck;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class SharedDashboardLayoutTest {
    private Map<String,String> layout(String order) {
        Map<String,String> values=new LinkedHashMap<>();
        values.put(SharedDashboardLayout.ORDER,order);
        values.put(SharedDashboardLayout.HIDDEN,"fan");
        values.put(SharedDashboardLayout.WIDE,"swap");
        values.put(SharedDashboardLayout.ACTION_COLUMNS,"3");
        values.put(SharedDashboardLayout.HORIZONTAL,"false");
        values.put(SharedDashboardLayout.COMPACT,"true");
        values.put(SharedDashboardLayout.FPS_SCREEN,"upper");
        values.put(SharedDashboardLayout.PINNED,"[portable-apps]");
        values.put(SharedDashboardLayout.LAYOUT_VERSION,"6");
        return values;
    }

    @Test public void migrationCopiesOnlyAnExplicitSourceAndPreservesEveryOutputRecord() {
        Map<String,String> store=new LinkedHashMap<>();
        store.put("handheld:order","handheld-order");
        store.put("output:tv:order","tv-order");
        store.put("output:tv:canvas:local_tv","ratio_16_9");
        store.put("output:tv:main","63");
        Map<String,String> before=new LinkedHashMap<>(store);

        SharedDashboardLayout.MigrationPlan plan=SharedDashboardLayout.planMigration(
            store,"output:tv",layout("tv-order,apps"));
        assertFalse(plan.alreadyInitialized);
        store.putAll(plan.writes);

        assertEquals("tv-order,apps",store.get("sharedDashboard:order"));
        assertEquals("output:tv",store.get(SharedDashboardLayout.SOURCE_KEY));
        assertEquals("handheld-order",store.get("handheld:order"));
        assertEquals("tv-order",store.get("output:tv:order"));
        assertEquals("ratio_16_9",store.get("output:tv:canvas:local_tv"));
        assertEquals("63",store.get("output:tv:main"));
        for(Map.Entry<String,String> entry:before.entrySet())assertEquals(entry.getValue(),store.get(entry.getKey()));
    }

    @Test public void migrationIsCopyOnceAndLayoutEditsResolveToOneKeyAcrossOutputs() {
        Map<String,String> store=new LinkedHashMap<>();
        store.put("handheld:order","handheld-order");
        SharedDashboardLayout.MigrationPlan first=SharedDashboardLayout.planMigration(
            store,"handheld",layout("handheld-order"));
        store.putAll(first.writes);
        String mainKey=SharedDashboardLayout.storageKey("handheld",SharedDashboardLayout.ORDER,true);
        String tvKey=SharedDashboardLayout.storageKey("output:tv",SharedDashboardLayout.ORDER,true);
        assertEquals(mainKey,tvKey);
        store.put(tvKey,"edited-on-tv");
        assertEquals("edited-on-tv",store.get(mainKey));

        SharedDashboardLayout.MigrationPlan second=SharedDashboardLayout.planMigration(
            store,"output:tv",layout("stale-tv-order"));
        assertTrue(second.alreadyInitialized);
        assertEquals("edited-on-tv",store.get(SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.ORDER)));
        assertEquals("handheld-order",store.get("handheld:order"));
    }

    @Test public void absentMigrationKeepsLegacyReadsSeparatedAndDoesNotGuessSource() {
        Map<String,String> store=new LinkedHashMap<>();
        assertFalse(SharedDashboardLayout.initialized(store));
        assertNotEquals(
            SharedDashboardLayout.storageKey("handheld",SharedDashboardLayout.ORDER,false),
            SharedDashboardLayout.storageKey("output:tv",SharedDashboardLayout.ORDER,false));
        try {
            SharedDashboardLayout.planMigration(store,"",layout(""));
            fail("An implicit or empty source must be rejected");
        } catch(IllegalArgumentException expected) {
            assertFalse(SharedDashboardLayout.initialized(store));
        }
    }

    @Test public void choosesOneMoreCustomizedLegacyProfileFromMatchingStandardProfiles() {
        Map<String,Map<String,String>> profiles=new LinkedHashMap<>();
        Map<String,String> standard=layout(ControlCatalog.defaultOrder());
        standard.put(SharedDashboardLayout.HIDDEN,ControlCatalog.defaultHidden());
        standard.put(SharedDashboardLayout.WIDE,"");
        standard.put(SharedDashboardLayout.ACTION_COLUMNS,"4");
        standard.put(SharedDashboardLayout.HORIZONTAL,"true");
        standard.put(SharedDashboardLayout.COMPACT,"true");
        standard.put(SharedDashboardLayout.PINNED,"[]");
        profiles.put("handheld",new LinkedHashMap<>(standard));
        profiles.put("output:old-tv",new LinkedHashMap<>(standard));

        Map<String,String> custom=new LinkedHashMap<>(standard);
        custom.put(SharedDashboardLayout.ORDER,"both,upper,main,audio_upper,audio_main,volume,wifi,dark,swap");
        custom.put(SharedDashboardLayout.ACTION_COLUMNS,"3");
        custom.put(SharedDashboardLayout.PINNED,"[SmartTube,PixelGuide]");
        custom.put(SharedDashboardLayout.FPS_SCREEN,"upper");
        profiles.put("output:active-tv",custom);

        assertEquals("output:active-tv",SharedDashboardLayout.selectUniqueCustomProfile(profiles));
    }

    @Test public void conflictingLegacyLayoutsRemainUnmigrated() {
        Map<String,Map<String,String>> profiles=new LinkedHashMap<>();
        Map<String,String> standard=layout(ControlCatalog.defaultOrder());
        standard.put(SharedDashboardLayout.HIDDEN,ControlCatalog.defaultHidden());
        standard.put(SharedDashboardLayout.WIDE,"");
        standard.put(SharedDashboardLayout.ACTION_COLUMNS,"4");
        standard.put(SharedDashboardLayout.HORIZONTAL,"true");
        standard.put(SharedDashboardLayout.COMPACT,"true");
        standard.put(SharedDashboardLayout.PINNED,"[]");
        profiles.put("handheld",new LinkedHashMap<>(standard));
        Map<String,String> customA=new LinkedHashMap<>(standard);
        customA.put(SharedDashboardLayout.ACTION_COLUMNS,"3");
        profiles.put("output:a",customA);
        Map<String,String> customB=new LinkedHashMap<>(standard);
        customB.put(SharedDashboardLayout.WIDE,"swap");
        profiles.put("output:b",customB);

        assertNull(SharedDashboardLayout.selectUniqueCustomProfile(profiles));

        profiles.remove("output:b");
        assertNull(SharedDashboardLayout.selectUniqueCustomProfile(profiles));
    }

    @Test public void portableDefaultsNeverReplaceUnmigratedLegacyLayouts() {
        assertTrue(SharedDashboardLayout.shouldSeedPortableDefaults(false,false));
        assertFalse(SharedDashboardLayout.shouldSeedPortableDefaults(true,false));
        assertFalse(SharedDashboardLayout.shouldSeedPortableDefaults(false,true));
    }
    @Test public void migrationKeepsOptionalSmallSizesWithoutRequiringThemFromOldLayouts(){
        Map<String,String> source=layout("wifi,bluetooth");source.put(SharedDashboardLayout.SMALL,"wifi");
        SharedDashboardLayout.MigrationPlan plan=SharedDashboardLayout.planMigration(new LinkedHashMap<>(),"handheld",source);
        assertEquals("wifi",plan.writes.get("sharedDashboard:small"));
        source.remove(SharedDashboardLayout.SMALL);
        assertFalse(SharedDashboardLayout.planMigration(new LinkedHashMap<>(),"handheld",source).writes.containsKey("sharedDashboard:small"));
    }

    @Test public void migrationCopiesOptionalExplicitSizesOnlyWhenPresent(){
        Map<String,String> source=layout("wifi,bluetooth");source.put(SharedDashboardLayout.SIZES,"wifi:2,bluetooth:6");
        SharedDashboardLayout.MigrationPlan plan=SharedDashboardLayout.planMigration(new LinkedHashMap<>(),"handheld",source);
        assertEquals("wifi:2,bluetooth:6",plan.writes.get("sharedDashboard:sizes"));
        source.remove(SharedDashboardLayout.SIZES);
        assertFalse(SharedDashboardLayout.planMigration(new LinkedHashMap<>(),"handheld",source).writes.containsKey("sharedDashboard:sizes"));
    }
}
