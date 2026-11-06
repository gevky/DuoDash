package dev.nova.deck;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Seeds portable user-facing defaults once, only on an empty fresh installation. */
final class InstallDefaults {
    private static final String PREFS = "install_defaults";
    private static final String INITIALIZED = "initialized";
    private static final String APPLY_PROFILE_TEMPLATE = "apply_profile_template";

    static void initialize(Context context) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (state.contains(INITIALIZED)) return;
        SharedPreferences ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        SharedPreferences profiles = context.getSharedPreferences("display_profiles", Context.MODE_PRIVATE);
        SharedPreferences setup = context.getSharedPreferences("setup_readiness", Context.MODE_PRIVATE);
        SharedPreferences activation = context.getSharedPreferences("bridge_activation", Context.MODE_PRIVATE);
        boolean hasExistingAppData = !profiles.getAll().isEmpty() || !ui.getAll().isEmpty()
            || !setup.getAll().isEmpty() || !activation.getAll().isEmpty()
            || new java.io.File(context.getFilesDir(), "bridge-token").exists()
            || new java.io.File(context.getFilesDir(), "adb-private-key").exists();
        boolean fresh = false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            fresh = PortableDefaults.isFreshInstall(info.firstInstallTime, info.lastUpdateTime, hasExistingAppData);
        } catch (PackageManager.NameNotFoundException ignored) {}

        SharedPreferences.Editor marker = state.edit().putBoolean(INITIALIZED, true).putBoolean(APPLY_PROFILE_TEMPLATE, fresh);
        if (fresh) {
            ui.edit()
                .putBoolean("fullScreen", PortableDefaults.FULL_SCREEN)
                .putBoolean("floatingButton", PortableDefaults.FLOATING_HANDLE)
                .putBoolean("showHeaderHz", PortableDefaults.SHOW_REFRESH_RATE)
                .putBoolean("showHeaderWifi", PortableDefaults.SHOW_WIFI)
                .putBoolean("showHeaderClock", PortableDefaults.SHOW_CLOCK)
                .putBoolean("showBatteryTime", PortableDefaults.SHOW_BATTERY_TIME)
                .putString(MetricModePolicy.CPU_PREF,PortableDefaults.CPU_METRIC_MODE)
                .putString(MetricModePolicy.GPU_PREF,PortableDefaults.GPU_METRIC_MODE)
                .apply();
        }
        marker.apply();
    }

    static boolean profileTemplateEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(APPLY_PROFILE_TEMPLATE, false);
    }

    private InstallDefaults() {}
}
