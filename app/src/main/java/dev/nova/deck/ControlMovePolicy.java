package dev.nova.deck;

import java.util.Arrays;

/** Keeps drag-and-drop inside the dashboard group whose layout order is being edited. */
final class ControlMovePolicy {
    private static final String[] BRIGHTNESS={"both","upper","main"};
    private static final String[] AUDIO={"audio_upper","audio_main","volume"};

    private ControlMovePolicy() { }

    static String section(String id){
        ControlCatalog.Entry entry=ControlCatalog.find(id);
        if(entry==null)return ControlCatalog.isApp(id)?"actions":"";
        if(entry.kind.equals("metric"))return "metrics";
        if(entry.kind.equals("action"))return "actions";
        if(Arrays.asList(BRIGHTNESS).contains(id))return "brightness";
        if(Arrays.asList(AUDIO).contains(id))return "audio";
        return "";
    }

    static boolean canDrop(String sourceId,String targetId){
        String source=section(sourceId);
        return !source.isEmpty()&&source.equals(section(targetId));
    }
}
