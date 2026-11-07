package dev.nova.deck;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ControlLogicTest {
    @Test public void handheldShowsOnlyMainBrightnessAndMasterVolume(){
        for(String id:Arrays.asList("both","upper","audio_upper","audio_main"))assertFalse(id,ControlLogic.visibleOnDisplay(id,false));
        for(String id:Arrays.asList("main","volume","wifi","apps","fps"))assertTrue(id,ControlLogic.visibleOnDisplay(id,false));
    }
    @Test public void reconnectRestoresDualScreenBarsWithoutResettingManualRemovals(){
        Set<String> removed=new HashSet<>(Arrays.asList("audio_upper","wifi"));
        List<String> saved=Arrays.asList("upper","main","both","volume","audio_upper","audio_main");
        List<String> visible=new ArrayList<>();
        for(boolean connected:new boolean[]{true,false,true}){
            visible.clear();
            for(String id:saved)if(!removed.contains(id)&&ControlLogic.visibleOnDisplay(id,connected))visible.add(id);
            assertEquals(connected?Arrays.asList("upper","main","both","volume","audio_main"):Arrays.asList("main","volume"),visible);
        }
        assertEquals(new HashSet<>(Arrays.asList("audio_upper","wifi")),removed);
    }
    private ControlLogic.Task task(int id,int display,String pkg,boolean visible,boolean standard) {
        return new ControlLogic.Task(id,display,pkg,visible,standard);
    }
    @Test public void keepsMainReadableButAllowsExternalBlack() {
        assertEquals(5,ControlLogic.percent(-10,true));
        assertEquals(0,ControlLogic.percent(0,false));
        assertEquals(100,ControlLogic.percent(1000,false));
    }
    @Test public void dimmingCoversBlackThroughUnmodifiedWithMonotonicSteps() {
        assertEquals(1f,ControlLogic.dimAlpha(0),0.0001f);
        assertEquals(0f,ControlLogic.dimAlpha(100),0.0001f);
        assertTrue(ControlLogic.dimAlpha(20)>ControlLogic.dimAlpha(60));
    }
    @Test public void pendingExternalAppPreventsMirrorFallbackFromCoveringItsWindow() {
        assertTrue(ControlLogic.shouldMirrorExternal(true,false,false,false));
        assertTrue(ControlLogic.shouldMirrorExternal(false,true,false,false));
        assertFalse(ControlLogic.shouldMirrorExternal(false,true,true,false));
        assertFalse(ControlLogic.shouldMirrorExternal(true,false,false,true));
    }
    @Test public void externalAppTaskCountsBeforeItsWindowIsDrawnButLauncherDoesNot() {
        assertTrue(ControlLogic.hasExternalUserTask(Arrays.asList(task(7,4,"com.retroarch.aarch64",false,true)),4));
        assertFalse(ControlLogic.hasExternalUserTask(Arrays.asList(task(8,4,"com.retrohrai.launcher.secondary",true,true)),4));
        assertFalse(ControlLogic.hasExternalUserTask(Arrays.asList(task(9,0,"org.smarttube.stable",true,true)),4));
    }
    @Test public void outputIdentityRatherThanItsNameOwnsRememberedSettings() {
        assertEquals("output:local:123",ControlLogic.outputKey("local:123"));
        assertEquals("handheld",ControlLogic.outputKey(""));
        assertNotEquals(ControlLogic.outputKey("local:123"),ControlLogic.outputKey("local:456"));
    }
    @Test public void acceptsCustomOutputNamesAndBoundsTheirDisplayLength() {
        assertEquals("Living room TV",ControlLogic.normalizeName("  Living  room\n TV  ","External display"));
        assertEquals("External display",ControlLogic.normalizeName("   ","External display"));
        assertEquals(40,ControlLogic.normalizeName("x".repeat(80),"External display").length());
    }
    @Test public void repairsLayoutOrderAndKeepsOnlyKnownHiddenControls() {
        String cleaned=ControlLogic.cleanOrder("upper,upper,unknown,main");
        assertTrue(cleaned.startsWith("upper,main,"));
        assertEquals(ControlCatalog.entries().size(),new HashSet<>(Arrays.asList(cleaned.split(","))).size());
        assertEquals("main,power",ControlLogic.cleanHidden("main,not-a-control,main,power"));
    }
    @Test public void catalogDropsLegacyRestoreAndKeepsPinnedAppControls(){
        String app="app_0123456789abcdef01234567";
        String order=ControlLogic.cleanOrder("wifi,"+app+",restore,not-real");
        assertTrue(order.startsWith("wifi,"+app+","));assertFalse(order.contains("restore"));
        assertNull(ControlCatalog.find("restore"));assertNotNull(ControlCatalog.find("screenshot"));
        assertFalse(ControlCatalog.defaultHidden().contains("wifi"));
    }
    @Test public void catalogUpgradePreservesCustomLayoutsButRefreshesUntouchedDefaults(){
        String old="volume,main,upper,both,swap,up,down,restore,refresh,temp,memory,power";
        String[] defaults=ControlLogic.upgradeLayout(old,"");assertEquals(ControlCatalog.defaultOrder(),defaults[0]);assertEquals(ControlCatalog.defaultHidden(),defaults[1]);
        String[] custom=ControlLogic.upgradeLayout("upper,main,volume","volume");
        assertTrue(custom[0].startsWith("upper,main,volume,"));assertTrue(Arrays.asList(custom[1].split(",")).contains("volume"));
        assertTrue(Arrays.asList(custom[1].split(",")).contains("wifi"));assertFalse(Arrays.asList(custom[1].split(",")).contains("upper"));
    }
    @Test public void actionNormalizationPreservesMoreThanTwelveControlsAndSavedHiddenChoices(){
        String hidden="wifi,bluetooth,apps";
        String normalized=ControlLogic.cleanHidden(hidden+",unknown,wifi");
        assertEquals(hidden,normalized);
        assertTrue(ControlLogic.actionCount(ControlCatalog.defaultOrder(),"")>12);
        assertEquals(9,ControlLogic.actionCount(ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden()));
        assertTrue(ControlLogic.showAddControl(true,false));
        assertFalse(ControlLogic.showAddControl(false,false));
        assertTrue(ControlLogic.showAddControl(false,true));
    }
    @Test public void movesWidgetsByOrderAndReplacesOnlyInactiveControlsInPlace(){
        String order=ControlCatalog.defaultOrder(),hidden=ControlCatalog.defaultHidden();
        String moved=ControlLogic.moveOrder(order,"swap","down");
        assertTrue(moved.indexOf("down")<moved.indexOf("swap"));
        String[] replaced=ControlLogic.replaceLayout(order,hidden,"swap","rotation");
        assertNotNull(replaced);assertTrue(replaced[0].startsWith("both,upper,main,audio_upper,audio_main,volume,rotation,"));
        assertTrue(Arrays.asList(replaced[1].split(",")).contains("swap"));
        assertFalse(Arrays.asList(replaced[1].split(",")).contains("rotation"));
        assertEquals(9,ControlLogic.actionCount(replaced[0],replaced[1]));
        assertNull(ControlLogic.replaceLayout(order,hidden,"swap","wifi"));
        assertNull(ControlLogic.replaceLayout(order,hidden,"swap","temp"));
    }
    @Test public void replaceCanPinNewAppWhenExistingLayoutAlreadyHasMoreThanTwelveActions(){
        String app="app_0123456789abcdef01234567";
        String[] replaced=ControlLogic.replaceLayout(ControlCatalog.defaultOrder(),"","swap",app);
        assertNotNull(replaced);assertTrue(replaced[0].contains(app));
        assertTrue(Arrays.asList(replaced[1].split(",")).contains("swap"));
        assertTrue(ControlLogic.actionCount(replaced[0],replaced[1])>12);
    }
    @Test public void deletingPinnedAppPurgesOnlyItsMetadataAndAllowsFreshPin(){
        String deleted="app_0123456789abcdef01234567",hiddenPinned="app_1123456789abcdef01234567";
        String orphan="app_2123456789abcdef01234567",active="app_3123456789abcdef01234567";
        String order="wifi,"+deleted+","+hiddenPinned+","+orphan+","+active+",rotation";
        String hidden="wifi,"+deleted+","+hiddenPinned+","+orphan+",rotation";
        String wide="wifi,"+deleted+","+hiddenPinned+","+orphan+","+active;
        List<String> pinned=Arrays.asList(deleted,hiddenPinned,active,active);
        List<String> otherProfilePinned=new ArrayList<>(Arrays.asList(deleted,"app_4123456789abcdef01234567"));

        ControlLogic.AppControlState removed=ControlLogic.cleanAppControls(order,hidden,wide,pinned,deleted);
        List<String> removedOrder=Arrays.asList(removed.order.split(",")),removedHidden=Arrays.asList(removed.hidden.split(",")),removedWide=Arrays.asList(removed.wide.split(","));
        for(String id:Arrays.asList(deleted,hiddenPinned,orphan)){assertFalse(removedOrder.contains(id));assertFalse(removedHidden.contains(id));assertFalse(removedWide.contains(id));}
        assertTrue(removedHidden.containsAll(Arrays.asList("wifi","rotation")));
        assertEquals(Collections.singletonList(active),removed.pinnedAppIds);
        assertEquals(1,Collections.frequency(removedOrder,active));

        // A separate profile containing the same app is a distinct input and remains intact.
        ControlLogic.AppControlState other=ControlLogic.cleanAppControls("wifi,"+deleted,"", "",otherProfilePinned,null);
        assertTrue(other.pinnedAppIds.contains(deleted));assertTrue(other.order.contains(deleted));
        assertEquals(Arrays.asList(deleted,"app_4123456789abcdef01234567"),otherProfilePinned);

        // Built-in controls remain hidden/re-addable; app deletion only removes app metadata.
        Set<String> builtInHidden=new LinkedHashSet<>(removedHidden);assertTrue(removedOrder.contains("wifi"));
        assertTrue(builtInHidden.remove("wifi"));assertFalse(builtInHidden.contains("wifi"));

        // Adding the deleted shortcut again starts clean and cannot duplicate its order/pin entry.
        List<String> repinned=new ArrayList<>(removed.pinnedAppIds);repinned.add(deleted);repinned.add(deleted);
        ControlLogic.AppControlState fresh=ControlLogic.cleanAppControls(removed.order+","+deleted+","+deleted,removed.hidden,removed.wide,repinned,null);
        assertEquals(1,Collections.frequency(Arrays.asList(fresh.order.split(",")),deleted));
        assertEquals(1,Collections.frequency(fresh.pinnedAppIds,deleted));
        assertFalse(Arrays.asList(fresh.hidden.split(",")).contains(deleted));
    }
    @Test public void uncappedActionsPreserveCurrentDefaultAndDoNotAutohideAnything(){
        assertEquals(9,ControlLogic.actionCount(ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden()));
        String hidden=ControlCatalog.defaultHidden();
        String allVisible=ControlLogic.cleanHidden("");
        assertEquals(hidden,ControlLogic.cleanHidden(hidden));
        assertEquals("",allVisible);
        assertTrue(ControlLogic.actionCount(ControlCatalog.defaultOrder(),allVisible)>12);
    }
    @Test public void quickButtonGridPacksFourColumnsAndWideButtonsWithoutHoles(){
        assertEquals(2,ControlLogic.actionColumns(1));assertEquals(4,ControlLogic.actionColumns(9));
        List<ControlLogic.GridPlacement> placements=ControlLogic.packActionGrid(Arrays.asList("swap","up","down","wifi","bluetooth","apps"),"up,apps",4);
        assertEquals(6,placements.size());
        assertCell(placements.get(0),"swap",0,0,1);assertCell(placements.get(1),"up",0,1,2);assertCell(placements.get(2),"down",0,3,1);
        assertCell(placements.get(3),"wifi",1,0,1);assertCell(placements.get(4),"bluetooth",1,1,1);assertCell(placements.get(5),"apps",1,2,2);
        List<ControlLogic.GridPlacement> three=ControlLogic.packActionGrid(Arrays.asList("swap","up","down"),"up",3);
        assertCell(three.get(2),"down",1,0,1);
    }
    @Test public void removeBadgeUsesSmallCircularCornerHitAndLeavesMetricBodyTappable(){
        assertFalse(ControlLogic.inRemoveBadge(40,30,80,60,10));
        assertFalse("Old L-shaped target must no longer cover most of a small metric",ControlLogic.inRemoveBadge(60,25,80,60,10));
        assertTrue(ControlLogic.inRemoveBadge(70,10,80,60,10));
        assertTrue(ControlLogic.inRemoveBadge(62,10,80,60,10));
        assertFalse(ControlLogic.inRemoveBadge(50,15,80,60,10));
        assertTrue("Badge hit stays usable in a narrow widget",ControlLogic.inRemoveBadge(6,6,12,15,10));
    }
    @Test public void removeBadgeRequiresPressAndReleaseInsideCircle(){
        assertTrue(ControlLogic.shouldRemoveBadge(true,true));
        assertFalse(ControlLogic.shouldRemoveBadge(true,false));
        assertFalse(ControlLogic.shouldRemoveBadge(false,true));
        assertFalse(ControlLogic.shouldRemoveBadge(false,false));
    }
    @Test public void fpsScreenIndicatorsMapTopToExternalAndBottomToMain(){
        assertTrue(ControlLogic.fpsMarkerFilled(true,true));assertFalse(ControlLogic.fpsMarkerFilled(true,false));
        assertFalse(ControlLogic.fpsMarkerFilled(false,true));assertTrue(ControlLogic.fpsMarkerFilled(false,false));
    }
    @Test public void addControlAppearsWhileEditingOrWhenNoActionsRemain(){
        assertTrue(ControlLogic.showAddControl(true,false));
        assertTrue(ControlLogic.showAddControl(true,true));
        assertTrue(ControlLogic.showAddControl(false,true));
        assertFalse(ControlLogic.showAddControl(false,false));
    }
    @Test public void mergesLegacyPerScreenFpsControlsIntoOneVisibleProfileSlot(){
        String order="temp,main_fps,cpu,upper_fps,power";
        String[] migrated=ControlLogic.mergeLegacyFps(order,"main_fps,upper_fps");
        assertEquals("temp,fps,cpu,power",migrated[0]);assertTrue(Arrays.asList(migrated[1].split(",")).contains("fps"));
        assertEquals("main",ControlLogic.legacyFpsSelection(order,"upper_fps"));
        assertEquals("upper",ControlLogic.legacyFpsSelection(order,"main_fps"));
        String[] oneVisible=ControlLogic.mergeLegacyFps(order,"upper_fps");assertFalse(Arrays.asList(oneVisible[1].split(",")).contains("fps"));
        assertEquals("main",ControlLogic.legacyFpsSelection(order,""));
        assertFalse(ControlLogic.DEFAULT_ORDER.contains("main_fps"));assertTrue(ControlLogic.DEFAULT_ORDER.contains("fps"));
    }
    private void assertCell(ControlLogic.GridPlacement cell,String id,int row,int column,int span){assertEquals(id,cell.id);assertEquals(row,cell.row);assertEquals(column,cell.column);assertEquals(span,cell.span);}
    @Test public void foregroundNeverSelectsLauncherOverlayOrHiddenTasks() {
        List<ControlLogic.Task> tasks=List.of(task(1,0,"launcher",true,false),task(2,0,"dev.nova.deck",true,true),
                task(3,0,"background.game",false,true),task(4,0,"visible.game",true,true));
        assertEquals(4,ControlLogic.foreground(tasks,0).id);
    }
    @Test public void swapsOnlyTwoForegroundAppsAndKeepsTheirOriginalSourcesForRollback() {
        List<ControlLogic.Move> moves=ControlLogic.swapPlan(List.of(task(5,0,"game",true,true),task(8,9,"settings",true,true)),9);
        assertEquals(2,moves.size());
        assertEquals(5,moves.get(0).task); assertEquals(0,moves.get(0).from); assertEquals(9,moves.get(0).to);
        assertEquals(8,moves.get(1).task); assertEquals(9,moves.get(1).from); assertEquals(0,moves.get(1).to);
    }
    @Test public void movesLoneUpperAppDownAndNeverMovesAnythingWithoutAnExternalDisplay() {
        List<ControlLogic.Task> tasks=List.of(task(8,9,"settings",true,true));
        List<ControlLogic.Move> moves=ControlLogic.swapPlan(tasks,9);
        assertEquals(1,moves.size()); assertEquals(0,moves.get(0).to);
        assertTrue(ControlLogic.swapPlan(tasks,-1).isEmpty());
    }
    @Test public void readdedControlGoesAfterLastVisibleControlInItsGroup(){
        Set<String> hidden=new LinkedHashSet<>(Arrays.asList(ControlCatalog.defaultOrder().split(",")));
        hidden.removeAll(Arrays.asList("wifi","dark","bluetooth"));
        String order=ControlLogic.appendControlToGroupEnd("wifi,bluetooth,dark",String.join(",",hidden),"bluetooth");
        List<String> visible=new ArrayList<>();for(String id:order.split(","))if(!hidden.contains(id))visible.add(id);
        assertEquals(Arrays.asList("wifi","dark","bluetooth"),visible);
    }
    @Test public void repeatedAddsFollowThePreviousAddWithoutMovingOtherGroups(){
        Set<String> hidden=new LinkedHashSet<>(Arrays.asList(ControlCatalog.defaultOrder().split(",")));
        hidden.removeAll(Arrays.asList("wifi","main","upper"));
        hidden.remove("bluetooth");String order=ControlLogic.appendControlToGroupEnd("wifi,main,bluetooth,upper,dark",String.join(",",hidden),"bluetooth");
        hidden.remove("dark");order=ControlLogic.appendControlToGroupEnd(order,String.join(",",hidden),"dark");
        List<String> actions=new ArrayList<>();for(String id:order.split(","))if(!hidden.contains(id)&&ControlLogic.action(id))actions.add(id);
        assertEquals(Arrays.asList("wifi","bluetooth","dark"),actions);
        assertTrue(order.indexOf("main")<order.indexOf("upper"));
    }
    @Test public void addingBrightnessKeepsItAfterBrightnessRatherThanActionButtons(){
        Set<String> hidden=new LinkedHashSet<>(Arrays.asList(ControlCatalog.defaultOrder().split(",")));
        hidden.removeAll(Arrays.asList("both","main","upper","wifi"));
        String order=ControlLogic.appendControlToGroupEnd("both,upper,main,wifi",String.join(",",hidden),"upper");
        List<String> visible=new ArrayList<>();for(String id:order.split(","))if(!hidden.contains(id))visible.add(id);
        assertEquals(Arrays.asList("both","main","upper","wifi"),visible);
    }
    @Test public void newOptionalControlsStayHiddenWithoutChangingExistingChoices(){
        String hidden=ControlLogic.hideMissingOptionalControls("wifi,apps","bluetooth","clear_recents","audio_output","controller_mouse","quick_settings");
        assertEquals("bluetooth,clear_recents,audio_output,controller_mouse,quick_settings",hidden);
    }
    @Test public void optionalMigrationKeepsAlreadyChosenControlsAndIsIdempotent(){
        String order="wifi,audio_output,apps",hidden="bluetooth,quick_settings";
        String once=ControlLogic.hideMissingOptionalControls(order,hidden,"clear_recents","audio_output","controller_mouse","quick_settings");
        assertFalse(Arrays.asList(once.split(",")).contains("audio_output"));
        assertEquals(once,ControlLogic.hideMissingOptionalControls(order,once,"clear_recents","audio_output","controller_mouse","quick_settings"));
    }
    @Test public void quickPanelMigrationHidesOnlyWhenItWasAbsentFromSavedOrder(){
        String missing=ControlLogic.hideMissingOptionalControls("wifi,apps","bluetooth","quick_panel");
        assertEquals("bluetooth,quick_panel",missing);
        String chosen=ControlLogic.hideMissingOptionalControls("wifi,quick_panel,apps","bluetooth","quick_panel");
        assertEquals("bluetooth",chosen);
    }

}
