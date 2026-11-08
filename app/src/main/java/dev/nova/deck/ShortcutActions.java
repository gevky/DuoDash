package dev.nova.deck;

import java.util.LinkedHashMap;
import java.util.Map;

final class ShortcutActions {
    private ShortcutActions() {}

    static Map<String,String> options(UiState state) {
        Map<String,String> choices=new LinkedHashMap<>();
        for(ControlCatalog.Entry entry:ControlCatalog.entries())
            if("action".equals(entry.kind))choices.put(entry.id,entry.label);
        steps(choices,"main_brightness","Main brightness");
        steps(choices,"external_brightness","External brightness");
        steps(choices,"both_brightness","Both screens brightness");
        steps(choices,"master_audio","Master volume");
        steps(choices,"main_audio","Main audio");
        steps(choices,"external_audio","External audio");
        choices.put("mute_master","Mute master audio");
        choices.put("mute_main","Mute main audio");
        choices.put("mute_external","Mute external audio");
        choices.put("black_main","Main screen on/off");
        choices.put("black_upper","External screen on/off");
        choices.put("fps_cycle","Switch FPS screen");
        choices.put("refresh_toggle","Switch refresh rate");
        if(state!=null)for(UiState.AppShortcut app:state.pinnedApps)
            if(ControlCatalog.isApp(app.id))choices.put(app.id,app.label);
        return choices;
    }
    private static void steps(Map<String,String> choices,String id,String label) {
        choices.put(id+"_up",label+" up");choices.put(id+"_down",label+" down");
    }
    static String label(String id,UiState state) {
        if(ShortcutBindings.TOGGLE.equals(id))return "DuoDash";
        String label=options(state).get(id);
        return label==null?"Unavailable control":label;
    }
    static boolean valid(String id,UiState state) {return id!=null&&options(state).containsKey(id);}
    static void perform(DashboardActions actions,UiState state,String action){
        if(!valid(action,state))return;
        int step=action.endsWith("_down")?-5:5;
        switch(action){
            case "main_brightness_up":case "main_brightness_down":actions.setMain(state.main+step);return;
            case "external_brightness_up":case "external_brightness_down":actions.setUpper(state.upper+step);return;
            case "both_brightness_up":case "both_brightness_down":actions.setMain(state.main+step);actions.setUpper(state.upper+step);return;
            case "master_audio_up":case "master_audio_down":actions.setVolume(state.volume+step);return;
            case "main_audio_up":case "main_audio_down":actions.setMainAudio(state.mainAudio+step);return;
            case "external_audio_up":case "external_audio_down":actions.setUpperAudio(state.upperAudio+step);return;
            case "mute_master":actions.toggleAudioMute("volume");return;
            case "mute_main":actions.toggleAudioMute("audio_main");return;
            case "mute_external":actions.toggleAudioMute("audio_upper");return;
            default:actions.control(action);
        }
    }
    static boolean requiresExternal(String id) {
        return id!=null&&(id.startsWith("external_")||id.startsWith("both_brightness_")
            ||id.equals("mute_external")||id.equals("black_main")||id.equals("black_upper")
            ||id.equals("swap")||id.equals("up")||id.equals("down"));
    }
}
