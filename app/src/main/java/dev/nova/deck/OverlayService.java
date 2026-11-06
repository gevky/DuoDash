package dev.nova.deck;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import android.view.inputmethod.InputMethodManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;

public final class OverlayService extends Service implements DeckController.Host {
    static OverlayService current;
    // Temporary recording build setting. Restore to true after the capture session if
    // keeping DuoDash surfaces out of Android screenshots and mirrored outputs again.
    private static final boolean EXCLUDE_CONTROLS_FROM_CAPTURE=true;
    private static final String ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG="com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG";
    private static final String ACTION_SETTINGS_MEDIA_OUTPUT="com.android.settings.panel.action.MEDIA_OUTPUT";
    private static final String EXTRA_MEDIA_OUTPUT_PACKAGE="package_name";
    private static final String EXTRA_SETTINGS_MEDIA_OUTPUT_PACKAGE="com.android.settings.panel.extra.PACKAGE_NAME";
    private static final String PACKAGE_SYSTEM_UI="com.android.systemui";
    public static final String SHOW="dev.nova.deck.SHOW", RESET="dev.nova.deck.RESET", STOP="dev.nova.deck.STOP";
    public static final String TOGGLE="dev.nova.deck.TOGGLE";
    private static final String STOP_RECORDING="dev.nova.deck.STOP_RECORDING";
    private WindowManager windows;
    private Context uiContext;
    private WindowManager mainWindows;
    private Context mainUiContext;
    private int mainWindowType=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private WindowManager panelWindows;
    private Context panelUiContext;
    private int panelWindowType=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private DeckController controller;
    private DashboardView panel;
    private FrameLayout panelRoot;
    private SettingsPageView settingsPage;
    InlineShortcutEditor inlineShortcutEditor;
    private String settingsCategory="output";
    private View mainBlank;
    private HandleView handle;
    private WindowManager.LayoutParams handleParams;
    private boolean panelShowing;
    private boolean dashboardControllerInput;
    private boolean controllerEntryRequested;
    private AlertDialog activeDialog;
    private ScreenshotCapture screenshotCapture;
    boolean settingsOpen;
    private boolean settingsReadinessKnown,settingsLastBridge,settingsLastAccessibilityEnabled,settingsLastAccessibilityBound,settingsRefreshPending;
    private TextView connectionStatusView,connectionHelpView;
    private Button restoreControlsButton;
    private boolean restoreRequested,activationLaunched,wifiPanelOpen,wifiPanelSeenSettings,autoRecoveryAttempted;
    private String restoreFailure="";
    private final Handler resolutionUiHandler=new Handler(Looper.getMainLooper());
    private String resolutionUiTransaction="";
    private long resolutionUiDeadline;
    private int windowType=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private long lastShortcutToggle;
    private boolean hasShortcutToggle;
    private final HiddenSurfaceTouchPolicy.Gesture hiddenSurfaceTouchGesture=new HiddenSurfaceTouchPolicy.Gesture();
    private boolean recordingNotification;
    private boolean readinessCheckPending;
    private boolean systemUiShadeQueryInFlight,systemUiShadeQueryPending,systemUiShadePendingYield,systemUiShadePendingRestore,systemShadeOwnsHandle;
    private int systemShadeDisplay=-1;
    private boolean panelImeVisible;
    private boolean handleImeVisible;
    private int lastImeGeometryHash;
    private boolean dashboardReady;
    private boolean backgroundRebindPending;
    private String pendingEntryAction;
    private final Runnable stopBackgroundRebind=new Runnable(){@Override public void run(){
        if(backgroundRebindPending){backgroundRebindPending=false;stopForeground(true);stopSelf();}
    }};

    @Override public void dump(FileDescriptor fd,PrintWriter writer,String[] args){
        if(args==null||!Arrays.asList(args).contains("ui")||(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0){super.dump(fd,writer,args);return;}
        try{
            View dialog=activeDialog==null||activeDialog.getWindow()==null?null:activeDialog.getWindow().getDecorView();
            int[] count={0};
            writer.println(new org.json.JSONObject().put("service","OverlayService").put("panelShowing",panelShowing)
                .put("settingsOpen",settingsOpen).put("imeVisible",panelImeVisible).put("externalTarget",controller!=null&&controller.panelTargetsExternal()).put("controllerInput",dashboardControllerInput).put("navigationEvents",DeckAccessibilityService.connected==null?-1:DeckAccessibilityService.connected.navigationEvents).put("panel",debugUiTree(panelRoot,count,0)).put("dialog",debugUiTree(dialog,count,0)));
        }catch(Exception ignored){writer.println("{\"error\":\"DuoDash view inspection unavailable\"}");}
    }
    private static Object debugUiTree(View view,int[] count,int depth)throws org.json.JSONException{
        if(view==null||!view.isAttachedToWindow())return org.json.JSONObject.NULL;
        if(count[0]++>=1500||depth>32)return new org.json.JSONObject().put("truncated",true);
        int[] position=new int[2];view.getLocationOnScreen(position);
        org.json.JSONObject node=new org.json.JSONObject().put("class",view.getClass().getName())
            .put("bounds",new org.json.JSONArray().put(position[0]).put(position[1]).put(view.getWidth()).put(view.getHeight()))
            .put("visibility",view.getVisibility()==View.VISIBLE?"visible":view.getVisibility()==View.GONE?"gone":"invisible")
            .put("enabled",view.isEnabled()).put("clickable",view.isClickable()).put("focused",view.isFocused())
            .put("contentDescription",view instanceof EditText?"[redacted]":safeDebugText(view.getContentDescription()));
        if(view instanceof TextView)node.put("text",view instanceof EditText?"[redacted]":safeDebugText(((TextView)view).getText())).put("textSizeSp",((TextView)view).getTextSize()/view.getResources().getDisplayMetrics().scaledDensity);
        if(view instanceof EditText)node.put("hint",safeDebugText(((EditText)view).getHint()));
        if(view instanceof Checkable)node.put("checked",((Checkable)view).isChecked());
        org.json.JSONArray children=new org.json.JSONArray();
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                children.put(debugUiTree(group.getChildAt(i),count,depth+1));
                if(count[0]>=1500){if(i+1<group.getChildCount())children.put(new org.json.JSONObject().put("truncated",true));break;}
            }
        }
        return node.put("children",children);
    }
    private static String safeDebugText(CharSequence value){
        if(value==null)return "";String text=value.toString();
        if(text.matches("(?i)[a-f0-9]{32,}")||text.matches("[A-Za-z0-9_=-]{48,}"))return "[redacted]";
        return text.length()>160?text.substring(0,157)+"...":text;
    }

    @Override public void onCreate() {
        super.onCreate();
        current=this;
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel("controls","DuoDash controls",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Background controls. You can hide this notification without stopping DuoDash."); channel.setShowBadge(false); channel.setSound(null,null); channel.enableVibration(false);
        notifications.createNotificationChannel(channel);
        startForeground(41,controlsNotification(false));
    }
    private void chooseWindowContext(){
        Display display=((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(0);
        if(display==null)throw new IllegalStateException("The built-in display is unavailable.");
        if(DeckAccessibilityService.connected!=null){
            // AccessibilityService contexts follow the display currently focused by
            // Android. Bind every control surface to the built-in display explicitly,
            // so a resumed upper-screen MirrorActivity cannot move its touch target.
            // A display context alone is not a UI context and does not carry the
            // accessibility overlay token. Create the typed window context through
            // AccessibilityService so Android attaches its per-display token.
            windowType=WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            uiContext=DeckAccessibilityService.connected.createWindowContext(display,windowType,null);
            windows=(WindowManager)uiContext.getSystemService(WINDOW_SERVICE);
        }else{
            uiContext=getApplicationContext().createDisplayContext(display);
            if(Build.VERSION.SDK_INT>=30)uiContext=uiContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            windows=(WindowManager)uiContext.getSystemService(WINDOW_SERVICE);
            windowType=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        mainUiContext=uiContext;mainWindows=windows;mainWindowType=windowType;
    }
    private void choosePanelWindowContext(){
        int displayId=controller==null?0:controller.panelWindowDisplayId();
        Display display=((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(displayId);
        if(display==null)throw new IllegalStateException("The selected display is unavailable.");
        int type=DeckAccessibilityService.connected!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        Context windowContext;
        if(DeckAccessibilityService.connected!=null)windowContext=DeckAccessibilityService.connected.createWindowContext(display,type,null);
        else{
            windowContext=getApplicationContext().createDisplayContext(display);
            if(Build.VERSION.SDK_INT>=30)windowContext=windowContext.createWindowContext(type,null);
        }
        WindowManager manager=(WindowManager)windowContext.getSystemService(WINDOW_SERVICE);
        Context viewContext=windowContext;
        if(controller!=null&&controller.panelTargetsExternal()&&Build.VERSION.SDK_INT>=24){
            Display densityDisplay=((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(controller.panelDensityDisplayId());
            DisplayMetrics metrics=new DisplayMetrics();(densityDisplay==null?display:densityDisplay).getRealMetrics(metrics);
            int shortPixels=Math.min(metrics.widthPixels,metrics.heightPixels);
            android.content.res.Configuration configuration=new android.content.res.Configuration(windowContext.getResources().getConfiguration());
            configuration.densityDpi=Math.max(160,Math.round(shortPixels*160f/480f));
            viewContext=new WindowBoundContext(windowContext,manager).createConfigurationContext(configuration);
        }
        panelWindows=manager;panelWindowType=type;panelUiContext=viewContext;windows=manager;windowType=type;uiContext=viewContext;
    }
    private static final class WindowBoundContext extends ContextWrapper {
        private final WindowManager manager;
        WindowBoundContext(Context base,WindowManager manager){super(base);this.manager=manager;}
        @Override public Object getSystemService(String name){return Context.WINDOW_SERVICE.equals(name)?manager:super.getSystemService(name);}
        @Override public Context createConfigurationContext(android.content.res.Configuration config){return new WindowBoundContext(super.createConfigurationContext(config),manager);}
    }
    void reconnectWindows(){
        boolean reopen=panelShowing||activeDialog!=null;
        boolean reopenSettings=settingsOpen;String reopenCategory=settingsCategory;
        clearSystemShadeOwnership();
        restoreMainScreen();
        clearRecording();
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        closePanel();
        if(handle!=null){try{mainWindows.removeView(handle);}catch(IllegalArgumentException ignored){}handle=null;}
        if(controller==null){chooseWindowContext();if(backgroundRebindPending&&DeckAccessibilityService.connected!=null)requestReadyEntry("background_rebind");return;}
        requestReadyEntry(reopenSettings?"settings":reopen?SHOW:"rebind");
        settingsCategory=reopenCategory;
    }
    int dp(float value){return Math.round(value*uiContext.getResources().getDisplayMetrics().density);}
    Context dialogContext(){return new ContextThemeWrapper(panelUiContext==null?mainUiContext:panelUiContext,R.style.DeckDialogTheme);}
    private boolean floatingEnabled(){return getSharedPreferences("ui",MODE_PRIVATE).getBoolean("floatingButton",true);}
    private Rect mainDisplayBounds(){
        if(Build.VERSION.SDK_INT>=30){
            Rect bounds=windows.getMaximumWindowMetrics().getBounds();
            if(bounds.width()>0&&bounds.height()>0)return bounds;
        }
        int target=controller==null?0:controller.panelWindowDisplayId();
        Display display=((DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(target);
        DisplayMetrics metrics=new DisplayMetrics();
        if(display!=null)display.getRealMetrics(metrics);
        return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }
    private boolean isExternalTouchscreen(MotionEvent event){
        InputDevice device=InputDevice.getDevice(event.getDeviceId());
        return HiddenSurfaceTouchPolicy.isExternalTouchscreen(
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN),device!=null&&device.isExternal());
    }
    private HiddenSurfaceTouchPolicy.Phase touchPhase(int action){
        if(action==MotionEvent.ACTION_DOWN)return HiddenSurfaceTouchPolicy.Phase.DOWN;
        if(action==MotionEvent.ACTION_UP)return HiddenSurfaceTouchPolicy.Phase.UP;
        if(action==MotionEvent.ACTION_CANCEL)return HiddenSurfaceTouchPolicy.Phase.CANCEL;
        return HiddenSurfaceTouchPolicy.Phase.CONTINUE;
    }
    private boolean consumeHiddenExternalTouch(MotionEvent event,boolean visibleOnExternal,String surface){
        boolean externalConnected=controller!=null&&controller.state.externalConnected;
        int action=event.getActionMasked();
        boolean consume=hiddenSurfaceTouchGesture.shouldConsume(event.getDeviceId(),touchPhase(action),isExternalTouchscreen(event),
            externalConnected,visibleOnExternal);
        if(consume&&action==MotionEvent.ACTION_DOWN){
            android.util.Log.i("NovaDeck","Consumed external touchscreen on hidden surface device="+event.getDeviceId()+
                " surface="+surface+" target="+(controller==null?0:controller.panelWindowDisplayId()));
        }
        return consume;
    }
    private final class GuardedFrameLayout extends FrameLayout {
        private final boolean visibleOnExternal;
        private final String surface;
        GuardedFrameLayout(Context context,boolean visibleOnExternal,String surface){super(context);this.visibleOnExternal=visibleOnExternal;this.surface=surface;}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(consumeHiddenExternalTouch(event,visibleOnExternal,surface))return true;
            return super.dispatchTouchEvent(event);
        }
    }
    private final class GuardedView extends View {
        private final boolean visibleOnExternal;
        private final String surface;
        GuardedView(Context context,boolean visibleOnExternal,String surface){super(context);this.visibleOnExternal=visibleOnExternal;this.surface=surface;}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(consumeHiddenExternalTouch(event,visibleOnExternal,surface))return true;
            return super.dispatchTouchEvent(event);
        }
    }
    private Window.Callback guardDialogCallback(Window.Callback original,boolean visibleOnExternal){
        if(original==null)return null;
        return (Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},(proxy,method,args)->{
            if("dispatchTouchEvent".equals(method.getName())&&args!=null&&args.length==1&&args[0] instanceof MotionEvent){
                MotionEvent event=(MotionEvent)args[0];
                if(consumeHiddenExternalTouch(event,visibleOnExternal,"dialog"))return true;
            }
            try{return method.invoke(original,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
    }
    private void addHandle() {
        if(mainWindows==null||mainUiContext==null)chooseWindowContext();
        handle=new HandleView(mainUiContext);
        handleParams=new WindowManager.LayoutParams(Math.round(122*mainUiContext.getResources().getDisplayMetrics().density),Math.round(44*mainUiContext.getResources().getDisplayMetrics().density),mainWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        setReceiveInsetsIgnoringZOrder(handleParams);
        float mainDensity=mainUiContext.getResources().getDisplayMetrics().density;handleParams.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;handleParams.y=Math.round(30*mainDensity);
        handleParams.setTitle("DuoDash handle");
        mainWindows.addView(handle,handleParams);
        handle.setOnApplyWindowInsetsListener((view,insets)->{
            boolean visible=Build.VERSION.SDK_INT>=30&&insets.isVisible(WindowInsets.Type.ime());
            if(handleImeVisible!=visible){handleImeVisible=visible;syncHandleVisibility();}
            return insets;
        });
        handle.requestApplyInsets();
        localScreenOnly(handle);
        handle.setOnClickListener(v->requestReadyEntry(TOGGLE));
        syncHandleVisibility();
    }
    private void syncHandleVisibility(){
        if(handle==null)return;
        boolean show=PanelImeViewportPolicy.showHandle(floatingEnabled(),panelShowing,systemShadeOwnsHandle,handleImeVisible);
        handle.setVisibility(show?View.VISIBLE:View.GONE);
    }
    private void clearSystemShadeOwnership(){
        systemShadeOwnsHandle=false;systemShadeDisplay=-1;syncHandleVisibility();
    }
    private void setReceiveInsetsIgnoringZOrder(WindowManager.LayoutParams params){
        try{
            java.lang.reflect.Field target=null;
            for(java.lang.reflect.Field field:org.lsposed.hiddenapibypass.HiddenApiBypass.getInstanceFields(params.getClass()))
                if("receiveInsetsIgnoringZOrder".equals(field.getName())){target=field;break;}
            if(target==null)throw new NoSuchFieldException("receiveInsetsIgnoringZOrder");
            target.setAccessible(true);target.setBoolean(params,true);
        }catch(Throwable error){
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
                android.util.Log.e("NovaDeckIme","operation=receive-insets-ignoring-z-order failed="+error.getClass().getSimpleName());
        }
    }
    private void showPanel() {
        if(controller==null||panelShowing||activeDialog!=null||!android.provider.Settings.canDrawOverlays(this))return;
        if(SetupReadiness.shouldRouteToSetup(SetupReadiness.inspect(this,dashboardReady))){requestReadyEntry(SHOW);return;}
        panelImeVisible=handleImeVisible;
        try{choosePanelWindowContext();}catch(Exception error){message("The selected display is unavailable");return;}
        if(handle==null&&floatingEnabled())addHandle();
        Rect bounds=mainDisplayBounds();
        boolean full=controller.state.fullScreen;
        int width=full?WindowManager.LayoutParams.MATCH_PARENT:Math.min(dp(620),bounds.width()-dp(24));
        int height=full?WindowManager.LayoutParams.MATCH_PARENT:Math.min(dp(406),bounds.height()-dp(68));
        panel=new DashboardView(uiContext,controller);
        panel.render(controller.state);
        boolean controllerTarget=settingsOpen||(!touchOnlyEnabled()&&(controller.panelTargetsExternal()||controllerEntryRequested));
        int panelFlags=PanelImePolicy.flags(controllerTarget)|(full?0:WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                |(full?WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS:0);
        if(panelImeVisible)panelFlags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(width,height,windowType,panelFlags,
            PixelFormat.TRANSLUCENT);
        if(full)params.setFitInsetsTypes(0);
        setReceiveInsetsIgnoringZOrder(params);
        params.gravity=full?Gravity.TOP|Gravity.LEFT:Gravity.CENTER;params.y=full?0:-dp(8);params.setTitle("DuoDash control center");
        if(full){params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            panel.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);}
        panelRoot=new GuardedFrameLayout(uiContext,controller.includePanelInMirror(),"panel");
        panelRoot.setBackgroundColor(Color.TRANSPARENT);
        panelRoot.addView(panel,new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
        panelRoot.setAlpha(PanelImeViewportPolicy.panelAlpha(panelImeVisible));
        panelRoot.setOnTouchListener((v,event)->{
            // Retroid's display-focus signal is outside the physical screen, not a dismissal tap.
            if(event.getAction()==MotionEvent.ACTION_OUTSIDE&&event.getRawX()<0&&event.getRawY()<0)return true;
            if(event.getAction()==MotionEvent.ACTION_OUTSIDE&&activeDialog==null&&!settingsOpen){closePanel();return true;}
            return false;
        });
        windows.addView(panelRoot,params);installPanelImeInsets(panelRoot);panelRoot.requestApplyInsets();panelShowing=true;syncHandleVisibility();
        dashboardControllerInput=controllerTarget&&!touchOnlyEnabled();
        panel.enableControllerNavigation(dashboardControllerInput);
        if(dashboardControllerInput)controller.controllerFocus(true);
        localScreenOnly(panelRoot,!controller.includePanelInMirror());
        panel.setAlpha(0);panel.animate().alpha(1).setDuration(160).start();
        controller.panelOpen(true);
    }
    boolean routeDashboardKey(android.view.KeyEvent event){
        if(touchOnlyEnabled())return false;
        if(controller==null||panel==null||!DashboardInputPolicy.navigationKey(event.getKeyCode()))return false;
        boolean recording=inlineShortcutEditor!=null&&inlineShortcutEditor.isRecording();
        if(!DashboardInputPolicy.route(panelShowing,controller.panelTargetsExternal(),settingsOpen,
                activeDialog!=null,panelImeVisible,recording))return false;
        if(!dashboardControllerInput){dashboardControllerInput=true;updatePanelPresentation();controller.controllerFocus(true);}
        panel.enableControllerNavigation(true);
        panel.dispatchKeyEvent(event);
        return true;
    }
    void applyTouchOnlyMode(boolean enabled){
        if(enabled&&controller!=null&&dashboardControllerInput)controller.controllerFocus(false);
        dashboardControllerInput=!enabled&&panelShowing;
        controllerEntryRequested=false;
        if(panel!=null)panel.enableControllerNavigation(dashboardControllerInput);
        updatePanelPresentation();
        if(dashboardControllerInput&&controller!=null)controller.controllerFocus(true);
    }
    @Override public void closePanel() {
        if(controller!=null&&dashboardControllerInput)controller.controllerFocus(false);
        dashboardControllerInput=false;
        if(settingsPage!=null){if(inlineShortcutEditor!=null){inlineShortcutEditor.dispose();inlineShortcutEditor=null;}settingsPage=null;settingsOpen=false;}
        if(panel!=null)panel.enableControllerNavigation(false);
        if(panelRoot!=null)panelRoot.setOnApplyWindowInsetsListener(null);
        if(panelRoot!=null){try{(panelWindows==null?windows:panelWindows).removeView(panelRoot);}catch(IllegalArgumentException ignored){}panelRoot=null;}
        panelImeVisible=false;
        panel=null;
        panelWindows=null;panelUiContext=null;windows=mainWindows;uiContext=mainUiContext;windowType=mainWindowType;
        panelShowing=false;
        syncHandleVisibility();
        if(controller!=null)controller.panelOpen(false);
    }
    @Override public void displayTargetChanged(){
        boolean reopen=panelShowing||activeDialog!=null;boolean reopenSettings=settingsOpen;String reopenCategory=settingsCategory;
        clearSystemShadeOwnership();
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        if(panelShowing)closePanel();
        settingsCategory=reopenCategory;
        if(reopen){showPanel();if(reopenSettings&&panelShowing)showSetup();}
    }
    @Override public void render(UiState state) {
        if(panel!=null)panel.render(state);
        refreshSettingsReadiness(state);
        restoreFailure=ConnectionStatusPolicy.afterBridgeStatus(restoreFailure,state.bridgeReady);
        updateConnectionStatus();
        if(restoreRequested&&state.wifiConnected)beginRestoreControls();
        if(handle!=null){handle.connected=state.bridgeReady;handle.invalidate();}
        UiState.ControlStatus recording=state.controls.get("screenrecord");
        boolean active=state.bridgeReady&&recording!=null&&recording.active;
        if(active!=recordingNotification){recordingNotification=active;updateNotification(active);}
    }
    private boolean accessibilityBound(){return DeckAccessibilityService.connected!=null;}
    private void rememberSettingsReadiness(){
        settingsReadinessKnown=true;settingsLastBridge=controller.state.bridgeReady;settingsLastAccessibilityEnabled=SetupReadiness.isAccessibilityServiceEnabled(this);settingsLastAccessibilityBound=accessibilityBound();settingsRefreshPending=false;
    }
    private void refreshSettingsReadiness(UiState state){
        if(settingsPage==null)return;
        boolean enabled=SetupReadiness.isAccessibilityServiceEnabled(this),bound=accessibilityBound();
        if(!settingsReadinessKnown||settingsLastBridge!=state.bridgeReady||settingsLastAccessibilityEnabled!=enabled||settingsLastAccessibilityBound!=bound){
            settingsReadinessKnown=true;settingsLastBridge=state.bridgeReady;settingsLastAccessibilityEnabled=enabled;settingsLastAccessibilityBound=bound;settingsRefreshPending=true;
        }
        if(settingsRefreshPending&&("connection".equals(settingsPage.category())||"dashboard".equals(settingsPage.category()))&&(inlineShortcutEditor==null||!inlineShortcutEditor.isRecording())){
            settingsRefreshPending=false;
            if("dashboard".equals(settingsPage.category())&&inlineShortcutEditor!=null){inlineShortcutEditor.dispose();inlineShortcutEditor=null;}
            settingsPage.refreshCurrent();
        }
    }
    private void updateNotification(boolean recording){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(41,controlsNotification(recording));
    }
    private Notification controlsNotification(boolean recording){
        PendingIntent open=PendingIntent.getService(this,1,new Intent(this,OverlayService.class).setAction(SHOW),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification=new Notification.Builder(this,"controls").setSmallIcon(R.drawable.ic_nova)
            .setContentTitle(recording?"DuoDash · Screen recording":"DuoDash")
            .setContentText(recording?"Video only · stops after 3 minutes · Movies/DuoDash":"Tap to open controls")
            .setCategory(Notification.CATEGORY_SERVICE).setLocalOnly(true)
            .setShowWhen(false).setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true);
        if(recording){
            PendingIntent stopRecording=PendingIntent.getService(this,4,new Intent(this,OverlayService.class).setAction(STOP_RECORDING),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(new Notification.Action.Builder(null,"Stop recording",stopRecording).build());
        }
        return notification.build();
    }

    void windowChanged(){if(controller!=null)controller.windowChanged();}
    void systemUiWindowStateChanged(CharSequence eventClass){
        if(controller!=null&&SystemUiShadePolicy.mayYieldOffline(controller.state.bridgeReady,controller.panelWindowDisplayId(),eventClass)){
            if(panelShowing)yieldToSystemShade(0);
            return;
        }
        querySystemShade(true,systemShadeOwnsHandle);
    }
    void otherWindowStateChanged(){
        if(!systemShadeOwnsHandle)return;
        if(controller!=null&&!controller.state.bridgeReady){restoreHandleAfterShade();return;}
        querySystemShade(false,true);
    }
    private void querySystemShade(boolean mayYield,boolean mayRestore){
        if(controller==null||(!panelShowing&&!systemShadeOwnsHandle))return;
        if(systemUiShadeQueryInFlight){systemUiShadeQueryPending=true;systemUiShadePendingYield|=mayYield;systemUiShadePendingRestore|=mayRestore;return;}
        final int display=systemShadeOwnsHandle?systemShadeDisplay:controller.panelWindowDisplayId();
        if(display<0)return;
        final String outputIdentity=controller.identity();
        final FrameLayout requestedPanel=panelRoot;
        final boolean panelWasShowing=panelShowing;
        systemUiShadeQueryInFlight=true;
        controller.systemUiShadeFocused(display,focused->{
            systemUiShadeQueryInFlight=false;
            boolean sameOutput=current==this&&controller!=null&&controller.panelWindowDisplayId()==display&&outputIdentity.equals(controller.identity());
            boolean currentPanelMatches=current==this&&panelShowing&&panelRoot==requestedPanel;
            logShadeQuery(display,focused,sameOutput,panelWasShowing,currentPanelMatches);
            if(current==this&&controller!=null&&(Boolean.TRUE.equals(focused)||Boolean.FALSE.equals(focused))){
                if(Boolean.TRUE.equals(focused)){
                    if(mayYield&&panelWasShowing&&currentPanelMatches&&sameOutput)yieldToSystemShade(display);
                }else if(mayRestore&&systemShadeOwnsHandle&&systemShadeDisplay==display&&sameOutput){
                    restoreHandleAfterShade();
                }
            }
            if(systemUiShadeQueryPending){
                boolean nextYield=systemUiShadePendingYield,nextRestore=systemUiShadePendingRestore;
                systemUiShadeQueryPending=false;systemUiShadePendingYield=false;systemUiShadePendingRestore=false;
                querySystemShade(nextYield,nextRestore);
            }
        });
    }
    private void logShadeQuery(int display,Boolean focused,boolean sameOutput,boolean panelWasShowing,boolean currentPanelMatches){
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        android.util.Log.i("NovaDeckShade","display="+display+" result="+(focused==null?"null":focused.toString())+
            " sameOutput="+sameOutput+" panelWasShowing="+panelWasShowing+" currentPanelMatches="+currentPanelMatches);
    }
    private void yieldToSystemShade(int display){
        systemShadeOwnsHandle=true;systemShadeDisplay=display;
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        if(panelShowing)closePanel();
        syncHandleVisibility();
    }
    private void restoreHandleAfterShade(){
        if(!systemShadeOwnsHandle)return;
        systemShadeOwnsHandle=false;systemShadeDisplay=-1;
        syncHandleVisibility();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        new Handler(Looper.getMainLooper()).post(this::reconnectWindows);
    }
    @Override public void showSetup() {
        if(controller==null)return;
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        if(!panelShowing)showPanel();
        if(!panelShowing||panelRoot==null)return;
        settingsOpen=true;
        if(settingsPage==null){
            panel.enableControllerNavigation(false);
            Context settingsContext=new ContextThemeWrapper(panelUiContext,R.style.SettingsTheme);
            settingsPage=new SettingsPageView(settingsContext,this,settingsCategory);
            panelRoot.addView(settingsPage,new FrameLayout.LayoutParams(-1,-1));
            localScreenOnly(settingsPage,!controller.includePanelInMirror());
            updatePanelPresentation();settingsPage.animateIn();settingsPage.requestFocus();rememberSettingsReadiness();
        }else{
            if("dashboard".equals(settingsPage.category())&&inlineShortcutEditor!=null&&inlineShortcutEditor.isRecording())return;
            if("dashboard".equals(settingsPage.category())&&inlineShortcutEditor!=null){inlineShortcutEditor.dispose();inlineShortcutEditor=null;}
            settingsPage.refreshCurrent();
        }
    }
    void exitSettingsPage(){
        if(settingsPage==null)return;
        SettingsPageView leaving=settingsPage;settingsCategory=leaving.category();
        if(inlineShortcutEditor!=null){inlineShortcutEditor.dispose();inlineShortcutEditor=null;}
        leaving.animateOut(()->{
            if(settingsPage!=leaving)return;
            if(panelRoot!=null)panelRoot.removeView(leaving);settingsPage=null;settingsOpen=false;
            updatePanelPresentation();
            if(panel!=null){panel.enableControllerNavigation(!touchOnlyEnabled()&&(dashboardControllerInput||controller.panelTargetsExternal()));}
        });
    }
    void settingsCategoryChanged(String previous,String next){
        settingsCategory=next;
        settingsRefreshPending=false;
        if("dashboard".equals(previous)&&!"dashboard".equals(next)&&inlineShortcutEditor!=null){inlineShortcutEditor.dispose();inlineShortcutEditor=null;}
    }
    @Override public void openConnectionSettings(){
        settingsCategory="connection";
        if(settingsPage!=null){settingsPage.selectCategory("connection");return;}
        if(!panelShowing)showPanel();
        if(panelShowing)showSetup();
    }
    @Override public void openControlSettings(String id){
        if("wifi".equals(id))openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS);
        else if("bluetooth".equals(id))openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
    }
    @Override public void networkAvailable(){
        if(restoreRequested){beginRestoreControls();return;}
        if(!dashboardReady||controller==null||controller.state.bridgeReady||!OnDeviceBridge.isPaired(this)
                ||autoRecoveryAttempted||BridgeActivationService.isConnecting())return;
        autoRecoveryAttempted=true;activationLaunched=true;restoreFailure="";
        BridgeActivationService.connectAfterBoot(this);
    }
    @Override public void networkUnavailable(){autoRecoveryAttempted=false;}
    void accessibilityWindowPackage(CharSequence packageName){
        if(!wifiPanelOpen)return;
        String pkg=packageName==null?"":packageName.toString();
        if("com.android.settings".equals(pkg)||"com.android.systemui".equals(pkg)){
            wifiPanelSeenSettings=true;return;
        }
        if(!wifiPanelSeenSettings)return;
        wifiPanelOpen=false;wifiPanelSeenSettings=false;
        settingsCategory="connection";
        syncHandleVisibility();
        if(controller!=null){if(!panelShowing)showPanel();if(panelShowing)showSetup();}
    }
    private void startRestoreControls(){
        if(controller==null)return;
        restoreFailure="";
        if(!OnDeviceBridge.isPaired(this)){startInlinePairing();return;}
        restoreRequested=true;
        if(BridgeActivationService.isConnecting()){activationLaunched=true;updateConnectionStatus();return;}
        if(!controller.state.wifiConnected){updateConnectionStatus();openWifiSettingsPanel();return;}
        beginRestoreControls();
    }
    private void beginRestoreControls(){
        if(!restoreRequested||controller==null||!controller.state.wifiConnected||activationLaunched||BridgeActivationService.isConnecting())return;
        activationLaunched=true;restoreFailure="";updateConnectionStatus();BridgeActivationService.connect(this);
    }
    private void startInlinePairing(){
        boolean notificationReady=BridgeActivationService.preparePairing(this);
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service!=null&&service.showPairingPanel()){
            openDeveloperOptions();return;
        }
        if(notificationReady){openDeveloperOptions();return;}
        restoreFailure="Could not open the pairing keypad. Re-enable DuoDash under Accessibility and try again.";updateConnectionStatus();
    }
    private void openWifiSettingsPanel(){
        if(wifiPanelOpen)return;
        wifiPanelOpen=true;wifiPanelSeenSettings=false;
        if(controller!=null)controller.wakeHandheldForActivity("com.android.settings");
        if(panelShowing)closePanel();
        if(handle!=null)handle.setVisibility(View.GONE);
        Intent intent=new Intent(android.provider.Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(Exception error){wifiPanelOpen=false;wifiPanelSeenSettings=false;syncHandleVisibility();restoreFailure="Open Android Wi-Fi settings, then return and choose Restore controls";updateConnectionStatus();}
    }
    private void updateConnectionStatus(){
        if(connectionStatusView==null||controller==null)return;
        boolean paired=OnDeviceBridge.isPaired(this);
        boolean connecting=BridgeActivationService.isConnecting()||activationLaunched;
        String status=controller.state.bridgeReady?"Connected":connecting?nonempty(BridgeActivationService.currentMessage(),"Restoring controls…")
                :restoreRequested&&!controller.state.wifiConnected?"Waiting for Wi-Fi":!restoreFailure.isEmpty()?restoreFailure:"System controls unavailable";
        connectionStatusView.setText(status);
        if(connectionHelpView!=null){
            if(!paired)connectionHelpView.setText("Pair this device from Android Wireless debugging. Local controls and profiles remain available.");
            else connectionHelpView.setText("Connect to Wi-Fi briefly to restore controls. Wireless debugging returns to its previous setting.");
        }
        if(restoreControlsButton!=null){
            restoreControlsButton.setText(paired?"Restore controls":"Pair this device");
            restoreControlsButton.setEnabled(!connecting&&!controller.state.bridgeReady);
        }
    }
    private static String nonempty(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value;}
    void populateSettingsCategory(String category,LinearLayout content){
        if(controller==null)return;
        switch(category){case "output":buildOutputSettings(content);break;case "dashboard":buildDashboardSettings(content);break;case "connection":buildConnectionSettings(content);break;}
    }
    private TextView sectionLabel(LinearLayout parent,String text){
        TextView label=new TextView(parent.getContext());label.setText(text);label.setTextSize(14);label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));label.setTextColor(0xffd2c6df);label.setPadding(0,dp(14),0,dp(5));parent.addView(label,new LinearLayout.LayoutParams(-1,dp(42)));return label;
    }
    private LinearLayout settingsRow(LinearLayout parent){
        LinearLayout row=new LinearLayout(parent.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(2),0,dp(2));parent.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));return row;
    }
    private TextView rowText(LinearLayout row,String title,String subtitle){
        LinearLayout labels=new LinearLayout(row.getContext());labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);row.addView(labels,new LinearLayout.LayoutParams(0,-1,1));TextView main=new TextView(row.getContext());main.setText(title);main.setTextSize(14);main.setTextColor(0xfff1eff4);labels.addView(main);if(subtitle!=null&&!subtitle.isEmpty()){TextView sub=new TextView(row.getContext());sub.setText(subtitle);sub.setTextSize(11);sub.setTextColor(0xffaaa6b0);labels.addView(sub);}return main;
    }
    private Switch settingSwitch(LinearLayout parent,String title,boolean checked,Consumer<Boolean> action){
        LinearLayout row=settingsRow(parent);Switch toggle=new Switch(row.getContext());toggle.setText(title);toggle.setTextSize(14);toggle.setTextColor(0xfff1eff4);toggle.setThumbTintList(stateTint(0xffd2c6df,0xff8d8992));toggle.setTrackTintList(stateTint(0xff756b80,0xff514f55));toggle.setChecked(checked);toggle.setMinHeight(dp(48));row.addView(toggle,new LinearLayout.LayoutParams(-1,-1));toggle.setOnCheckedChangeListener((button,value)->action.accept(value));return toggle;
    }
    private static android.content.res.ColorStateList stateTint(int checked,int normal){return new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{checked,normal});}
    private void styleSettingsButton(Button button){button.setAllCaps(false);button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffa6a2ad,0xfff1eff4}));button.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},new int[]{}},new int[]{0xff514b58,0xff423d47,0xff302d33}));}
    private void styleSettingsRadio(RadioButton radio){radio.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xffa6a2ad,0xfff1eff4,0xffe2dfe6}));radio.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff76727a,0xffd2c6df,0xffb8b4be}));}
    private void styleSettingsEditText(EditText input){input.setTextColor(0xfff1eff4);input.setHintTextColor(0xffb8b4be);input.setBackgroundTintList(stateTint(0xffd2c6df,0xff77737d));}
    private CharSequence modeChoiceText(String title,String description){
        android.text.SpannableStringBuilder text=new android.text.SpannableStringBuilder(title);text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,title.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.append('\n');int start=text.length();text.append(description);text.setSpan(new android.text.style.RelativeSizeSpan(.84f),start,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);text.setSpan(new android.text.style.ForegroundColorSpan(0xffb8b4be),start,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return text;
    }
    private void buildOutputSettings(LinearLayout content){
        if(!controller.identity().isEmpty()){
            sectionLabel(content,"Profile name");EditText name=new EditText(content.getContext());name.setSingleLine(true);name.setText(controller.state.profile);name.setHint("Profile name");name.setContentDescription("Profile name");name.setTextSize(14);styleSettingsEditText(name);name.setSelectAllOnFocus(true);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});content.addView(name,new LinearLayout.LayoutParams(-1,dp(48)));String stableIdentity=controller.identity();name.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(!s.toString().trim().isEmpty())controller.renameOutput(stableIdentity,s.toString());}public void afterTextChanged(android.text.Editable e){}});
        }
        UiState.ResolutionPreview pending=controller.pendingResolution();sectionLabel(content,"Resolution & aspect ratio");TextView note=new TextView(content.getContext());note.setText("Choose a resolution and screen shape. Some apps may restart.");note.setTextSize(12);note.setTextColor(0xffb8b4be);content.addView(note,new LinearLayout.LayoutParams(-1,-2));
        List<UiState.DisplayCanvas> canvases=new ArrayList<>(controller.state.displayCanvases);canvases.sort(Comparator.comparingInt(c->c.id==0?0:1));
        if(canvases.isEmpty()){TextView unavailable=new TextView(content.getContext());unavailable.setText("Connect system controls to see available screens.");unavailable.setTextSize(13);unavailable.setTextColor(0xffaaa6b0);content.addView(unavailable,new LinearLayout.LayoutParams(-1,dp(44)));}else for(UiState.DisplayCanvas canvas:canvases)addCanvasPicker(content,canvas,controller.layoutKey());
        if(pending!=null&&pending.ok)addResolutionConfirmation(content,pending);
        if(controller.state.externalConnected){
            sectionLabel(content,"External display");String profile=controller.layoutKey();RadioGroup modes=new RadioGroup(content.getContext());modes.setOrientation(RadioGroup.VERTICAL);RadioButton docked=new RadioButton(content.getContext());docked.setId(View.generateViewId());docked.setMinHeight(dp(62));docked.setGravity(Gravity.CENTER_VERTICAL);docked.setText(modeChoiceText("Use external screen","Show the same Android screen on your TV or glasses."));styleSettingsRadio(docked);RadioButton dual=new RadioButton(content.getContext());dual.setId(View.generateViewId());dual.setMinHeight(dp(62));dual.setGravity(Gravity.CENTER_VERTICAL);dual.setText(modeChoiceText("Use both screens","Run a different app on each screen."));styleSettingsRadio(dual);modes.addView(docked,new RadioGroup.LayoutParams(-1,dp(66)));modes.addView(dual,new RadioGroup.LayoutParams(-1,dp(66)));modes.check(controller.dockedMode()?docked.getId():dual.getId());content.addView(modes);modes.setOnCheckedChangeListener((g,id)->controller.displayMode(profile,id==docked.getId()?DisplayModePolicy.DOCKED:DisplayModePolicy.DUAL));
            sectionLabel(content,"Show DuoDash on");RadioGroup target=new RadioGroup(content.getContext());target.setOrientation(RadioGroup.HORIZONTAL);RadioButton handheld=new RadioButton(content.getContext());handheld.setText("Handheld");styleSettingsRadio(handheld);handheld.setId(View.generateViewId());RadioButton external=new RadioButton(content.getContext());external.setText("External");styleSettingsRadio(external);external.setId(View.generateViewId());target.addView(handheld,new RadioGroup.LayoutParams(0,dp(48),1));target.addView(external,new RadioGroup.LayoutParams(0,dp(48),1));target.check(controller.state.showDuoDashOnExternal?external.getId():handheld.getId());content.addView(target);target.setOnCheckedChangeListener((g,id)->controller.showDuoDashOn(profile,id==external.getId()?"external":"handheld"));
        }
    }
    private void buildDashboardSettings(LinearLayout content){
        sectionLabel(content,"Shortcut");inlineShortcutEditor=new InlineShortcutEditor(content.getContext(),this,controller);content.addView(inlineShortcutEditor,new LinearLayout.LayoutParams(-1,-2));
        settingSwitch(content,"Touch only",touchOnlyEnabled(),enabled->{getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean("touchOnly",enabled).apply();applyTouchOnlyMode(enabled);});
        sectionLabel(content,"Layout");
        String layout=controller.layoutKey();settingSwitch(content,"Horizontal sliders",controller.state.horizontalSliders,v->controller.style(layout,v,controller.state.compactControls));settingSwitch(content,"Floating launch button",floatingEnabled(),v->{getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean("floatingButton",v).apply();if(!v&&handle!=null){mainWindows.removeView(handle);handle=null;}else if(v&&handle==null)addHandle();});
        addScreenshotSettings(content);
        sectionLabel(content,"Status bar");settingSwitch(content,"Show refresh rate",controller.state.showHeaderHz,controller::showHeaderHz);settingSwitch(content,"Show Wi-Fi status",controller.state.showHeaderWifi,controller::showHeaderWifi);settingSwitch(content,"Show clock",controller.state.showHeaderClock,controller::showHeaderClock);settingSwitch(content,"Show estimated battery time",controller.state.showBatteryTime,controller::showBatteryTime);
    }
    boolean touchOnlyEnabled(){return getSharedPreferences("ui",MODE_PRIVATE).getBoolean("touchOnly",false);}
    private void addScreenshotSettings(LinearLayout content){
        sectionLabel(content,"Screenshots");
        TextView note=new TextView(content.getContext());note.setText("Tap Screenshot to choose a screen; hold to capture.");note.setTextSize(12);note.setTextColor(0xffb8b4be);content.addView(note,new LinearLayout.LayoutParams(-1,-2));
        settingSwitch(content,"Include DuoDash",ScreenshotOptions.includeDuoDash(this),value->ScreenshotOptions.setIncludeDuoDash(this,value));
    }
    private void buildConnectionSettings(LinearLayout content){
        sectionLabel(content,"System controls");connectionStatusView=new TextView(content.getContext());connectionStatusView.setTextSize(14);connectionStatusView.setTextColor(0xfff1eff4);content.addView(connectionStatusView,new LinearLayout.LayoutParams(-1,dp(34)));
        connectionHelpView=new TextView(content.getContext());connectionHelpView.setTextSize(12);connectionHelpView.setTextColor(0xffb8b4be);content.addView(connectionHelpView,new LinearLayout.LayoutParams(-1,-2));
        restoreControlsButton=new Button(content.getContext());styleSettingsButton(restoreControlsButton);content.addView(restoreControlsButton,new LinearLayout.LayoutParams(-1,dp(48)));restoreControlsButton.setOnClickListener(v->startRestoreControls());updateConnectionStatus();
        Button developer=new Button(content.getContext());developer.setText("Open Developer options");styleSettingsButton(developer);content.addView(developer,new LinearLayout.LayoutParams(-1,dp(48)));developer.setOnClickListener(v->openDeveloperOptions());
        sectionLabel(content,"Permissions and notifications");boolean accessibilityEnabled=SetupReadiness.isAccessibilityServiceEnabled(this);LinearLayout access=settingsRow(content);rowText(access,"Always-visible controls",accessibilityEnabled?"Enabled":"Off");Button accessButton=new Button(content.getContext());accessButton.setText(accessibilityEnabled?"Manage":"Set up");styleSettingsButton(accessButton);access.addView(accessButton,new LinearLayout.LayoutParams(dp(100),dp(44)));accessButton.setOnClickListener(v->openSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        LinearLayout notify=settingsRow(content);rowText(notify,"Background notification","Hide this category in Android settings. Controls will keep working.");Button notifyButton=new Button(content.getContext());notifyButton.setText("Manage");styleSettingsButton(notifyButton);notify.addView(notifyButton,new LinearLayout.LayoutParams(dp(100),dp(44)));notifyButton.setOnClickListener(v->openControlsNotificationSettings());
    }
    private void addCanvasPicker(LinearLayout parent,UiState.DisplayCanvas canvas,String layoutKey){
        Context ctx=parent.getContext();LinearLayout group=new LinearLayout(ctx);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(dp(4),dp(4),dp(4),dp(4));
        LinearLayout row=new LinearLayout(ctx);row.setGravity(Gravity.CENTER_VERTICAL);group.addView(row,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout labels=new LinearLayout(ctx);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(Gravity.CENTER_VERTICAL);row.addView(labels,new LinearLayout.LayoutParams(0,-1,1));
        TextView heading=new TextView(ctx);String screenName=canvas.mirroredPrimary?"TV mirror":canvas.id==0?"Main screen":"External screen";heading.setText(screenName);heading.setTextSize(14);heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));heading.setTextColor(0xfff1eff4);labels.addView(heading);
        TextView current=new TextView(ctx);current.setText(canvas.currentWidth>0&&canvas.currentHeight>0?canvas.currentWidth+" × "+canvas.currentHeight+" · "+aspect(canvas.currentWidth,canvas.currentHeight):"Current resolution unavailable");current.setTextSize(12);current.setTextColor(0xffb8b4be);labels.addView(current);
        Button change=new Button(ctx);change.setText("Change");styleSettingsButton(change);change.setMinWidth(0);change.setMinimumWidth(0);row.addView(change,new LinearLayout.LayoutParams(dp(84),dp(44)));
        LinearLayout choices=new LinearLayout(ctx);choices.setOrientation(LinearLayout.VERTICAL);choices.setVisibility(View.GONE);group.addView(choices,new LinearLayout.LayoutParams(-1,-2));
        change.setContentDescription("Change resolution for "+screenName);change.setOnClickListener(v->{boolean open=choices.getVisibility()!=View.VISIBLE;choices.setVisibility(open?View.VISIBLE:View.GONE);change.setText(open?"Done":"Change");});
        if(canvas.options.isEmpty()){
            TextView none=new TextView(ctx);none.setText("No supported resolutions available");none.setTextSize(12);none.setTextColor(0xffb8b4be);choices.addView(none,new LinearLayout.LayoutParams(-1,dp(40)));
        }else for(UiState.CanvasOption choice:canvas.options){
            Button option=new Button(ctx);styleSettingsButton(option);option.setMinWidth(0);option.setMinimumWidth(0);option.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
            String label=(choice.label==null||choice.label.isEmpty()?"Resolution":choice.label)+" · "+choice.width+" × "+choice.height+" · "+aspect(choice.width,choice.height);
            boolean selected=choice.id.equals(canvas.savedOptionId);
            boolean currentChoice=canvas.currentWidth==choice.width&&canvas.currentHeight==choice.height;
            option.setText(label+(selected?" · Saved":currentChoice?" · Current":""));option.setTextSize(13);option.setMinHeight(dp(44));choices.addView(option,new LinearLayout.LayoutParams(-1,dp(44)));
            option.setOnClickListener(v->{
                UiState.ResolutionPreview active=controller.pendingResolution();if(active!=null&&active.ok){message("Keep or revert the current screen-size preview first");return;}
                option.setEnabled(false);controller.previewResolution(layoutKey,canvas.id,canvas.identity,choice.id,result->{
                    option.setEnabled(true);
                    if(result.ok&&result.transactionId!=null&&!result.transactionId.isEmpty()){resolutionUiTransaction=result.transactionId;resolutionUiDeadline=SystemClock.uptimeMillis()+14_000;showSetup();}
                    else message(result.message==null?"Could not preview this resolution":result.message);
                });
            });
        }
        parent.addView(group,new LinearLayout.LayoutParams(-1,-2));
    }
    private void addResolutionConfirmation(LinearLayout parent,UiState.ResolutionPreview preview){
        if(preview.transactionId==null||preview.transactionId.isEmpty())return;
        Context ctx=parent.getContext();if(!preview.transactionId.equals(resolutionUiTransaction)){resolutionUiTransaction=preview.transactionId;resolutionUiDeadline=SystemClock.uptimeMillis()+14_000;}
        LinearLayout confirm=new LinearLayout(ctx);confirm.setOrientation(LinearLayout.VERTICAL);confirm.setPadding(dp(8),dp(8),dp(8),dp(4));
        TextView title=new TextView(ctx);title.setText("Preview: "+preview.width+" × "+preview.height+" · "+aspect(preview.width,preview.height));title.setTextSize(14);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));title.setTextColor(0xfff1eff4);confirm.addView(title,new LinearLayout.LayoutParams(-1,dp(36)));
        TextView countdown=new TextView(ctx);countdown.setTextSize(12);countdown.setTextColor(0xffb8b4be);confirm.addView(countdown,new LinearLayout.LayoutParams(-1,dp(28)));
        LinearLayout actions=new LinearLayout(ctx);actions.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);confirm.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        Button revert=new Button(ctx);revert.setText("Revert");styleSettingsButton(revert);actions.addView(revert,new LinearLayout.LayoutParams(dp(96),dp(44)));
        Button keep=new Button(ctx);keep.setText("Keep");styleSettingsButton(keep);actions.addView(keep,new LinearLayout.LayoutParams(dp(96),dp(44)));
        keep.setOnClickListener(v->{keep.setEnabled(false);controller.confirmResolution(controller.layoutKey(),preview.transactionId,ok->{resolutionUiTransaction="";resolutionUiDeadline=0;if(ok){controller.refreshConnection();showSetup();}else message("Could not save this screen size");});});
        revert.setOnClickListener(v->{revert.setEnabled(false);controller.revertResolution(preview.transactionId,ok->{resolutionUiTransaction="";resolutionUiDeadline=0;controller.refreshConnection();showSetup();});});
        parent.addView(confirm,new LinearLayout.LayoutParams(-1,-2));
        Runnable tick=new Runnable(){@Override public void run(){
            if(!settingsOpen||!preview.transactionId.equals(resolutionUiTransaction))return;
            long remaining=resolutionUiDeadline-SystemClock.uptimeMillis();
            if(remaining<=0){resolutionUiTransaction="";resolutionUiDeadline=0;controller.revertResolution(preview.transactionId,ok->{controller.refreshConnection();if(settingsOpen)showSetup();});return;}
            countdown.setText("Reverting in "+Math.max(1,(remaining+999)/1000)+" seconds unless you keep it");resolutionUiHandler.postDelayed(this,250);
        }};tick.run();
    }
    private static String aspect(int width,int height){if(width<=0||height<=0)return "ratio unavailable";int a=width,b=height;while(b!=0){int r=a%b;a=b;b=r;}return (width/a)+":"+(height/a);}
    void openDeveloperOptions(){
        AlertDialog dialog=activeDialog;
        Intent intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        controller.wakeHandheldForActivity(targetPackage(intent));
        if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();activeDialog=null;}
        clearRecording();closePanel();
        try{startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(Exception error){message("Open Android Settings → Developer options → Wireless debugging");}
    }
    private void openAppNotificationSettings(){
        Intent intent=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        controller.wakeHandheldForActivity(targetPackage(intent));
        AlertDialog dialog=activeDialog;if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();activeDialog=null;}
        clearRecording();closePanel();
        try{startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(Exception error){message("Open Android Settings → Apps → DuoDash → Notifications");}
    }
    void bridgeActivationFinished(boolean connected,String detail){
        if(controller!=null)controller.refreshConnection();
        if(activationLaunched||restoreRequested){
            activationLaunched=false;restoreRequested=false;
            restoreFailure=connected?ConnectionStatusPolicy.RESTORE_VERIFYING:nonempty(detail,"System controls could not be restored");
            updateConnectionStatus();return;
        }
        if(backgroundRebindPending){
            if(connected&&DeckAccessibilityService.connected!=null)requestReadyEntry("background_rebind");
            else if(!connected){backgroundRebindPending=false;resolutionUiHandler.removeCallbacks(stopBackgroundRebind);stopForeground(true);stopSelf();}
            return;
        }
        if(!connected)message(detail);
    }
    void showDialog(AlertDialog dialog){
        clearRecording();
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        if(!panelShowing)showPanel();activeDialog=dialog;
        dialog.getWindow().setType(windowType);
        dialog.show();
        Window dialogWindow=dialog.getWindow();
        boolean visibleOnExternal=controller.includePanelInMirror();
        Window.Callback originalCallback=dialogWindow.getCallback();
        Window.Callback guardedCallback=guardDialogCallback(originalCallback,visibleOnExternal);
        if(guardedCallback!=null)dialogWindow.setCallback(guardedCallback);
        dialog.setOnDismissListener(d->{if(guardedCallback!=null&&dialogWindow.getCallback()==guardedCallback)dialogWindow.setCallback(originalCallback);dialogDismissed(dialog);});
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setDimAmount(0f);
        GradientDrawable background=new GradientDrawable();background.setColor(0xff242328);background.setCornerRadius(dp(12));
        dialog.getWindow().setBackgroundDrawable(background);
        localScreenOnly(dialog.getWindow().getDecorView(),!visibleOnExternal);
        dialog.getWindow().setLayout(Math.min(dp(560),mainDisplayBounds().width()-dp(32)),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void updatePanelPresentation(){
        if(panel==null||panelRoot==null)return;boolean dashboardFull=controller.state.fullScreen;boolean full=settingsOpen||dashboardFull;Rect bounds=mainDisplayBounds();
        int width=dashboardFull?WindowManager.LayoutParams.MATCH_PARENT:Math.min(dp(620),bounds.width()-dp(24));
        int height=dashboardFull?WindowManager.LayoutParams.MATCH_PARENT:Math.min(dp(406),bounds.height()-dp(68));
        FrameLayout.LayoutParams child=(FrameLayout.LayoutParams)panel.getLayoutParams();child.width=width;child.height=height;child.gravity=Gravity.CENTER;panel.setLayoutParams(child);
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)panelRoot.getLayoutParams();
        params.width=full?WindowManager.LayoutParams.MATCH_PARENT:width;params.height=full?WindowManager.LayoutParams.MATCH_PARENT:height;
        boolean controllerTarget=settingsOpen||(!touchOnlyEnabled()&&(controller.panelTargetsExternal()||dashboardControllerInput));
        params.flags=PanelImePolicy.flags(controllerTarget)|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|(full?WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS:WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        if(panelImeVisible)params.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.setFitInsetsTypes(full?0:android.view.WindowInsets.Type.systemBars());
        params.gravity=full?Gravity.TOP|Gravity.LEFT:Gravity.CENTER;params.y=full?0:-dp(8);params.layoutInDisplayCutoutMode=full?WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES:WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        panelRoot.setSystemUiVisibility(full?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:0);
        windows.updateViewLayout(panelRoot,params);panel.render(controller.state);
    }
    private void installPanelImeInsets(FrameLayout root){
        root.setOnApplyWindowInsetsListener((view,insets)->{
            boolean visible=android.os.Build.VERSION.SDK_INT>=30&&insets.isVisible(WindowInsets.Type.ime());
            handleImeVisible=visible;
            if(panelImeVisible!=visible){
                panelImeVisible=visible;
                root.setAlpha(PanelImeViewportPolicy.panelAlpha(visible));
                if(root.getLayoutParams() instanceof WindowManager.LayoutParams){
                    WindowManager.LayoutParams params=(WindowManager.LayoutParams)root.getLayoutParams();
                    if(visible)params.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    else params.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    try{windows.updateViewLayout(root,params);}catch(IllegalArgumentException ignored){}
                }
                syncHandleVisibility();
                logPanelImeState(root,visible);
            }
            return insets;
        });
    }
    private void logPanelImeState(View root,boolean visible){
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        int signature=Objects.hash(visible,root.getWidth(),root.getHeight());
        if(signature==lastImeGeometryHash)return;
        lastImeGeometryHash=signature;
        int flags=root.getLayoutParams() instanceof WindowManager.LayoutParams?((WindowManager.LayoutParams)root.getLayoutParams()).flags:0;
        android.util.Log.i("NovaDeckIme","visible="+visible+" root="+root.getWidth()+"x"+root.getHeight()+" alpha="+root.getAlpha()+" notTouchable="+((flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0));
    }
    void dialogDismissed(AlertDialog dialog){if(activeDialog==dialog){activeDialog=null;clearRecording();if(current==this)showPanel();}}
    void dismissForLaunch(){if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}clearRecording();closePanel();}
    @Override public void message(String text){String visible=text==null?"":text.replaceAll("(?i)\\bupper (screen|display|app|audio)\\b","external $1");Toast.makeText(uiContext,visible,Toast.LENGTH_SHORT).show();}
    private void ensureDashboard(){
        chooseWindowContext();
        if(controller==null){
            controller=new DeckController(this,this);
            controller.state.fullScreen=true;
            getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean("fullScreen",true).apply();
        }
        if(floatingEnabled()&&android.provider.Settings.canDrawOverlays(this)&&handle==null)addHandle();
    }
    private void routeToSetup(){
        dashboardReady=false;
        if(controller!=null)controller.wakeHandheldForActivity(getPackageName());
        if(activeDialog!=null){
            activeDialog.setOnDismissListener(null);
            activeDialog.dismiss();
            activeDialog=null;
        }
        settingsOpen=false;
        clearRecording();
        closePanel();
        if(handle!=null){try{mainWindows.removeView(handle);}catch(IllegalArgumentException ignored){}handle=null;}
        try{
            Intent setup=new Intent(this,MainActivity.class).putExtra("setup",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(setup,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        }catch(Exception ignored){}
        if(controller==null){stopForeground(true);stopSelf();}
    }
    private void requestReadyEntry(String action){
        pendingEntryAction=OverlayEntryPolicy.merge(pendingEntryAction,action);
        if(readinessCheckPending)return;
        readinessCheckPending=true;
        SetupReadiness.refresh(this,status->{
            readinessCheckPending=false;
            if(current!=this)return;
            String next=pendingEntryAction;pendingEntryAction=null;
            if(SetupReadiness.shouldRouteToSetup(status)){
                if("background_rebind".equals(next)){
                    // Wait quietly for accessibility to finish rebinding; never open a
                    // setup Activity from Android's sticky background-service restart.
                    if(status.isCompleted()&&status.paired&&status.accessibilityEnabled&&!status.accessibilityBound)return;
                    backgroundRebindPending=false;resolutionUiHandler.removeCallbacks(stopBackgroundRebind);stopForeground(true);stopSelf();return;
                }
                routeToSetup();return;
            }
            if(status.canFinish())SetupReadiness.complete(this,status);
            dashboardReady=true;
            try{ensureDashboard();}catch(Exception error){routeToSetup();return;}
            if("background_rebind".equals(next)){backgroundRebindPending=false;resolutionUiHandler.removeCallbacks(stopBackgroundRebind);return;}
            if(RESET.equals(next)){restoreMainScreen();controller.restore();}
            else if(STOP_RECORDING.equals(next))controller.stopScreenRecording();
            else if("settings".equals(next)){if(!panelShowing)showPanel();showSetup();}
            else if(TOGGLE.equals(next)){restoreMainScreen();togglePanel();}
            else if(SHOW.equals(next)){restoreMainScreen();showPanel();}
        });
    }
    @Override public void performControl(String id){
        switch(id){
            case "black_main":if(mainBlank!=null)restoreMainScreen();else blackMainScreen();return;
            case "shortcuts":ShortcutDialogs.show(this);return;
            case "apps":chooseApp(false);return;
            case "keyboard":controller.wakeHandheldForActivity("");dismissForLaunch();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();return;
            case "screenshot":
                startScreenshotCapture();return;
            case "audio_output":openAudioOutput();return;
            case "quick_settings":openSettings(android.provider.Settings.ACTION_SETTINGS);return;
            case "display_settings":openSettings(android.provider.Settings.ACTION_DISPLAY_SETTINGS);return;
            case "audio_settings":openSettings(android.provider.Settings.ACTION_SOUND_SETTINGS);return;
            case "cast":openSettings(android.provider.Settings.ACTION_CAST_SETTINGS);return;
            case "hotspot":openSettings("android.settings.TETHER_SETTINGS");return;
            case "fan":case "performance":case "controller_style":case "trigger_mode":case "refresh_mode":showNativeMode(id);return;
        }
        for(UiState.AppShortcut app:controller.state.pinnedApps)if(app.id.equals(id)){launchApp(android.content.ComponentName.unflattenFromString(app.component),app.upper);return;}
        message("This control is no longer available");
    }
    private void startScreenshotCapture(){
        if(screenshotCapture!=null){message("A screenshot is already being saved");return;}
        DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(service==null){message("Enable always-visible controls to take screenshots");return;}
        final ScreenshotCapture[] holder=new ScreenshotCapture[1];
        ScreenshotCapture capture=new ScreenshotCapture(this,service,ScreenshotOptions.target(this),
            ScreenshotOptions.includeDuoDash(this),screenshotSurfaces(),result->{
                if(screenshotCapture==holder[0])screenshotCapture=null;
                message(result);
            });
        holder[0]=capture;screenshotCapture=capture;capture.start();
    }
    private List<View> screenshotSurfaces(){
        List<View> result=new ArrayList<>(4);
        if(mainBlank!=null)result.add(mainBlank);
        if(handle!=null)result.add(handle);
        if(panelRoot!=null)result.add(panelRoot);
        if(activeDialog!=null&&activeDialog.getWindow()!=null)result.add(activeDialog.getWindow().getDecorView());
        return result;
    }
    private void showNativeMode(String id){
        UiState.ControlStatus status=controller.state.controls.get(id);ControlCatalog.Entry entry=ControlCatalog.find(id);
        if(status==null||!status.available||status.optionLabels.isEmpty()){message("This native control has not been detected");return;}
        int selected=status.optionValues.indexOf(status.value);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(entry.label).setSingleChoiceItems(status.optionLabels.toArray(new String[0]),selected,(d,which)->{controller.nativeMode(id,status.optionValues.get(which));d.dismiss();}).setNegativeButton("Cancel",null).create();showDialog(dialog);
        dialog.getWindow().setLayout(Math.min(dp(360),mainDisplayBounds().width()-dp(32)),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private String targetPackage(Intent intent){android.content.pm.ResolveInfo resolved=getPackageManager().resolveActivity(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);return resolved==null||resolved.activityInfo==null?"":resolved.activityInfo.packageName;}
    private void openSettings(String action){Intent intent=new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);int target=controller.dockedMode()?0:controller.dashboardDisplayId();if(target==0&&!controller.dockedMode())controller.wakeHandheldForActivity(targetPackage(intent));dismissForLaunch();try{startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());}catch(ActivityNotFoundException error){message("Android settings are unavailable");}}
    private void openAudioOutput(){
        Intent systemUi=new Intent(ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG).setPackage(PACKAGE_SYSTEM_UI)
            .putExtra(EXTRA_MEDIA_OUTPUT_PACKAGE,getPackageName());
        if(hasSystemBroadcastReceiver(systemUi)){
            controller.wakeHandheldForActivity(PACKAGE_SYSTEM_UI);dismissForLaunch();
            resolutionUiHandler.postDelayed(()->{
                if(current!=this)return;
                try{sendBroadcast(systemUi);}catch(RuntimeException failure){message("Android's audio output chooser could not be opened");}
            },200);
            return;
        }

        Intent settingsPanel=new Intent(ACTION_SETTINGS_MEDIA_OUTPUT)
            .putExtra(EXTRA_SETTINGS_MEDIA_OUTPUT_PACKAGE,getPackageName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.content.pm.ResolveInfo targetInfo=getPackageManager().resolveActivity(settingsPanel,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if(isSystemActivity(targetInfo)){
            int display=controller.dockedMode()?0:controller.dashboardDisplayId();
            String packageName=targetInfo.activityInfo.packageName;
            if(display==0&&!controller.dockedMode())controller.wakeHandheldForActivity(packageName);
            dismissForLaunch();
            try{startActivity(settingsPanel,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());}
            catch(ActivityNotFoundException|SecurityException failure){message("Android's audio output chooser is unavailable");}
            return;
        }
        message("Android's audio output chooser is unavailable");
    }
    private boolean hasSystemBroadcastReceiver(Intent intent){
        for(android.content.pm.ResolveInfo receiver:getPackageManager().queryBroadcastReceivers(intent,0)){
            android.content.pm.ActivityInfo info=receiver.activityInfo;
            if(info!=null&&isSystemApplication(info.applicationInfo))return true;
        }
        return false;
    }
    private boolean isSystemActivity(android.content.pm.ResolveInfo resolved){
        return resolved!=null&&resolved.activityInfo!=null&&isSystemApplication(resolved.activityInfo.applicationInfo);
    }
    private boolean isSystemApplication(android.content.pm.ApplicationInfo application){
        return application!=null&&(application.flags&(android.content.pm.ApplicationInfo.FLAG_SYSTEM|android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
    }
    private void openControlsNotificationSettings(){
        Intent channel=new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,"controls")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        controller.wakeHandheldForActivity(targetPackage(channel));dismissForLaunch();
        try{startActivity(channel,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(ActivityNotFoundException error){try{Intent app=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(app,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}catch(Exception ignored){message("Android notification settings are unavailable");}}
    }
    void chooseApp(boolean pin){new AppPicker(this,controller).show(pin);}
    void launchApp(android.content.ComponentName component,boolean upper){
        if(component==null){message("This app shortcut is invalid");return;}
        int target=controller.dockedMode()?0:upper?controller.externalDisplay():0;if(target<0){message("Connect an external display first");return;}
        try{android.content.pm.ActivityInfo info=getPackageManager().getActivityInfo(component,0);if(!info.exported||!info.enabled)throw new ActivityNotFoundException();
            if(target==0&&!controller.dockedMode())controller.wakeHandheldForActivity(component.getPackageName());dismissForLaunch();ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(target);
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),options.toBundle());
        }catch(Exception error){message("Android could not open this app on that screen");}
    }
    private void clearRecording(){if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.recorder=null;if(controller!=null)controller.recordGamepad(false);}
    void recordGamepad(boolean enabled){controller.recordGamepad(enabled);}
    void shortcutsChanged(){controller.configureShortcuts();}
    private void blackMainScreen(){
        if(mainBlank!=null)return;
        View blank=new GuardedView(mainUiContext,false,"main-screen-shield");blank.setBackgroundColor(Color.BLACK);blank.setContentDescription("Main screen black. Tap to restore.");
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,mainWindowType,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
        params.setFitInsetsTypes(0);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;params.setTitle("DuoDash main screen black");
        blank.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        blank.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_UP)restoreMainScreen();return true;});
        mainWindows.addView(blank,params);mainBlank=blank;controller.state.mainBlack=true;localScreenOnly(blank);render(controller.state);
    }
    private void restoreMainScreen(){if(mainBlank!=null){try{mainWindows.removeView(mainBlank);}catch(IllegalArgumentException ignored){}mainBlank=null;}if(controller!=null){controller.state.mainBlack=false;render(controller.state);}}
    /** Screenshot exclusion also prevents these surfaces appearing on mirrored outputs. */
    private void localScreenOnly(View view){localScreenOnly(view,EXCLUDE_CONTROLS_FROM_CAPTURE,0);}
    private void localScreenOnly(View view,boolean excludeFromCapture){localScreenOnly(view,excludeFromCapture,0);}
    private void localScreenOnly(View view,boolean excludeFromCapture,int attempt){
        view.post(()->{
            Object transaction=null;
            try{
                Object root=view.getRootSurfaceControl();
                if(root==null){if(attempt<5)view.postDelayed(()->localScreenOnly(view,excludeFromCapture,attempt+1),80);return;}
                Object surface=org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(root.getClass(),root,"getSurfaceControl");
                if(!((SurfaceControl)surface).isValid()){if(attempt<5)view.postDelayed(()->localScreenOnly(view,excludeFromCapture,attempt+1),80);return;}
                Class<?> surfaceClass=Class.forName("android.view.SurfaceControl"),txClass=Class.forName("android.view.SurfaceControl$Transaction");
                transaction=txClass.getConstructor().newInstance();
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(txClass,transaction,"setSkipScreenshot",surface,excludeFromCapture);
                txClass.getMethod("apply").invoke(transaction);
                android.util.Log.i("NovaDeck",excludeFromCapture?"Control surface excluded from screenshots and mirroring":"Control surface included in screenshots and mirroring");
            }catch(Exception error){android.util.Log.w("NovaDeck","Mirror exclusion unavailable: "+error);}
            finally{if(transaction!=null)try{transaction.getClass().getMethod("close").invoke(transaction);}catch(Exception ignored){}}
        });
    }
    @Override public void promptOutputName(String identity,String suggestion,boolean rename,Consumer<String> result){
        Context dialogUi=dialogContext();
        EditText name=new EditText(dialogUi);name.setSingleLine(true);name.setText(suggestion);name.setSelectAllOnFocus(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        LinearLayout content=new LinearLayout(dialogUi);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(4),dp(24),0);
        TextView note=new TextView(dialogUi);note.setText(rename?"Brightness, volume and screen size stay with this display.":"Give this display a name. Brightness, volume and screen size return automatically when it reconnects.");
        note.setTextColor(0xffA6B2C5);note.setTextSize(14);content.addView(note);content.addView(name,new LinearLayout.LayoutParams(-1,dp(56)));
        AlertDialog dialog=new AlertDialog.Builder(dialogUi).setTitle(rename?"Rename output":"New display connected").setView(content)
            .setPositiveButton("Save",null).setNegativeButton(rename?"Cancel":"Later",(d,w)->result.accept(null)).create();
        showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(name.getText().toString().trim().isEmpty()){name.setError("Enter a display name");return;}
            result.accept(ControlLogic.normalizeName(name.getText().toString(),suggestion));dialog.dismiss();
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    @Override public void showEditor(){new ControlEditor(this,controller).show();}
    void refreshEditLayout(){if(panel!=null)panel.refreshEditingLayout(controller.state);}
    @Override public void editControl(String id){if(panel!=null)panel.startMove(id,controller.layoutKey());}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(action.isEmpty()){
            SetupReadiness.Status local=SetupReadiness.inspect(this,false);
            if(!local.canReconnectInBackground()){stopForeground(true);stopSelf(id);return START_NOT_STICKY;}
            backgroundRebindPending=true;
            resolutionUiHandler.removeCallbacks(stopBackgroundRebind);
            resolutionUiHandler.postDelayed(stopBackgroundRebind,30_000L);
            BridgeActivationService.connect(this);
            return START_STICKY;
        }
        if(SHOW.equals(action))controllerEntryRequested=false;
        if(TOGGLE.equals(action)){
            long now=SystemClock.uptimeMillis();boolean shortcut=intent.getBooleanExtra("shortcut",false);
            ShortcutTogglePolicy.Decision decision=ShortcutTogglePolicy.decide(shortcut,panelShowing,activeDialog!=null,
                now,lastShortcutToggle,hasShortcutToggle);
            if(decision==ShortcutTogglePolicy.Decision.IGNORE_DUPLICATE){
                android.util.Log.i("NovaDeck","Dropped duplicate dashboard shortcut uptime="+now+
                    " source="+intent.getStringExtra("shortcut_source")+
                    " target="+(controller==null?0:controller.panelWindowDisplayId())+
                    " showing="+panelShowing+" dialog="+(activeDialog!=null));
                return START_STICKY;
            }
            controllerEntryRequested=shortcut;
            if(shortcut){lastShortcutToggle=now;hasShortcutToggle=true;}
            android.util.Log.i("NovaDeck","Dashboard toggle uptime="+now+" shortcut="+shortcut+
                " source="+intent.getStringExtra("shortcut_source")+
                " target="+(controller==null?0:controller.panelWindowDisplayId())+
                " showing="+panelShowing+" dialog="+(activeDialog!=null));
            // Explicit toggles close immediately; duplicate shortcut notifications were
            // filtered above even if the first request has already opened the panel.
            if(decision==ShortcutTogglePolicy.Decision.CLOSE){togglePanel();return START_STICKY;}
        }
        if(STOP_RECORDING.equals(action)||RESET.equals(action)||SHOW.equals(action)||TOGGLE.equals(action)){
            boolean openConnection=intent.getBooleanExtra("open_connection",false);
            if(openConnection)settingsCategory="connection";
            requestReadyEntry(openConnection?"settings":action);
        }
        return START_STICKY;
    }
    private void togglePanel(){
        if(mainBlank!=null){restoreMainScreen();showPanel();return;}
        clearRecording();
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;closePanel();}
        else if(panelShowing)closePanel();else showPanel();
    }
    @Override public void onDestroy() {
        current=null;
        resolutionUiHandler.removeCallbacks(stopBackgroundRebind);
        if(screenshotCapture!=null){screenshotCapture.cancel();screenshotCapture=null;}
        if(controller!=null)controller.prepareForStop();
        restoreMainScreen();
        clearRecording();
        if(activeDialog!=null){activeDialog.setOnDismissListener(null);activeDialog.dismiss();activeDialog=null;}
        closePanel();
        if(handle!=null){try{mainWindows.removeView(handle);}catch(IllegalArgumentException ignored){}handle=null;}
        if(controller!=null)controller.stop();
        stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}

    private final class HandleView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean connected;
        HandleView(Context context){super(context);setContentDescription("Open DuoDash control center");setClickable(true);setFocusable(true);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            if(consumeHiddenExternalTouch(event,false,"handle"))return true;
            return super.dispatchTouchEvent(event);
        }
        @Override protected void onDraw(Canvas canvas) {
            float d=getResources().getDisplayMetrics().density;
            paint.setColor(Color.rgb(18,24,34));canvas.drawRoundRect(3*d,7*d,getWidth()-3*d,getHeight()-7*d,15*d,15*d,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d);paint.setColor(Color.rgb(63,76,94));
            canvas.drawRoundRect(3*d,7*d,getWidth()-3*d,getHeight()-7*d,15*d,15*d,paint);paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffB4A5FF);canvas.drawRoundRect(15*d,15*d,30*d,20*d,2*d,2*d,paint);
            paint.setColor(0xff75E6DE);canvas.drawRoundRect(15*d,23*d,30*d,28*d,2*d,2*d,paint);
            paint.setColor(0xffF1F4FA);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(13*d);
            canvas.drawText("DuoDash",38*d,27*d,paint);
            paint.setColor(connected?0xff75E6DE:0xffDFB775);canvas.drawCircle(106*d,22*d,2.5f*d,paint);
        }
    }
}
