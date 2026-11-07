package dev.nova.deck;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ProfileStoreDashboardLayoutTest {
    private static final class PreferencesFixture extends ContextWrapper {
        final Map<String,Object> values=new LinkedHashMap<>();
        private final SharedPreferences prefs;

        PreferencesFixture(){
            super(null);
            prefs=proxy(SharedPreferences.class,(object,method,args)->{
                String name=method.getName();
                if(name.equals("getAll"))return new LinkedHashMap<>(values);
                if(name.equals("contains"))return values.containsKey(args[0]);
                if(name.equals("getString"))return values.getOrDefault(args[0],args[1]);
                if(name.equals("getInt"))return values.getOrDefault(args[0],args[1]);
                if(name.equals("getBoolean"))return values.getOrDefault(args[0],args[1]);
                if(name.equals("edit"))return editor();
                if(name.equals("toString"))return "MapBackedPreferences";
                if(name.equals("hashCode"))return System.identityHashCode(object);
                if(name.equals("equals"))return object==args[0];
                throw new UnsupportedOperationException("Unexpected preferences call: "+name);
            });
        }

        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}

        private SharedPreferences.Editor editor(){
            Map<String,Object> pending=new LinkedHashMap<>();
            return proxy(SharedPreferences.Editor.class,(object,method,args)->{
                String name=method.getName();
                if(name.startsWith("put")&&args!=null&&args.length==2){pending.put((String)args[0],args[1]);return object;}
                if(name.equals("remove")){pending.put((String)args[0],Removed.VALUE);return object;}
                if(name.equals("clear")){pending.clear();pending.put("__clear__",Boolean.TRUE);return object;}
                if(name.equals("apply")||name.equals("commit")){
                    if(Boolean.TRUE.equals(pending.remove("__clear__")))values.clear();
                    for(Map.Entry<String,Object> item:pending.entrySet()){
                        if(item.getValue()==Removed.VALUE)values.remove(item.getKey());
                        else values.put(item.getKey(),item.getValue());
                    }
                    return name.equals("commit")?Boolean.TRUE:null;
                }
                if(name.equals("toString"))return "MapBackedEditor";
                if(name.equals("hashCode"))return System.identityHashCode(object);
                if(name.equals("equals"))return object==args[0];
                throw new UnsupportedOperationException("Unexpected editor call: "+name);
            });
        }
    }

    private enum Removed { VALUE }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type,InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }

    private static void putSharedLayout(PreferencesFixture fixture){
        Map<String,Object> values=fixture.values;
        values.put(SharedDashboardLayout.VERSION_KEY,SharedDashboardLayout.VERSION);
        values.put("sharedDashboard:layoutVersion",7);
        values.put("sharedDashboard:order","wifi,bluetooth,apps,volume,clear_recents");
        values.put("sharedDashboard:hidden","fan");
        values.put("sharedDashboard:wide","bluetooth");
        values.put("sharedDashboard:small","wifi");
        values.put("sharedDashboard:sizes","apps:6");
        values.put("sharedDashboard:actionColumns",2);
        values.put("sharedDashboard:horizontal",false);
        values.put("sharedDashboard:compact",false);
        values.put("sharedDashboard:fpsScreen","upper");
        values.put("sharedDashboard:pinned","[]");
    }

    private static void putLegacyLayout(PreferencesFixture fixture,String key,String order,String hidden){
        fixture.values.put(key+":layoutVersion",7);
        fixture.values.put(key+":order",order);
        fixture.values.put(key+":hidden",hidden);
        fixture.values.put(key+":wide","");
        fixture.values.put(key+":small","");
        fixture.values.put(key+":sizes","");
        fixture.values.put(key+":actionColumns",4);
        fixture.values.put(key+":horizontal",true);
        fixture.values.put(key+":compact",true);
        fixture.values.put(key+":fpsScreen","main");
        fixture.values.put(key+":pinned","[]");
    }

    private static void assertSavedLayout(UiState state,String expectedKey){
        assertEquals(expectedKey,state.layoutKey);
        assertEquals(ControlLogic.cleanOrder("wifi,bluetooth,apps,volume,clear_recents"),state.controlOrder);
        assertEquals("fan,quick_panel",state.hiddenControls);
        assertEquals("bluetooth",state.wideControls);
        assertEquals("wifi",state.smallControls);
        assertEquals("apps:6",state.controlSizes);
        assertEquals(2,state.actionColumns);
        assertFalse(state.horizontalSliders);
        assertFalse(state.compactControls);
        assertEquals("upper",state.fpsScreen);
        assertTrue(state.pinnedApps.isEmpty());
    }

    @Test public void offlineInitialLoadAndReconnectUseSavedSharedLayoutWithoutTouchingHardwareState(){
        PreferencesFixture fixture=new PreferencesFixture();
        putSharedLayout(fixture);
        ProfileStore store=new ProfileStore(fixture);

        UiState offline=new UiState();
        int main=offline.main,upper=offline.upper,volume=offline.volume;
        store.loadDashboardLayout("handheld",offline,"main");
        assertSavedLayout(offline,"handheld");
        assertEquals(main,offline.main);
        assertEquals(upper,offline.upper);
        assertEquals(volume,offline.volume);

        UiState reconnected=new UiState();
        store.loadDashboardLayout("output:tv-identity",reconnected,"main");
        assertSavedLayout(reconnected,"output:tv-identity");
        assertEquals(main,reconnected.main);
        assertEquals(upper,reconnected.upper);
        assertEquals(volume,reconnected.volume);
    }

    @Test public void freshOfflineLoadUsesCatalogLayoutDefaults(){
        PreferencesFixture fixture=new PreferencesFixture();
        ProfileStore store=new ProfileStore(fixture);
        store.prepareDashboardLayout();
        UiState state=new UiState();
        store.loadDashboardLayout("handheld",state,"main");
        assertEquals(ControlCatalog.defaultOrder(),state.controlOrder);
        assertEquals(ControlCatalog.defaultHidden(),state.hiddenControls);
        assertEquals("",state.wideControls);
        assertEquals("",state.smallControls);
        assertEquals("",state.controlSizes);
        assertEquals(4,state.actionColumns);
        assertTrue(state.horizontalSliders);
        assertTrue(state.compactControls);
        assertEquals("main",state.fpsScreen);
        assertTrue(state.pinnedApps.isEmpty());
        assertFalse("Offline loading must not materialize a fake legacy layout",fixture.values.containsKey("handheld:order"));
        assertFalse(fixture.values.containsKey("handheld:hidden"));
        assertFalse(fixture.values.containsKey("handheld:layoutVersion"));
        assertTrue("Later portable defaults must remain eligible: "+fixture.values,
                SharedDashboardLayout.shouldSeedPortableDefaults(SharedDashboardLayout.initialized(stringValues(fixture.values)),hasSavedLegacyLayouts(fixture.values)));
    }

    @Test public void offlinePreparationUsesLastKnownLegacyLayoutWithoutCopyingOutputHardwareValues(){
        PreferencesFixture fixture=new PreferencesFixture();
        putLegacyLayout(fixture,"handheld",ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden());
        putLegacyLayout(fixture,"output:tv-identity","wifi,bluetooth,volume","fan,apps");
        fixture.values.put("output:tv-identity:main",37);
        fixture.values.put("handheld:main",82);
        fixture.values.put("lastLayoutProfile","output:tv-identity");
        ProfileStore store=new ProfileStore(fixture);

        store.prepareDashboardLayout();
        UiState offline=new UiState();
        store.loadDashboardLayout("handheld",offline,"main");
        assertEquals(ControlLogic.cleanOrder("wifi,bluetooth,volume"),offline.controlOrder);
        assertEquals("fan,apps,quick_panel",offline.hiddenControls);
        assertEquals("37",fixture.values.get("output:tv-identity:main").toString());
        assertEquals("82",fixture.values.get("handheld:main").toString());
        assertEquals("output:tv-identity",fixture.values.get(SharedDashboardLayout.SOURCE_KEY));
    }

    @Test public void versionEightMigrationHidesNewPanelAndKeepsLaterUserChoice(){
        PreferencesFixture fixture=new PreferencesFixture();
        putLegacyLayout(fixture,"handheld","wifi,apps","volume");
        ProfileStore store=new ProfileStore(fixture);

        String migratedOrder=store.order("handheld");
        assertTrue(Arrays.asList(migratedOrder.split(",")).contains("quick_panel"));
        assertTrue(Arrays.asList(store.hidden("handheld").split(",")).contains("quick_panel"));
        assertEquals(8,fixture.values.get("handheld:layoutVersion"));

        store.layout("handheld",migratedOrder,"volume");
        ProfileStore reloaded=new ProfileStore(fixture);
        assertTrue(Arrays.asList(reloaded.order("handheld").split(",")).contains("quick_panel"));
        assertFalse(Arrays.asList(reloaded.hidden("handheld").split(",")).contains("quick_panel"));
        assertEquals(8,fixture.values.get("handheld:layoutVersion"));
    }

    private static Map<String,String> stringValues(Map<String,Object> values){
        Map<String,String> result=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:values.entrySet())result.put(entry.getKey(),String.valueOf(entry.getValue()));
        return result;
    }

    private static boolean hasSavedLegacyLayouts(Map<String,Object> values){
        for(String key:values.keySet())for(String suffix:new String[]{":order",":hidden",":wide",":small",":sizes",":actionColumns",":horizontal",":compact",":fpsScreen",":pinned",":layoutVersion"})
            if(key.endsWith(suffix)&&(key.startsWith("handheld:")||key.startsWith("output:")))return true;
        return false;
    }
}
