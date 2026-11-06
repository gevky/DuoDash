package dev.nova.deck;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Display;

/** Launcher and mandatory readiness flow for first install and recovery. */
public final class MainActivity extends Activity {
    private static final String STATE_STEP="wizard_step";
    private static final int REQUEST_NOTIFICATIONS=73;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean forced,resumed,requestPending,opened,stepInitialized,operationBusy;
    private int step;
    private String feedback="";
    private SetupReadiness.Status status;
    private SetupWizardView wizard;
    private SetupShortcutCapture shortcutCapture;
    private final Runnable poll=new Runnable(){@Override public void run(){if(resumed){refreshStatus();main.postDelayed(this,2500);}}};

    @Override public void onCreate(Bundle saved){super.onCreate(saved);InstallDefaults.initialize(this);forced=getIntent().getBooleanExtra("setup",false);if(saved!=null){step=Math.max(0,Math.min(2,saved.getInt(STATE_STEP,0)));stepInitialized=true;}wizard=new SetupWizardView(this,new WizardActions());setContentView(wizard);}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt(STATE_STEP,step);super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();resumed=true;refreshStatus();main.removeCallbacks(poll);main.postDelayed(poll,2500);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(poll);if(wizard!=null)wizard.stopCapture();stopShortcutCapture();SetupShortcutCapture.stopFor(this);super.onPause();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_NOTIFICATIONS){feedback=grantResults.length>0&&grantResults[0]==android.content.pm.PackageManager.PERMISSION_GRANTED?"Notifications are enabled for pairing fallback.":"Allow notifications before pairing.";refreshStatus();}}

    private void refreshStatus(){
        if(requestPending||opened)return;requestPending=true;
        SetupReadiness.refresh(getApplicationContext(),fresh->{
            requestPending=false;if(isFinishing()||isDestroyed())return;status=fresh;if(!resumed)return;
            if(status.paired&&status.liveHelperReady)feedback="";
            if(status.canShowDashboard()&&(!forced||status.recoveryMode())){
                openDashboard(forced&&status.recoveryMode());return;
            }
            if(!stepInitialized){
                if(forced)step=status.paired&&status.liveHelperReady?2:status.overlayAllowed&&status.accessibilityBound?1:0;
                else if(!status.overlayAllowed||!status.accessibilityEnabled||!status.accessibilityBound
                        ||status.needsPairing()&&!status.canStartPairing())step=0;
                else if(status.needsPairing()||!status.liveHelperReady)step=1;
                else step=2;
                stepInitialized=true;
            }
            render();
        });
    }
    private void render(){if(wizard!=null)wizard.render(status,step,feedback,operationBusy);}
    boolean isSetupRecordingForeground(){return resumed&&hasWindowFocus()&&!isFinishing()&&!isDestroyed();}
    private void stopShortcutCapture(){if(shortcutCapture!=null){shortcutCapture.stop();shortcutCapture=null;}}
    private void saveShortcut(String chord){
        String normalized=ShortcutMatcher.normalize(chord);if(normalized.isEmpty()){feedback="Choose a supported button combination and try again.";render();return;}
        android.content.SharedPreferences ui=getSharedPreferences("ui",MODE_PRIVATE);String existing=ShortcutMatcher.normalize(ui.getString("shortcuts",""));
        int[] keys=java.util.Arrays.stream(normalized.split(",")).mapToInt(Integer::parseInt).toArray();
        if(!ShortcutMatcher.canAdd(existing,keys)){feedback="That overlaps a saved shortcut. Choose a different button combination.";render();return;}
        ui.edit().putString("shortcuts",existing.isEmpty()?normalized:existing+";"+normalized).apply();
        if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();
        feedback="Shortcut saved.";refreshStatus();
    }
    private void openDashboard(){openDashboard(false);}
    private void openDashboard(boolean openConnection){if(opened)return;opened=true;String action=getIntent().getBooleanExtra("tile_toggle",false)?OverlayService.TOGGLE:OverlayService.SHOW;Intent service=new Intent(this,OverlayService.class).setAction(action).putExtra("open_connection",openConnection);startForegroundService(service);finish();}
    private boolean developerOptionsEnabled(){
        try{return Settings.Global.getInt(getContentResolver(),Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1;}
        catch(Exception ignored){return false;}
    }
    private void openAboutDevice(){
        try{startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));}
        catch(Exception error){feedback="Open Android Settings → About phone/device → tap Build number seven times, then return here.";render();}
    }
    private void openDeveloperOptions(){
        if(!developerOptionsEnabled()){openAboutDevice();return;}
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}
        catch(ActivityNotFoundException error){feedback="Open Android Settings → Developer options → Wireless debugging.";render();}
    }
    private void openNotificationSettings(){
        Intent intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
        try{startActivity(intent);}catch(Exception error){feedback="Open Android Settings → Apps → DuoDash → Notifications.";render();}
    }
    private void openAccessibilitySettings(){
        android.content.ComponentName service=new android.content.ComponentName(this,DeckAccessibilityService.class);
        Intent details=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME,service);
        try{
            // AOSP exposes this per-service page only to privileged callers. Try it when
            // the platform permits it, but always retain the public, supported fallback.
            if(details.resolveActivity(getPackageManager())!=null){startActivity(details);return;}
        }catch(Exception ignored){}
        try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
        catch(Exception error){feedback="Open Android Settings → Accessibility → Downloaded apps → DuoDash, then turn on the service.";render();}
    }
    private void openAppInfo(){
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        catch(Exception error){feedback="Open Android Settings → Apps → DuoDash → App info.";render();}
    }
    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            if(getPreferences(MODE_PRIVATE).getBoolean("notificationPermissionRequested",false)){openNotificationSettings();return;}
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationPermissionRequested",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);
        }else openNotificationSettings();
    }
    private void startPairingFlow(boolean rePair){
        if(status==null){refreshStatus();return;}
        if(!rePair&&!status.paired&&!status.notificationsPermissionGranted){
            feedback="Allow notifications before pairing.";render();requestNotificationPermission();return;
        }
        if(!developerOptionsEnabled()){
            feedback="First enable Developer options: open About phone/device and tap Build number seven times. Then return here to pair.";render();openAboutDevice();return;
        }
        boolean allowed=rePair?status.canPairAgain():status.canStartPairing();
        if(!allowed){feedback="This device already has a saved pairing. Use Reconnect or choose the recovery option for a new code.";render();return;}
        boolean notificationReady=BridgeActivationService.preparePairing(MainActivity.this);
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service==null||!status.accessibilityBound||!service.showPairingPanel()){
            if(notificationReady){feedback="The keypad could not be shown. Use the DuoDash pairing notification, then open Wireless debugging.";render();openPairingSettings();return;}
            feedback="The keypad could not be shown. Enable DuoDash under Accessibility, or enable notifications for the fallback.";render();return;
        }
        feedback=notificationReady
            ?"A keypad is open on the main screen. In Wireless debugging, choose Pair device with pairing code; the notification is an alternate reply path."
            :"A keypad is open on the main screen. In Wireless debugging, choose Pair device with pairing code.";
        render();main.postDelayed(this::openPairingSettings,300);
    }
    private void openPairingSettings(){
        Intent intent=new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Display target=null;
        try{
            DisplayManager manager=getSystemService(DisplayManager.class);
            if(manager!=null)for(Display display:manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
                if(display.getDisplayId()!=Display.DEFAULT_DISPLAY&&display.getState()==Display.STATE_ON){target=display;break;}
        }catch(Exception ignored){}
        try{
            if(target!=null&&Build.VERSION.SDK_INT>=26){
                ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(target.getDisplayId());
                startActivity(intent,options.toBundle());
            }else startActivity(intent);
        }catch(Exception first){
            try{startActivity(intent);}catch(Exception error){feedback="Open Android Settings → Developer options → Wireless debugging. The keypad remains open.";render();}
        }
    }

    private final class WizardActions implements SetupWizardView.Actions {
        @Override public void overlayPermission(){try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(Exception error){feedback="Allow DuoDash to display over other apps in Android Settings.";render();}}
        @Override public void accessibilitySettings(){openAccessibilitySettings();}
        @Override public void notificationPermission(){requestNotificationPermission();}
        @Override public void notificationSettings(){openNotificationSettings();}
        @Override public void pairThisDevice(){
            if(status==null){refreshStatus();return;}
            if(status.paired){BridgeActivationService.connect(MainActivity.this);feedback="Reconnecting to this device’s saved pairing…";render();return;}
            startPairingFlow(false);
        }
        @Override public void pairAgain(){startPairingFlow(true);}
        @Override public void reconnect(){BridgeActivationService.connect(MainActivity.this);feedback="Checking the saved pairing and system-controls helper…";render();}
        @Override public void developerOptions(){openDeveloperOptions();}
        @Override public void aboutDevice(){openAboutDevice();}
        @Override public void appInfo(){openAppInfo();}
        @Override public void beginShortcutCapture(SetupShortcutCapture.Listener listener){
            stopShortcutCapture();shortcutCapture=new SetupShortcutCapture(MainActivity.this,listener);
            if(!shortcutCapture.start()){shortcutCapture=null;listener.onError("Button recording needs the live system-controls helper. Reconnect and try again.");}
        }
        @Override public void endShortcutCapture(){stopShortcutCapture();}
        @Override public String savedShortcuts(){return ShortcutMatcher.normalize(getSharedPreferences("ui",MODE_PRIVATE).getString("shortcuts",""));}
        @Override public void saveShortcut(String chord){MainActivity.this.saveShortcut(chord);}
        @Override public void refresh(){feedback="Checking permissions and system controls…";render();refreshStatus();}
        @Override public void next(){if(wizard!=null)wizard.stopCapture();step=Math.min(2,step+1);feedback="";render();}
        @Override public void back(){if(step>0){if(wizard!=null)wizard.stopCapture();step--;feedback="";render();}}
        @Override public void finish(){
            if(requestPending||operationBusy)return;
            if(status==null||!status.canFinish()){feedback="Finish is available after all live system checks pass.";render();return;}
            operationBusy=true;feedback="Confirming the live helper and permissions…";render();requestPending=true;
            SetupReadiness.refresh(getApplicationContext(),fresh->{requestPending=false;operationBusy=false;if(isFinishing()||isDestroyed()||!resumed)return;status=fresh;
                if(!status.canFinish()||!SetupReadiness.complete(MainActivity.this,status)){feedback="A permission or system check changed. Review the checks and try again.";render();return;}
                openDashboard();
            });
        }
    }
}
