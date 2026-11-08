package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShortcutActionsTest {
    @Test public void offersActionsInsteadOfReadOnlyMetrics() {
        UiState state=new UiState();
        assertTrue(ShortcutActions.valid("quick_settings",state));
        assertTrue(ShortcutActions.valid("quick_panel",state));
        assertTrue(ShortcutActions.valid("main_brightness_up",state));
        assertTrue(ShortcutActions.valid("mute_main",state));
        assertFalse(ShortcutActions.valid("temp",state));
        assertFalse(ShortcutActions.valid("memory",state));
        assertFalse(ShortcutActions.valid("main",state));
        assertFalse(ShortcutActions.valid("reboot",state));
        assertFalse(ShortcutActions.valid(null,state));
    }
    @Test public void appActionsFollowLiveSavedAppControls() {
        UiState state=new UiState();String id="app_"+"a".repeat(24);
        assertFalse(ShortcutActions.valid(id,state));
        state.pinnedApps.add(new UiState.AppShortcut(id,"My app","com.example/.Main",false));
        assertTrue(ShortcutActions.valid(id,state));
        assertEquals("My app",ShortcutActions.label(id,state));
        state.pinnedApps.clear();assertFalse(ShortcutActions.valid(id,state));
    }
    @Test public void externalActionsCannotAccidentallyChangeTheHandheldAlone() {
        assertTrue(ShortcutActions.requiresExternal("external_audio_up"));
        assertTrue(ShortcutActions.requiresExternal("both_brightness_down"));
        assertTrue(ShortcutActions.requiresExternal("black_main"));
        assertFalse(ShortcutActions.requiresExternal("main_brightness_down"));
        assertFalse(ShortcutActions.requiresExternal("master_audio_up"));
    }
    @Test public void brightnessStepsPreserveIndependentScreenValues() {
        UiState state=new UiState();state.main=40;state.upper=75;
        java.util.List<String> calls=new java.util.ArrayList<>();
        DashboardActions actions=(DashboardActions)java.lang.reflect.Proxy.newProxyInstance(
            DashboardActions.class.getClassLoader(),new Class<?>[]{DashboardActions.class},
            (proxy,method,args)->{calls.add(method.getName()+":"+java.util.Arrays.toString(args));return null;});
        ShortcutActions.perform(actions,state,"both_brightness_up");
        assertEquals(java.util.Arrays.asList("setMain:[45]","setUpper:[80]"),calls);
        calls.clear();ShortcutActions.perform(actions,state,"mute_external");
        assertEquals(java.util.Arrays.asList("toggleAudioMute:[audio_upper]"),calls);
        calls.clear();ShortcutActions.perform(actions,state,"reboot");assertTrue(calls.isEmpty());
    }
}
