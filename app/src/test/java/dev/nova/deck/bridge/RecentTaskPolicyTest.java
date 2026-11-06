package dev.nova.deck.bridge;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RecentTaskPolicyTest {
    private RecentTaskPolicy.Task task(int id,int display,int type,String top,String base){
        return new RecentTaskPolicy.Task(id,display,type,top,base);
    }

    @Test public void removesOrdinaryAndUserFacingSystemAppTasksAcrossDisplays(){
        Set<String> homes=Collections.singleton("com.vendor.launcher");
        List<RecentTaskPolicy.Task> selected=RecentTaskPolicy.removable(Arrays.asList(
                task(10,0,1,"com.example.game","com.example.game"),
                task(11,15,1,"com.android.calculator2","com.android.calculator2"),
                task(12,32,1,"com.android.chrome","com.android.chrome"),
                task(13,0,1,"com.android.settings","com.android.settings"),
                task(14,0,1,"com.rp.settings","com.rp.settings")),homes);
        assertEquals(5,selected.size());
        assertEquals(0,selected.get(0).display);
        assertEquals(15,selected.get(1).display);
        assertEquals(32,selected.get(2).display);
    }

    @Test public void preservesDashboardLauncherHomeRecentsAndCoreInfrastructure(){
        Set<String> homes=Collections.singleton("com.vendor.launcher");
        List<RecentTaskPolicy.Task> tasks=Arrays.asList(
                task(1,0,1,"dev.nova.deck","dev.nova.deck"),
                task(2,0,2,"com.example.launcher","com.example.launcher"),
                task(3,0,3,"com.android.systemui","com.android.systemui"),
                task(4,0,1,"com.vendor.launcher","com.vendor.launcher"),
                task(5,0,1,"com.android.permissioncontroller","com.android.permissioncontroller"),
                task(6,0,1,"com.example.game","com.android.permissioncontroller"),
                task(7,0,1,"com.android.packageinstaller","com.android.packageinstaller"),
                task(8,0,1,"com.rp.gameassistant","com.rp.gameassistant"),
                task(9,0,1,"com.android.packageinstaller","com.android.packageinstaller"));
        assertTrue(RecentTaskPolicy.removable(tasks,homes).isEmpty());
    }

    @Test public void skipsInvalidAndUnknownTaskMetadataSafely(){
        Set<String> homes=Collections.emptySet();
        assertFalse(RecentTaskPolicy.removable(task(-1,0,1,"com.example.game","com.example.game"),homes));
        assertFalse(RecentTaskPolicy.removable(task(1,0,1,null,"com.example.game"),homes));
        assertFalse(RecentTaskPolicy.removable(task(1,0,1,"com.example.game",null),homes));
        assertFalse(RecentTaskPolicy.removable(task(1,0,0,"com.example.game","com.example.game"),homes));
        assertFalse(RecentTaskPolicy.removable(task(1,0,1,"com.example.game","com.example.game"),null));
    }
    @Test public void combinesHistoryAndLiveTasksIncludingExternalWithoutDuplicateRemoval(){
        RecentTaskPolicy.Task history=task(10,0,1,"com.example.main","com.example.main");
        RecentTaskPolicy.Task stale=task(11,0,1,"com.example.external","com.example.external");
        RecentTaskPolicy.Task live=task(11,5,1,"com.example.external","com.example.external");
        RecentTaskPolicy.Task notInRecents=task(12,5,1,"com.example.other","com.example.other");
        List<RecentTaskPolicy.Task> combined=RecentTaskPolicy.combine(
                Arrays.asList(history,stale),Arrays.asList(live,notInRecents));
        assertEquals(3,combined.size());
        assertEquals(5,combined.get(1).display);
        assertEquals(12,combined.get(2).id);
        assertEquals(3,RecentTaskPolicy.removable(combined,Collections.singleton("com.vendor.launcher")).size());
    }

    @Test public void protectsHomeAndDashboardWhenTheyExistOnlyInLiveTasks(){
        List<RecentTaskPolicy.Task> combined=RecentTaskPolicy.combine(Collections.emptyList(),Arrays.asList(
                task(1,0,2,"com.vendor.launcher","com.vendor.launcher"),
                task(2,5,1,"dev.nova.deck","dev.nova.deck"),
                task(3,5,1,"com.example.game","com.example.game")));
        List<RecentTaskPolicy.Task> removable=RecentTaskPolicy.removable(combined,Collections.singleton("com.vendor.launcher"));
        assertEquals(1,removable.size());
        assertEquals(3,removable.get(0).id);
    }

}
