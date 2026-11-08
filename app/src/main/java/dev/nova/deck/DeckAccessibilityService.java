package dev.nova.deck;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;

/** Trusted local overlay and explicitly configured key shortcuts; no screen content retrieval. */
public final class DeckAccessibilityService extends AccessibilityService {
    static DeckAccessibilityService connected;
    interface Recorder{void event(KeyEvent event);}
    Recorder recorder;
    private volatile Recorder wizardRecorder;
    private volatile MainActivity wizardRecordingActivity;
    private PairingCodePanel pairingPanel;
    private WindowManager pairingWindows;
    private WindowManager.LayoutParams pairingParams;
    private final Handler pairingHandler=new Handler(Looper.getMainLooper());
    private final Runnable expirePairingPanel=()->dismissPairingPanel();
    private Runnable successDismissPairingPanel;
    private long pairingPanelSession;
    boolean nativeGamepad;
    int navigationEvents;
    private final java.util.Set<Integer> dashboardKeys=new java.util.HashSet<>();
    private ShortcutMatcher shortcuts=new ShortcutMatcher("");
    void reloadShortcuts(){android.content.SharedPreferences prefs=getSharedPreferences("ui",MODE_PRIVATE);shortcuts=new ShortcutMatcher(prefs.getString("shortcuts",""),prefs.getString(ShortcutBindings.PREF,""));}
    static boolean beginWizardRecording(MainActivity activity,Recorder callback){
        DeckAccessibilityService service=connected;
        if(service==null||activity==null||callback==null||!activity.isSetupRecordingForeground())return false;
        service.wizardRecordingActivity=activity;service.wizardRecorder=callback;return true;
    }
    static void endWizardRecording(MainActivity activity,Recorder callback){
        DeckAccessibilityService service=connected;
        if(service!=null&&service.wizardRecordingActivity==activity&&service.wizardRecorder==callback){service.wizardRecorder=null;service.wizardRecordingActivity=null;}
    }
    private boolean dispatchWizardRecorder(KeyEvent event){
        Recorder target=wizardRecorder;MainActivity owner=wizardRecordingActivity;
        if(target==null)return false;
        if(owner==null||!owner.isSetupRecordingForeground()){wizardRecorder=null;wizardRecordingActivity=null;return false;}
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_POWER||code==KeyEvent.KEYCODE_UNKNOWN)return false;
        target.event(event);return true;
    }
    void onWizardGamepadEvent(int keyCode,int action){
        if(action!=KeyEvent.ACTION_DOWN&&action!=KeyEvent.ACTION_UP)return;
        dispatchWizardRecorder(new KeyEvent(action,keyCode));
    }
    boolean showPairingPanel(){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return false;
        return showPairingPanelMain();
    }
    private boolean showPairingPanelMain(){
        if(!SetupReadiness.isAccessibilityServiceEnabled(this)||connected!=this)return false;
        try{
            if(pairingPanel!=null&&pairingPanel.isAttachedToWindow())return true;
            android.view.Display main=((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(0);
            if(main==null)return false;
            Context windowContext=createWindowContext(main,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,null);
            pairingWindows=(WindowManager)windowContext.getSystemService(WINDOW_SERVICE);
            if(pairingWindows==null)return false;
            float density=windowContext.getResources().getDisplayMetrics().density;
            int width=Math.min(Math.round(310*density),Math.round(pairingWindows.getMaximumWindowMetrics().getBounds().width()-16*density));
            final long session=++pairingPanelSession;
            pairingPanel=new PairingCodePanel(windowContext,new PairingCodePanel.Host(){
                @Override public void submit(String reply){BridgeActivationService.submitPanelPairing(DeckAccessibilityService.this,reply,session);}
                @Override public void cancel(){dismissPairingPanel();}
                @Override public void dragBy(float dx,float dy){movePairingPanel(dx,dy);}
            });
            pairingParams=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            pairingParams.gravity=Gravity.TOP|Gravity.LEFT;pairingParams.x=Math.round(12*density);pairingParams.y=Math.round(36*density);
            pairingParams.setTitle("DuoDash pairing keypad");
            pairingWindows.addView(pairingPanel,pairingParams);
            pairingHandler.removeCallbacks(expirePairingPanel);pairingHandler.postDelayed(expirePairingPanel,5*60_000L);
            return true;
        }catch(Exception error){dismissPairingPanel();return false;}
    }
    private void movePairingPanel(float dx,float dy){
        if(pairingPanel==null||pairingWindows==null||pairingParams==null)return;
        try{
            Rect bounds=pairingWindows.getMaximumWindowMetrics().getBounds();
            int maxX=Math.max(0,bounds.width()-pairingPanel.getWidth()),maxY=Math.max(0,bounds.height()-pairingPanel.getHeight());
            pairingParams.x=Math.max(0,Math.min(maxX,pairingParams.x+Math.round(dx)));
            pairingParams.y=Math.max(0,Math.min(maxY,pairingParams.y+Math.round(dy)));
            pairingWindows.updateViewLayout(pairingPanel,pairingParams);
        }catch(Exception ignored){}
    }
    void pairingPanelResult(long session,boolean success,String detail){
        if(pairingPanel==null||session!=pairingPanelSession)return;
        pairingPanel.showResult(success,detail);
        if(success){
            if(successDismissPairingPanel!=null)pairingHandler.removeCallbacks(successDismissPairingPanel);
            successDismissPairingPanel=()->{if(session==pairingPanelSession)dismissPairingPanel();};
            pairingHandler.postDelayed(successDismissPairingPanel,1200);
        }
    }
    void pairingPanelConnected(){
        if(pairingPanel!=null)pairingPanelResult(pairingPanelSession,true,"System controls connected.");
    }
    void dismissPairingPanel(){
        pairingHandler.removeCallbacks(expirePairingPanel);
        if(successDismissPairingPanel!=null){pairingHandler.removeCallbacks(successDismissPairingPanel);successDismissPairingPanel=null;}
        pairingPanelSession++;
        if(pairingPanel!=null){pairingPanel.clearSensitive();try{if(pairingWindows!=null)pairingWindows.removeView(pairingPanel);}catch(Exception ignored){}pairingPanel=null;}
        pairingParams=null;pairingWindows=null;
    }
    @Override protected void onServiceConnected(){
        super.onServiceConnected();connected=this;
        reloadShortcuts();
        if(OverlayService.current!=null)OverlayService.current.reconnectWindows();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        boolean debuggable=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        boolean systemUiWindow=SystemUiShadePolicy.shouldProbe(event.getPackageName(),event.getEventType());
        if(debuggable&&systemUiWindow){
            // Window metadata only; do not log text, descriptions, or node contents.
            android.util.Log.i("NovaDeckA11yProbe","window_state pkg=com.android.systemui class="
                +event.getClassName()+" id="+event.getWindowId()+" changes="+event.getContentChangeTypes()
                +" fullscreen="+event.isFullScreen());
        }
        // Event content is never read; only focused SystemUI events request a shade check.
        if(OverlayService.current!=null){
            OverlayService.current.windowChanged();
            OverlayService.current.accessibilityWindowPackage(event.getPackageName());
            if(systemUiWindow)OverlayService.current.systemUiWindowStateChanged(event.getClassName());
            else OverlayService.current.otherWindowStateChanged();
        }
    }
    @Override public void onInterrupt(){ }
    @Override protected boolean onKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(DashboardInputPolicy.navigationKey(code))navigationEvents++;
        if(dispatchWizardRecorder(event))return true;
        if(!SetupReadiness.hasCompletedSetup(this))return false;
        android.view.InputDevice source=event.getDevice();
        boolean nativeSource=nativeGamepad&&source!=null&&"Retroid Pocket Controller".equals(source.getName());
        if(!nativeSource&&recorder!=null&&code!=KeyEvent.KEYCODE_HOME&&code!=KeyEvent.KEYCODE_POWER&&code!=KeyEvent.KEYCODE_UNKNOWN){recorder.event(event);return true;}
        // The native monitor owns internal-controller shortcuts. Other controllers
        // must match their configured shortcut before A/B or D-pad navigation.
        if(!nativeSource){
            OverlayService overlayForCapture=OverlayService.current;
            boolean editingWithoutRecorder=recorder==null&&overlayForCapture!=null&&overlayForCapture.inlineShortcutEditor!=null&&overlayForCapture.inlineShortcutEditor.isRecording();
            if(!editingWithoutRecorder){
                ShortcutMatcher.Result result=shortcuts.event(code,event.getAction()==KeyEvent.ACTION_DOWN,event.getRepeatCount()>0);
                if(result.action!=null)OverlayService.dispatchShortcut(this,result.action,"accessibility");
                if(result.consume){if(event.getAction()==KeyEvent.ACTION_UP)dashboardKeys.remove(code);return true;}
            }
        }
        OverlayService overlay=OverlayService.current;
        if(event.getAction()==KeyEvent.ACTION_UP&&dashboardKeys.remove(code)){
            if(overlay!=null&&recorder==null)overlay.routeDashboardKey(event);
            return true;
        }
        if(recorder==null&&overlay!=null&&overlay.routeDashboardKey(event)){
            if(event.getAction()==KeyEvent.ACTION_DOWN)dashboardKeys.add(code);
            return true;
        }
        return false;
    }

    @Override public void onDestroy(){
        dismissPairingPanel();
        connected=null;
        if(OverlayService.current!=null)OverlayService.current.reconnectWindows();
        super.onDestroy();
    }
}
