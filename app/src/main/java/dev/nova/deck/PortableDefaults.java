package dev.nova.deck;

import java.util.Collections;
import java.util.List;

/** Neutral dashboard choices for a genuinely fresh installation. */
final class PortableDefaults {
    static final boolean FULL_SCREEN = true;
    static final boolean FLOATING_HANDLE = false;
    static final boolean SHOW_REFRESH_RATE = false;
    static final boolean SHOW_WIFI = true;
    static final boolean SHOW_CLOCK = true;
    static final boolean SHOW_BATTERY_TIME = true;
    static final boolean HORIZONTAL_SLIDERS = true;
    static final boolean COMPACT_CONTROLS = true;
    static final int ACTION_COLUMNS = 3;
    static final int MAIN_BRIGHTNESS = 59;
    static final int EXTERNAL_BRIGHTNESS = 59;
    static final int MASTER_VOLUME = 100;
    static final int MAIN_AUDIO = 100;
    static final int EXTERNAL_AUDIO = 100;
    static final String FPS_SCREEN = "upper";
    static final String CPU_METRIC_MODE = MetricModePolicy.USAGE;
    static final String GPU_METRIC_MODE = MetricModePolicy.USAGE;
    static final String WIDE_CONTROLS = "";
    static final String SMALL_CONTROLS = "wifi,bluetooth";
    static final int LAYOUT_VERSION = 6;
    static final String ORDER = "both,upper,main,audio_upper,audio_main,volume,quick_settings,wifi,bluetooth,screenshot,apps,clear_recents,controller_style,night,dark,swap,up,down,rotation,dnd,keyboard,shortcuts,display_settings,audio_settings,fan,performance,charging,trigger_mode,charge_limit,led,keep_awake,force_landscape,game_assistant,airplane,battery_saver,inversion,cast,hotspot,location,refresh_mode,screenrecord,fps,temp,fan_speed,cpu,gpu,memory,power,refresh";
    static final String HIDDEN = "swap,up,down,rotation,dnd,keyboard,shortcuts,display_settings,audio_settings,fan,performance,charging,trigger_mode,charge_limit,led,keep_awake,force_landscape,game_assistant,airplane,battery_saver,inversion,cast,hotspot,location,refresh_mode,screenrecord,audio_output,controller_mouse,quick_panel";

    static final class App {
        final String label, packageName, component;
        final boolean external;

        App(String label, String packageName, String component, boolean external) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
            this.external = external;
        }
    }

    private static final List<App> APPS = Collections.emptyList();

    static List<App> apps() { return APPS; }

    static boolean isFreshInstall(long firstInstallTime, long lastUpdateTime, boolean hasPortablePreferences) {
        return firstInstallTime > 0 && firstInstallTime == lastUpdateTime && !hasPortablePreferences;
    }

    static boolean shouldSeedProfile(boolean templateEnabled, boolean profileAlreadyExists) {
        return templateEnabled && !profileAlreadyExists;
    }

    private PortableDefaults() {}
}
