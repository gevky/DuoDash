package dev.nova.deck;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DashboardEditHistoryTest {
    private UiState state(String order){UiState state=new UiState();state.controlOrder=order;return state;}

    @Test public void snapshotsCopyAppMetadataAndTrackOnlyEditableFields(){
        UiState state=state("wifi,apps");
        UiState.AppShortcut app=new UiState.AppShortcut("app_0123456789abcdef01234567","Music","music/.Player",true);
        state.pinnedApps.add(app);state.controlSizes="wifi:3";state.cpuMetricMode=MetricModePolicy.USAGE;
        DashboardEditSnapshot snapshot=DashboardEditSnapshot.capture(state);
        app.label="Changed";app.upper=false;state.pinnedApps.clear();state.controlOrder="bluetooth";
        assertEquals("wifi,apps",snapshot.order);assertEquals(1,snapshot.pinnedApps.size());
        assertEquals("Music",snapshot.pinnedApps.get(0).label);assertTrue(snapshot.pinnedApps.get(0).upper);
        assertEquals("wifi:3",snapshot.sizes);assertEquals(MetricModePolicy.USAGE,snapshot.cpuMode);
        assertEquals("wifi,apps",snapshot.toUiState("handheld").controlOrder);
        List<UiState.AppShortcut> restored=snapshot.copyPinnedApps();restored.get(0).label="Another change";
        assertEquals("Music",snapshot.pinnedApps.get(0).label);
        UiState live=state("wifi");DashboardEditSnapshot before=DashboardEditSnapshot.capture(live);
        live.main=5;live.upper=0;live.volume=0;live.cpuGhz=2.5f;live.gpuLoadPercent=40;live.wifiConnected=true;live.battery=25;
        live.controls.put("wifi",new UiState.ControlStatus(true,true,"On"));
        assertEquals(before,DashboardEditSnapshot.capture(live));
    }

    @Test public void historyIgnoresRepeatedSnapshotsAndSupportsUndo(){
        DashboardEditHistory history=new DashboardEditHistory();UiState initial=state("wifi");
        history.begin(DashboardEditSnapshot.capture(initial));
        assertFalse(history.observe(DashboardEditSnapshot.capture(state("wifi"))));
        assertTrue(history.observe(DashboardEditSnapshot.capture(state("bluetooth"))));
        assertFalse(history.observe(DashboardEditSnapshot.capture(state("bluetooth"))));
        assertTrue(history.canUndo());assertEquals("wifi",history.undo().order);
        assertFalse(history.canUndo());
        assertFalse(history.observe(DashboardEditSnapshot.capture(state("wifi"))));assertFalse(history.canUndo());
    }

    @Test public void historyKeepsOnlyTwentyMostRecentSteps(){
        DashboardEditHistory history=new DashboardEditHistory();history.begin(DashboardEditSnapshot.capture(state("step0")));
        for(int step=1;step<=22;step++)assertTrue(history.observe(DashboardEditSnapshot.capture(state("step"+step))));
        assertEquals(20,history.size());
        for(int step=21;step>=2;step--)assertEquals("step"+step,history.undo().order);
        assertFalse(history.canUndo());
    }

    @Test public void clearEndsHistoryAndSnapshotsIncludePinnedAndMetricChanges(){
        DashboardEditHistory history=new DashboardEditHistory();UiState initial=state("wifi");history.begin(DashboardEditSnapshot.capture(initial));
        UiState changed=state("wifi");changed.gpuMetricMode=MetricModePolicy.CLOCK;
        assertTrue(history.observe(DashboardEditSnapshot.capture(changed)));
        UiState pinned=state("wifi");pinned.pinnedApps.add(new UiState.AppShortcut("app_0123456789abcdef01234567","App","pkg/.Home",false));
        assertTrue(history.observe(DashboardEditSnapshot.capture(pinned)));
        history.clear();assertEquals(0,history.size());assertNull(history.undo());
    }
}
