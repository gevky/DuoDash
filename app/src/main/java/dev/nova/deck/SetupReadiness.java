package dev.nova.deck;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import java.util.function.Consumer;

/** Real permission, pairing, and helper checks shared by every dashboard entry point. */
public final class SetupReadiness {
    public static final int CURRENT_SCHEMA_VERSION=2;
    private static final String PREFS="setup_readiness";
    private static final String COMPLETED_SCHEMA="completed_schema";

    private SetupReadiness(){}

    public static final class Status {
        public final boolean overlayAllowed;
        public final boolean accessibilityEnabled;
        public final boolean accessibilityBound;
        public final boolean paired;
        public final boolean persistentReconnectGranted;
        public final boolean liveHelperReady;
        public final boolean notificationsPermissionGranted;
        public final boolean notificationsEnabled;
        public final boolean pairingChannelEnabled;
        public final boolean shortcutConfigured;
        public final boolean floatingButtonEnabled;
        public final int completedVersion;

        public Status(boolean overlayAllowed,boolean accessibilityEnabled,boolean accessibilityBound,
                boolean paired,boolean persistentReconnectGranted,boolean liveHelperReady,
                boolean notificationsPermissionGranted,boolean notificationsEnabled,
                boolean pairingChannelEnabled,boolean shortcutConfigured,boolean floatingButtonEnabled,
                int completedVersion){
            this.overlayAllowed=overlayAllowed;this.accessibilityEnabled=accessibilityEnabled;
            this.accessibilityBound=accessibilityBound;this.paired=paired;
            this.persistentReconnectGranted=persistentReconnectGranted;this.liveHelperReady=liveHelperReady;
            this.notificationsPermissionGranted=notificationsPermissionGranted;
            this.notificationsEnabled=notificationsEnabled;this.pairingChannelEnabled=pairingChannelEnabled;
            this.shortcutConfigured=shortcutConfigured;this.floatingButtonEnabled=floatingButtonEnabled;
            this.completedVersion=completedVersion;
        }
        /** Compatibility constructor for callers that predate the required shortcut. */
        public Status(boolean overlayAllowed,boolean accessibilityEnabled,boolean accessibilityBound,
                boolean paired,boolean persistentReconnectGranted,boolean liveHelperReady,
                boolean notificationsPermissionGranted,boolean notificationsEnabled,
                boolean pairingChannelEnabled,int completedVersion){
            this(overlayAllowed,accessibilityEnabled,accessibilityBound,paired,persistentReconnectGranted,liveHelperReady,
                notificationsPermissionGranted,notificationsEnabled,pairingChannelEnabled,true,true,completedVersion);
        }
        public boolean canFinish(){
            return overlayAllowed&&accessibilityEnabled&&accessibilityBound&&paired
                &&persistentReconnectGranted&&liveHelperReady&&shortcutConfigured;
        }
        public boolean hasLocalDashboardPrerequisites(){
            return overlayAllowed&&accessibilityEnabled&&accessibilityBound&&shortcutConfigured;
        }
        /** A completed install may open offline; Connection settings repairs pair/helper state. */
        public boolean canShowDashboard(){return isCompleted()&&hasLocalDashboardPrerequisites();}
        public boolean needsSetup(){return !canShowDashboard();}
        public boolean recoveryMode(){return isCompleted()&&(!paired||!persistentReconnectGranted||!liveHelperReady);}
        public boolean needsPairing(){return !paired;}
        /** The in-app keypad is the primary path; notifications are an optional fallback. */
        public boolean canStartPairing(){return !paired;}
        public boolean canPairAgain(){return paired;}
        public boolean canUsePairingNotification(){return pairingNotificationsAvailable();}
        private boolean pairingNotificationsAvailable(){return notificationsPermissionGranted&&notificationsEnabled&&pairingChannelEnabled;}
        public boolean isCompleted(){return completedVersion>=CURRENT_SCHEMA_VERSION;}
        public boolean canReconnectInBackground(){return isCompleted()&&paired;}
    }

    /** Setup is mandatory until completion and local overlay/accessibility/shortcut requirements hold. */
    public static boolean shouldRouteToSetup(Status status){return status==null||!status.canShowDashboard();}

    /** Fast preference-only guard for accessibility key events; avoids consuming keys before Finish. */
    public static boolean hasCompletedSetup(Context context){
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getInt(COMPLETED_SCHEMA,0)>=CURRENT_SCHEMA_VERSION;
    }

    /** Collects local grants synchronously; helper readiness is supplied by the live status probe. */
    public static Status inspect(Context context,boolean liveHelperReady){
        Context app=context.getApplicationContext();
        boolean overlay=Settings.canDrawOverlays(app);
        boolean accessEnabled=isAccessibilityServiceEnabled(app);
        boolean accessBound=DeckAccessibilityService.connected!=null;
        boolean paired=OnDeviceBridge.isPaired(app);
        boolean persistent=app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)==PackageManager.PERMISSION_GRANTED;
        boolean postPermission=Build.VERSION.SDK_INT<33||app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        NotificationManager manager=app.getSystemService(NotificationManager.class);
        boolean notifications=manager!=null&&manager.areNotificationsEnabled();
        boolean pairChannel=true;
        if(Build.VERSION.SDK_INT>=26&&manager!=null){
            NotificationChannel channel=manager.getNotificationChannel(BridgeActivationService.PAIR_CHANNEL);
            pairChannel=channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
        }
        int completed=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(COMPLETED_SCHEMA,0);
        android.content.SharedPreferences ui=app.getSharedPreferences("ui",Context.MODE_PRIVATE);
        boolean shortcutConfigured=ShortcutMatcher.hasConfiguredToggle(ui.getString("shortcuts",""));
        boolean floatingButton=ui.getBoolean("floatingButton",true);
        return new Status(overlay,accessEnabled,accessBound,paired,persistent,liveHelperReady,
            postPermission,notifications,pairChannel,shortcutConfigured,floatingButton,completed);
    }

    /** Probes the authenticated current helper off-main and returns the snapshot on the UI thread. */
    public static void refresh(Context context,Consumer<Status> callback){
        Context app=context.getApplicationContext();
        new Thread(()->{
            boolean helperReady=false;
            if(OnDeviceBridge.isPaired(app)){
                try{helperReady=new BridgeClient(app).isHealthy();}
                catch(Exception ignored){}
            }
            Status status=inspect(app,helperReady);
            if(callback!=null)new Handler(Looper.getMainLooper()).post(()->callback.accept(status));
        },"duodash-readiness").start();
    }

    /** Saves completion only after all current prerequisites pass; the stamp never bypasses future checks. */
    public static boolean complete(Context context,Status status){
        if(status==null||!status.canFinish())return false;
        Status current=inspect(context,status.liveHelperReady);
        if(!current.canFinish())return false;
        context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putInt(COMPLETED_SCHEMA,CURRENT_SCHEMA_VERSION).apply();
        return true;
    }

    public static boolean isAccessibilityServiceEnabled(Context context){
        try{
            if(Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)!=1)return false;
            String enabled=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(TextUtils.isEmpty(enabled))return false;
            ComponentName own=new ComponentName(context,DeckAccessibilityService.class);
            for(String entry:enabled.split(":")){
                ComponentName component=ComponentName.unflattenFromString(entry);
                if(component!=null&&own.getPackageName().equals(component.getPackageName())
                        &&own.getClassName().equals(component.getClassName()))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
}
