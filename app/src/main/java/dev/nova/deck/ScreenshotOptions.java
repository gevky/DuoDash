package dev.nova.deck;

import android.content.Context;
import android.content.SharedPreferences;

/** Shared screenshot choices used by the dashboard gesture and settings page. */
public final class ScreenshotOptions {
    private static final String PREFS="ui";
    private static final String TARGET="screenshotTarget";
    private static final String INCLUDE_DUODASH="screenshotIncludeDuoDash";

    private ScreenshotOptions(){}

    public static String target(Context context){
        return ScreenshotTargetPolicy.normalize(preferences(context).getString(TARGET,ScreenshotTargetPolicy.MAIN));
    }

    public static String cycleTarget(Context context){
        String next=ScreenshotTargetPolicy.cycle(target(context));
        preferences(context).edit().putString(TARGET,next).apply();
        return next;
    }

    public static void setTarget(Context context,String value){
        preferences(context).edit().putString(TARGET,ScreenshotTargetPolicy.normalize(value)).apply();
    }

    public static boolean includeDuoDash(Context context){
        return preferences(context).getBoolean(INCLUDE_DUODASH,false);
    }

    public static void setIncludeDuoDash(Context context,boolean include){
        preferences(context).edit().putBoolean(INCLUDE_DUODASH,include).apply();
    }

    private static SharedPreferences preferences(Context context){
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
}
