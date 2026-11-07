package dev.nova.deck;
import java.util.*;
/** Shared catalog for the dashboard and add/remove editor. */
public final class ControlCatalog {
    public static final class Entry {
        public final String id,label,description,group,kind;
        public final boolean defaultShown;
        Entry(String id,String label,String description,String group,String kind,boolean shown){this.id=id;this.label=label;this.description=description;this.group=group;this.kind=kind;this.defaultShown=shown;}
    }
    private static Entry e(String id,String label,String description,String group,String kind,boolean shown){return new Entry(id,label,description,group,kind,shown);}
    private static final List<Entry> ENTRIES=Collections.unmodifiableList(Arrays.asList(
        e("both","Both screens","Adjust both screen brightness levels","Brightness","slider",true),
        e("upper","External screen","Dim the external screen","Brightness","slider",true),
        e("main","Main screen","Built-in screen brightness","Brightness","slider",true),
        e("audio_upper","External audio","Volume of the app on the external screen","Audio","slider",true),
        e("audio_main","Main audio","Volume of the app on the main screen","Audio","slider",true),
        e("volume","Master volume","Android media volume","Audio","slider",true),
        e("swap","Swap apps","Exchange the two running apps","Actions","action",true),
        e("up","Send up","Move the main app to the external screen","Actions","action",true),
        e("down","Send down","Move the external app to the main screen","Actions","action",true),
        e("wifi","Wi-Fi","Turn Wi-Fi on or off","Connectivity","action",true),
        e("bluetooth","Bluetooth","Turn Bluetooth on or off","Connectivity","action",true),
        e("rotation","Auto-rotate","Toggle automatic screen rotation","Display","action",false),
        e("dnd","Do not disturb","Native notification-priority toggle","Sound","action",false),
        e("keyboard","Keyboard","Choose an input method","Tools","action",false),
        e("screenshot","Screenshot","Tap to choose screens; hold to capture","Tools","action",false),
        e("apps","Open app","Choose an app and target screen","Apps","action",true),
        e("clear_recents","Clear recent apps","Close recent app tasks while keeping DuoDash running","Tools","action",false),
        e("audio_output","Audio output","Choose speakers, headphones or another audio device","Sound","action",false),
        e("controller_mouse","Controller mouse","Use the controller as a mouse on supported Retroid firmware","Device","action",false),
        e("quick_settings","Settings","Open Android system settings","Tools","action",false),
        e("shortcuts","Shortcuts","Configure controller-button shortcuts","Tools","action",false),
        e("display_settings","Display settings","Open Android display settings","Display","action",false),
        e("audio_settings","Sound settings","Open Android sound settings","Sound","action",false),
        e("fan","Fan mode","Nova Quiet, Smart, Sport and saved Custom modes","Device","action",true),
        e("performance","Performance","Nova native performance modes","Device","action",true),
        e("charging","Direct power","Nova bypass-charging toggle","Device","action",true),
        e("controller_style","Controller style","Native Retro, Xbox and disabled layouts","Device","action",false),
        e("trigger_mode","Trigger mode","Native analog, digital or combined triggers","Device","action",false),
        e("charge_limit","80% charge limit","Native battery charge-limit toggle","Device","action",false),
        e("led","Stick lights","Native joystick-light toggle","Device","action",false),
        e("keep_awake","Keep screen on","Run the Nova's original Quick Setting","Device","action",false),
        e("force_landscape","Force landscape","Run the Nova's original Quick Setting","Device","action",false),
        e("game_assistant","Game Assistant","Open the Nova's original game overlay","Device","action",false),
        e("airplane","Airplane mode","Native airplane-mode toggle","Connectivity","action",false),
        e("battery_saver","Battery saver","Native battery-saver toggle","Device","action",false),
        e("night","Night light","Native night-light toggle","Display","action",false),
        e("dark","Dark theme","Native Android theme toggle","Display","action",false),
        e("inversion","Color inversion","Native accessibility color inversion","Display","action",false),
        e("cast","Cast","Open Android casting controls","Connectivity","action",false),
        e("hotspot","Hotspot","Open Android hotspot configuration","Connectivity","action",false),
        e("location","Location","Native location toggle","Connectivity","action",false),
        e("refresh_mode","Refresh rate","Choose a supported built-in display refresh rate","Display","action",false),
        e("screenrecord","Screen recording","Record screen video without audio, up to three minutes","Tools","action",false),
        e("fps","FPS","Foreground app frames on the selected screen","Monitoring","metric",true),
        e("temp","Temperature","Live reported temperature","Monitoring","metric",true),
        e("fan_speed","Fan output","Actual fan PWM duty percentage","Monitoring","metric",true),
        e("cpu","CPU","CPU clock or usage, depending on the selected mode","Monitoring","metric",true),
        e("gpu","GPU","GPU clock or usage, depending on the selected mode","Monitoring","metric",true),
        e("memory","Memory","Current memory usage","Monitoring","metric",true),
        e("power","Battery power","Battery power reading","Monitoring","metric",true),
        e("refresh","Refresh monitor","Current display refresh rate, separate from game FPS","Monitoring","metric",false),
        e("quick_panel","Quick settings","Open Android quick-settings panel","Tools","action",false)
    ));
    public static List<Entry> entries(){return ENTRIES;}
    public static Entry find(String id){for(Entry e:ENTRIES)if(e.id.equals(id))return e;return null;}
    public static boolean isApp(String id){return id!=null&&id.matches("app_[a-f0-9]{24}");}
    public static boolean known(String id){return find(id)!=null||isApp(id);}
    public static String defaultOrder(){List<String> ids=new ArrayList<>();for(Entry e:ENTRIES)ids.add(e.id);return String.join(",",ids);}
    public static String defaultHidden(){List<String> ids=new ArrayList<>();for(Entry e:ENTRIES)if(!e.defaultShown)ids.add(e.id);return String.join(",",ids);}
}
