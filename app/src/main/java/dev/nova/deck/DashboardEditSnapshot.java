package dev.nova.deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable copy of fields that can be changed during dashboard editing. */
final class DashboardEditSnapshot {
    final String profileKey,order,hidden,wide,small,sizes,cpuMode,gpuMode;
    final List<AppShortcutState> pinnedApps;

    private DashboardEditSnapshot(UiState state){
        profileKey=state.layoutKey;order=state.controlOrder;hidden=state.hiddenControls;wide=state.wideControls;
        small=state.smallControls;sizes=state.controlSizes;cpuMode=state.cpuMetricMode;gpuMode=state.gpuMetricMode;
        List<AppShortcutState> apps=new ArrayList<>();for(UiState.AppShortcut app:state.pinnedApps)apps.add(new AppShortcutState(app));
        pinnedApps=Collections.unmodifiableList(apps);
    }

    static DashboardEditSnapshot capture(UiState state){return new DashboardEditSnapshot(state);}
    List<UiState.AppShortcut> copyPinnedApps(){List<UiState.AppShortcut> apps=new ArrayList<>();for(AppShortcutState app:pinnedApps)apps.add(app.toShortcut());return apps;}
    UiState toUiState(String layoutKey){
        UiState state=new UiState();state.layoutKey=layoutKey;state.controlOrder=order;state.hiddenControls=hidden;state.wideControls=wide;state.smallControls=small;state.controlSizes=sizes;state.cpuMetricMode=cpuMode;state.gpuMetricMode=gpuMode;state.pinnedApps.addAll(copyPinnedApps());return state;
    }

    @Override public boolean equals(Object other){
        if(this==other)return true;if(!(other instanceof DashboardEditSnapshot))return false;
        DashboardEditSnapshot value=(DashboardEditSnapshot)other;
        return Objects.equals(profileKey,value.profileKey)&&Objects.equals(order,value.order)&&Objects.equals(hidden,value.hidden)
            &&Objects.equals(wide,value.wide)&&Objects.equals(small,value.small)&&Objects.equals(sizes,value.sizes)
            &&Objects.equals(cpuMode,value.cpuMode)&&Objects.equals(gpuMode,value.gpuMode)&&pinnedApps.equals(value.pinnedApps);
    }
    @Override public int hashCode(){return Objects.hash(profileKey,order,hidden,wide,small,sizes,cpuMode,gpuMode,pinnedApps);}

    static final class AppShortcutState {
        final String id,label,component;final boolean upper;
        AppShortcutState(UiState.AppShortcut app){id=app.id;label=app.label;component=app.component;upper=app.upper;}
        UiState.AppShortcut toShortcut(){return new UiState.AppShortcut(id,label,component,upper);}
        @Override public boolean equals(Object other){if(this==other)return true;if(!(other instanceof AppShortcutState))return false;AppShortcutState value=(AppShortcutState)other;return upper==value.upper&&Objects.equals(id,value.id)&&Objects.equals(label,value.label)&&Objects.equals(component,value.component);}
        @Override public int hashCode(){return Objects.hash(id,label,component,upper);}
    }
}
