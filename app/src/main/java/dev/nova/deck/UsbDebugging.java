package dev.nova.deck;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

final class UsbDebugging {
    private UsbDebugging() {}

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    static void ensureEnabled(Context context) throws Exception {
        boolean canWrite = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        try {
            UsbDebuggingPolicy.ensureEnabled(canWrite, new UsbDebuggingPolicy.Operations() {
                public int read() {
                    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
                }
                public boolean enable() {
                    return Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                }
            });
        } catch (SecurityException blocked) {
            throw new IllegalStateException("Turn on USB debugging in Developer options to keep controls working with Wi-Fi off.", blocked);
        }
    }
}
