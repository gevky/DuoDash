package dev.nova.deck;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class TvProfilePolicyTest {
    @Test public void secondaryHomeOnlyStartsForTheMatchingEmptyExternalDisplay() {
        assertTrue(TvProfilePolicy.shouldLaunchSecondaryHome(Collections.emptyList(),14,"com.android.launcher3"));
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(null,14,"com.android.launcher3"));
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(Collections.emptyList(),0,"com.android.launcher3"));
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(Collections.emptyList(),14,""));
    }

    @Test public void onlyVisibleExternalHomeOrUserTasksBlockSecondaryHome() {
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(4,14,"com.android.launcher3",true,false)),14,"com.android.launcher3"));
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(12,14,"com.example.game",true,true)),14,"com.android.launcher3"));
        assertTrue(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(4,14,"com.android.launcher3",false,false)),14,"com.android.launcher3"));
        assertTrue(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(5,14,"com.android.settings",false,true)),14,"com.android.launcher3"));
        assertTrue(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(12,14,"com.example.game",false,true)),14,"com.android.launcher3"));
        assertTrue(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList(new ControlLogic.Task(12,0,"com.example.game",true,true)),14,"com.android.launcher3"));
        assertFalse(TvProfilePolicy.shouldLaunchSecondaryHome(
            Arrays.asList((ControlLogic.Task)null),14,"com.android.launcher3"));
    }

    @Test public void secondaryHomeTargetMustBelongToTheResolvedDefaultHomePackage() {
        String resolved="priority=0 preferredOrder=0 match=0x108000 isDefault=true\ncom.android.launcher3/.uioverrides.QuickstepLauncher";
        String candidates="com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher\nrip.moth.cocoonshell/.ExternalDisplayActivity";
        assertEquals("com.android.launcher3",TvProfilePolicy.defaultHomePackage(resolved));
        assertEquals("com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher",
            TvProfilePolicy.secondaryHomeComponent("com.android.launcher3",candidates));
        assertNull(TvProfilePolicy.secondaryHomeComponent("com.example.home",candidates));
        assertNull(TvProfilePolicy.secondaryHomeComponent("com.android.launcher3",candidates+"\ncom.android.launcher3/.OtherHome"));
        assertNull(TvProfilePolicy.secondaryHomeComponent("com.android.launcher3","com.android.launcher3/.Bad;am start"));
        assertNull(TvProfilePolicy.defaultHomePackage("No activity found"));
    }

    @Test public void nativePanelOffRequiresTheRememberedConnectedOutputAndClosedControls() {
        assertTrue(TvProfilePolicy.shouldTurnPanelOff(true,true,true,true,false,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(false,true,true,true,false,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(true,false,true,true,false,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(true,true,false,true,false,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(true,true,true,false,false,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(true,true,true,true,true,false));
        assertFalse(TvProfilePolicy.shouldTurnPanelOff(true,true,true,true,false,true));
    }

    @Test public void visibleExternalPanelTemporarilyClearsDimWithoutChangingSavedValue() {
        assertEquals(100,TvProfilePolicy.effectiveUpperDimPercent(true,17,0));
        assertEquals(100,TvProfilePolicy.effectiveUpperDimPercent(true,17,48));
        assertEquals(0,TvProfilePolicy.effectiveUpperDimPercent(false,17,0));
        assertEquals(48,TvProfilePolicy.effectiveUpperDimPercent(false,17,48));
        assertEquals(0,TvProfilePolicy.effectiveUpperDimPercent(true,0,0));
        // Docked external controls are physically on the TV even though their WMS window is on display 0.
        assertEquals(100,TvProfilePolicy.effectiveUpperDimPercent(true,true,0));
        assertEquals(0,TvProfilePolicy.effectiveUpperDimPercent(false,true,0));
        assertEquals(0,TvProfilePolicy.effectiveUpperDimPercent(true,false,0));
    }

    @Test public void restoreOnlyOwnWritesAndNeverReblankAnAttachedPanel() {
        assertTrue(TvProfilePolicy.stillOwnsSetting("1","1"));
        assertFalse(TvProfilePolicy.stillOwnsSetting("0","1"));
        assertTrue(TvProfilePolicy.mayRestoreBaseline(true,"0",false));
        assertTrue(TvProfilePolicy.mayRestoreBaseline(true,"1",true));
        assertTrue(TvProfilePolicy.mayRestoreBaseline(false,"1",true));
        assertFalse(TvProfilePolicy.mayRestoreBaseline(false,"1",false));
    }

    @Test public void screenOffChoiceIsOutputScopedAndExplicitChoiceWinsBaseline() {
        assertFalse(TvProfilePolicy.initialScreenOffChoice(false,false,false,true));
        assertTrue(TvProfilePolicy.initialScreenOffChoice(true,false,false,true));
        assertFalse(TvProfilePolicy.initialScreenOffChoice(true,true,false,true));
        assertTrue(TvProfilePolicy.initialScreenOffChoice(true,true,true,false));
    }
}
