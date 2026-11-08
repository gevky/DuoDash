package dev.nova.deck;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.service.notification.StatusBarNotification;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Separate test APK: exercises the installed native windows and shell helper. */
public final class DeviceChecks extends Instrumentation {
    private DeckController controller;
    private OverlayService service;
    private int originalMain,originalUpper;
    private String originalShortcuts="";
    private String originalOrder,originalHidden,originalWide,originalLayout;
    private boolean originalHorizontal,originalCompact;
    private boolean mainMuteRouteVerified,externalMuteRouteVerified;
    private int originalActionColumns;
    private boolean originalsCaptured;
    private Bundle launchArguments;
    private MainActivity setupActivity;
    private void shell(UiAutomation automation,String command)throws Exception{
        try(ParcelFileDescriptor output=automation.executeShellCommand(command)){new FileInputStream(output.getFileDescriptor()).readAllBytes();}
    }
    private String shellOutput(UiAutomation automation,String command)throws Exception{
        try(ParcelFileDescriptor output=automation.executeShellCommand(command)){return new String(new FileInputStream(output.getFileDescriptor()).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    private Notification activePairingNotification(){
        NotificationManager manager=getTargetContext().getSystemService(NotificationManager.class);if(manager==null)return null;
        for(StatusBarNotification item:manager.getActiveNotifications())if(item.getId()==54)return item.getNotification();return null;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);launchArguments=args;start();}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void ui(Runnable code){runOnMainSync(code);}
    private void waitFor(java.util.function.BooleanSupplier condition,String message)throws Exception{for(int i=0;i<50;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError(message);}
    private DashboardView panel(){try{return (DashboardView)field(service,"panel");}catch(Exception e){throw new RuntimeException(e);}}
    private String verifyMainPanelBounds()throws Exception{
        DashboardView view=panel();Context context=(Context)field(service,"uiContext");
        WindowManager manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Rect bounds=manager.getMaximumWindowMetrics().getBounds();
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)((View)field(service,"panelRoot")).getLayoutParams();
        Display display=view.getDisplay();
        String details="display="+(display==null?"null":display.getDisplayId())+", bounds="+bounds.width()+"x"+bounds.height()+", view="+view.getWidth()+"x"+view.getHeight()+", params="+params.width+"x"+params.height+", y="+params.y+", fitInsetsTypes="+params.getFitInsetsTypes()+", type="+params.type;
        require(display!=null&&display.getDisplayId()==0,"DuoDash panel is not attached to built-in display 0: "+details);
        require(params.width==WindowManager.LayoutParams.MATCH_PARENT&&params.height==WindowManager.LayoutParams.MATCH_PARENT,"Fullscreen layout is not MATCH_PARENT: "+details);
        require(params.y==0,"Fullscreen layout is offset from the top edge: "+details);
        require(params.getFitInsetsTypes()==0,"Fullscreen layout still requests system-bar insets: "+details);
        int expectedType=DeckAccessibilityService.connected!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        require(params.type==expectedType,"Panel selected the wrong trusted-window path: expected="+expectedType+" actual="+params.type+"; "+details);
        require(view.getWidth()==bounds.width()&&view.getHeight()==bounds.height(),"Fullscreen panel does not match main-display bounds: "+details);
        return details;
    }
    private void verifyAdbIdentityPersistence()throws Exception{
        Context target=getTargetContext();OnDeviceAdb.resetForValidation();String before=OnDeviceAdb.get(target).certificateFingerprint();
        File key=new File(target.getFilesDir(),"adb-host.pk8"),cert=new File(target.getFilesDir(),"adb-host.pem");require(key.isFile()&&cert.isFile(),"Private ADB identity files missing");byte[] keyBefore=java.nio.file.Files.readAllBytes(key.toPath());
        OnDeviceAdb.resetForValidation();String after=OnDeviceAdb.get(target).certificateFingerprint();byte[] keyAfter=java.nio.file.Files.readAllBytes(key.toPath());require(before.equals(after)&&Arrays.equals(keyBefore,keyAfter),"ADB pairing identity did not reload from private app storage");OnDeviceAdb.resetForValidation();
    }
    private View described(View view,String prefix){if(view.getContentDescription()!=null&&view.getContentDescription().toString().startsWith(prefix))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=described(((ViewGroup)view).getChildAt(i),prefix);if(found!=null)return found;}return null;}
    private String visibleOrder(String order,String hidden){Set<String> inactive=new HashSet<>(Arrays.asList(hidden.split(",")));List<String> active=new ArrayList<>();for(String id:order.split(","))if(!inactive.contains(id))active.add(id);return String.join(",",active);}
    private TextView textView(View root,String text){if(root instanceof TextView&&text.equals(((TextView)root).getText().toString()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=textView(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private View textContaining(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=textContaining(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void findDescriptions(View root,String prefix,List<View> found){if(root.getContentDescription()!=null&&root.getContentDescription().toString().startsWith(prefix))found.add(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)findDescriptions(((ViewGroup)root).getChildAt(i),prefix,found);}
    private boolean dashboardEditing()throws Exception{return Boolean.TRUE.equals(field(panel(),"editing"));}
    private void waitForStableProfile()throws Exception{
        final String[] previous={null};final int[] stable={0};
        waitFor(()->{try{DashboardView current=panel();String key=controller.layoutKey(),stateKey=controller.state.layoutKey,rendered=(String)field(current,"renderedLayoutKey");if(!controller.state.bridgeReady||!key.equals(stateKey)||!key.equals(rendered)){previous[0]=null;stable[0]=0;return false;}if(key.equals(previous[0]))stable[0]++;else{previous[0]=key;stable[0]=1;}return stable[0]>=4;}catch(Exception ignored){previous[0]=null;stable[0]=0;return false;}} ,"Display profile/layout did not settle before native test");
    }
    private DashboardActions renderOnlyActions(List<String> calls){
        return (DashboardActions)java.lang.reflect.Proxy.newProxyInstance(DashboardActions.class.getClassLoader(),new Class<?>[]{DashboardActions.class},(proxy,method,args)->{
            String name=method.getName();if(args!=null&&args.length>0)calls.add(name+":"+args[0]);else calls.add(name);
            if(method.getReturnType()==boolean.class)return false;
            if(method.getReturnType()==int.class)return 0;
            return null;
        });
    }
    private void drawComponentFixture(DashboardView view,UiState state,String filename,boolean edit)throws Exception{
        state.showHeaderClock=false;state.showHeaderHz=true;state.showHeaderWifi=true;
        view.render(state);
        if(edit)view.beginEditing();
        view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));
        view.layout(0,0,1280,960);
        Bitmap bitmap=Bitmap.createBitmap(1280,960,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File output=new File(getTargetContext().getFilesDir(),filename);
        try(FileOutputStream stream=new FileOutputStream(output)){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream),"Could not write component fixture "+filename);}finally{bitmap.recycle();}
    }
    private void checkComponentRender()throws Exception{
        Context context=getTargetContext();Map<String,?> preferencesBefore=new HashMap<>(context.getSharedPreferences("display_profiles",Context.MODE_PRIVATE).getAll());List<String> callbacks=new ArrayList<>();DashboardView view=new DashboardView(context,renderOnlyActions(callbacks));
        UiState state=new UiState();state.profile="Render fixture";state.main=61;state.upper=37;state.volume=48;state.mainAudio=72;state.upperAudio=29;
        state.mainApp="Fixture Main";state.upperApp="Fixture External";state.battery=73;state.wifiConnected=true;state.refreshHz=60;state.mainFps=60;state.upperFps=30;
        state.temperatureC=42.5f;state.powerW=7.2f;state.usedMemoryGb=3.1f;state.totalMemoryGb=8f;state.mainAudioAvailable=true;state.upperAudioAvailable=true;
        state.controls.put("refresh_mode",new UiState.ControlStatus(true,true,""));state.controls.get("refresh_mode").value=60;
        String[] fixtures={"componentrender-handheld-horizontal.png","componentrender-handheld-vertical.png","componentrender-external-horizontal.png","componentrender-external-vertical.png","componentrender-edit.png"};
        state.layoutKey="render-handheld";state.externalConnected=false;state.displayMode=DisplayModePolicy.DUAL;state.bridgeReady=true;state.horizontalSliders=true;
        drawComponentFixture(view,state,fixtures[0],false);
        state.horizontalSliders=false;drawComponentFixture(view,state,fixtures[1],false);
        state.layoutKey="render-external";state.externalConnected=true;state.displayMode=DisplayModePolicy.DOCKED;state.horizontalSliders=true;
        drawComponentFixture(view,state,fixtures[2],false);
        state.horizontalSliders=false;drawComponentFixture(view,state,fixtures[3],false);
        state.horizontalSliders=true;drawComponentFixture(view,state,fixtures[4],true);

        callbacks.clear();view=new DashboardView(context,renderOnlyActions(callbacks));state.layoutKey="render-interaction";state.externalConnected=true;state.horizontalSliders=true;view.render(state);
        view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,960);
        @SuppressWarnings("unchecked") Map<String,View> faders=(Map<String,View>)field(view,"faders");View main=faders.get("main");
        require(main!=null&&main.getWidth()>0&&main.getHeight()>0,"Main slider fixture is not laid out");
        callbacks.clear();
        require(main.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)),"Slider keyboard adjustment was not handled");
        require(callbacks.contains("setMain:66"),"Keyboard adjustment did not report the expected value: "+callbacks);
        callbacks.clear();Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,44f);
        require(main.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress),"Slider accessibility progress action was not handled");
        require(callbacks.contains("setMain:44"),"Accessibility progress did not report the expected value: "+callbacks);
        view.enableControllerNavigation(true);callbacks.clear();
        require(main.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)),"Controller slider adjustment was not handled");
        require(callbacks.contains("setMain:49"),"Controller adjustment did not report the expected value: "+callbacks);
        require(main.onKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT)),"Controller adjustment key release leaked out of the slider");
        view.enableControllerNavigation(false);
        RectF track=new RectF((RectF)field(main,"track"));float density=context.getResources().getDisplayMetrics().density;
        for(boolean horizontal:new boolean[]{true,false}){
            state.horizontalSliders=horizontal;view.render(state);view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,960);main=faders.get("main");Bitmap frame=Bitmap.createBitmap(1280,960,Bitmap.Config.ARGB_8888);view.draw(new Canvas(frame));frame.recycle();
            track=new RectF((RectF)field(main,"track"));float startX=horizontal?(track.left+track.width()*.35f)*density:track.centerX()*density;
            float startY=horizontal?track.centerY()*density:(track.bottom-track.height()*.35f)*density;
            long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,startX,startY,0);main.dispatchTouchEvent(down);down.recycle();
            MotionEvent move=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_MOVE,horizontal?(track.left+track.width()*.7f)*density:track.centerX()*density,horizontal?track.centerY()*density:(track.bottom-track.height()*.7f)*density,0);main.dispatchTouchEvent(move);move.recycle();
            require(callbacks.stream().anyMatch(value->value.startsWith("previewMain:")),"Slider drag did not emit a temporary preview in "+(horizontal?"horizontal":"vertical")+" mode: "+callbacks);
            callbacks.clear();MotionEvent cancel=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_CANCEL,startX,startY,0);main.dispatchTouchEvent(cancel);cancel.recycle();
            require(callbacks.stream().anyMatch(value->value.startsWith("setMain:")),"Cancelled slider preview did not restore through its callback");
        }
        for(String filename:fixtures)require(new File(context.getFilesDir(),filename).length()>0,"Component fixture missing: "+filename);
        require(preferencesBefore.equals(context.getSharedPreferences("display_profiles",Context.MODE_PRIVATE).getAll()),"Standalone slider interactions changed saved display profile preferences");
    }
    private void tapView(UiAutomation automation,View view)throws Exception{
        int[] location=new int[2];view.getLocationOnScreen(location);float x=location[0]+view.getWidth()/2f,y=location[1]+view.getHeight()/2f;
        tapAt(automation,x,y);
    }
    private void tapTopRightBadge(UiAutomation automation,View view)throws Exception{
        int[] location=new int[2];view.getLocationOnScreen(location);float inset=22*getTargetContext().getResources().getDisplayMetrics().density;
        tapAt(automation,location[0]+view.getWidth()-inset,location[1]+inset);
    }
    private void tapAt(UiAutomation automation,float x,float y)throws Exception{
        long down=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Touch down was not injected");event.recycle();
        long up=SystemClock.uptimeMillis()+70;event=MotionEvent.obtain(down,up,MotionEvent.ACTION_UP,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Touch up was not injected");event.recycle();
    }
    private void tapLocal(View view,float x,float y){long down=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);view.dispatchTouchEvent(event);event.recycle();long up=down+70;event=MotionEvent.obtain(down,up,MotionEvent.ACTION_UP,x,y,0);view.dispatchTouchEvent(event);event.recycle();}
    private void longPressView(UiAutomation automation,View view)throws Exception{
        int[] location=new int[2];view.getLocationOnScreen(location);float x=location[0]+view.getWidth()/2f,y=location[1]+view.getHeight()/2f;
        long down=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Long-press down was not injected");event.recycle();
        Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L);long up=SystemClock.uptimeMillis();event=MotionEvent.obtain(down,up,MotionEvent.ACTION_UP,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Long-press up was not injected");event.recycle();
    }
    private void injectTouch(UiAutomation automation,long down,long at,int action,float x,float y)throws Exception{
        MotionEvent event=MotionEvent.obtain(down,Math.max(down,at),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{require(automation.injectInputEvent(event,true),"Fader gesture event was not injected: "+action);}finally{event.recycle();}
    }
    private boolean editingOnUi()throws Exception{
        final boolean[] result={false};final String[] error={null};
        ui(()->{try{OverlayService currentService=OverlayService.current;Object candidate=currentService==null?null:field(currentService,"panel");if(!(candidate instanceof DashboardView)){error[0]="Dashboard panel is null during gesture check (service="+(currentService!=null)+", accessibilityConnected="+(DeckAccessibilityService.connected!=null)+")";return;}DashboardView current=(DashboardView)candidate;if(!current.isAttachedToWindow()||current.getWidth()<=0||current.getHeight()<=0){error[0]="Dashboard panel detached during gesture check (display="+(current.getDisplay()==null?"null":current.getDisplay().getDisplayId())+", size="+current.getWidth()+"x"+current.getHeight()+")";return;}result[0]=Boolean.TRUE.equals(field(current,"editing"));}catch(Exception e){error[0]="Could not read dashboard edit state: "+e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);return result[0];
    }
    private DashboardView waitForStableFaderPanel()throws Exception{
        final DashboardView[] previous={null},stablePanel={null};final String[] previousBounds={null};final int[] stable={0};long deadline=SystemClock.elapsedRealtime()+10_000L;
        while(SystemClock.elapsedRealtime()<deadline){ui(()->{try{OverlayService currentService=OverlayService.current;if(currentService==null){stable[0]=0;previous[0]=null;return;}service=currentService;DashboardView current=panel();if(current==null||!current.isAttachedToWindow()||current.getWidth()<=0||current.getHeight()<=0||current.getDisplay()==null||current.getDisplay().getDisplayId()!=0){stable[0]=0;previous[0]=null;return;}WindowManager.LayoutParams params=(WindowManager.LayoutParams)((View)field(currentService,"panelRoot")).getLayoutParams();WindowManager manager=(WindowManager)((Context)field(currentService,"uiContext")).getSystemService(Context.WINDOW_SERVICE);Rect bounds=manager.getMaximumWindowMetrics().getBounds();int expectedType=DeckAccessibilityService.connected!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;String geometry=current.getWidth()+"x"+current.getHeight()+"/"+bounds.width()+"x"+bounds.height()+"/"+params.width+"x"+params.height+"/"+params.y+"/"+params.getFitInsetsTypes()+"/"+params.type;if(params.type!=expectedType||current.getWidth()!=bounds.width()||current.getHeight()!=bounds.height()||params.width!=WindowManager.LayoutParams.MATCH_PARENT||params.height!=WindowManager.LayoutParams.MATCH_PARENT||params.y!=0||params.getFitInsetsTypes()!=0){stable[0]=0;previous[0]=null;return;}if(current==previous[0]&&geometry.equals(previousBounds[0]))stable[0]++;else stable[0]=1;previous[0]=current;previousBounds[0]=geometry;if(stable[0]>=3)stablePanel[0]=current;}catch(Exception ignored){stable[0]=0;previous[0]=null;}});if(stablePanel[0]!=null)return stablePanel[0];Thread.sleep(150L);}
        throw new AssertionError("Accessibility overlay panel did not stay attached on display 0 with stable fullscreen bounds for three polls");
    }
    private boolean sameAttachedPanel(DashboardView expected)throws Exception{final boolean[] same={false};ui(()->{try{DashboardView current=panel();same[0]=current==expected&&current.isAttachedToWindow()&&current.getWidth()>0&&current.getHeight()>0;}catch(Exception ignored){}});return same[0];}
    private void finishEditingIfPresent(){ui(()->{try{service=OverlayService.current;DashboardView current=panel();if(current!=null&&current.isAttachedToWindow()&&Boolean.TRUE.equals(field(current,"editing"))){View done=described(current,"Done editing");if(done!=null)done.performClick();}}catch(Exception ignored){}});}
    private String faderTrackDiagnostic(DashboardView before,DashboardView after){StringBuilder details=new StringBuilder("profile=").append(controller.layoutKey()).append(", stateHorizontal=").append(controller.state.horizontalSliders).append(", panelRefChanged=").append(before!=after);try{@SuppressWarnings("unchecked") Map<String,View> views=(Map<String,View>)field(after,"faders");View volume=views.get("volume");RectF track=(RectF)field(volume,"track");details.append(", panel=").append(after.getWidth()).append("x").append(after.getHeight()).append(" attached=").append(after.isAttachedToWindow()).append(", volumeVisibility=").append(volume.getVisibility()).append(", volumeSize=").append(volume.getWidth()).append("x").append(volume.getHeight()).append(", horizontal=").append(field(volume,"horizontal")).append(", parent=").append(volume.getParent()==null?"null":volume.getParent().getClass().getSimpleName()).append(", track=").append(track==null?"null":track.toString());}catch(Exception e){details.append(", faderError=").append(e);}return details.toString();}
    private View faderRow(View fader,ViewGroup bank){ViewParent parent=fader.getParent();return parent instanceof View&&((View)parent).getParent()==bank?(View)parent:fader;}
    private void bringFaderIntoView(View fader)throws Exception{
        ui(()->fader.requestRectangleOnScreen(new Rect(0,0,fader.getWidth(),fader.getHeight()),true));
        waitFor(()->{Rect visible=new Rect();return fader.getGlobalVisibleRect(visible);},"Fader could not scroll into view");
        waitFor(()->{try{RectF track=(RectF)field(fader,"track");return track.width()>0&&track.height()>0;}catch(Exception e){return false;}},"Fader track was not drawn after scrolling into view");
    }
    private void assertGlyphFootprint(View button,String primary,String alternate,float density)throws Exception{
        for(String member:Arrays.asList(primary,alternate)){Drawable glyph=(Drawable)field(button,member);float width=glyph.getIntrinsicWidth()/density,height=glyph.getIntrinsicHeight()/density;require(Math.abs(width-16f)<=1&&Math.abs(height-16f)<=1,"Mute/eye glyph is not 16dp: "+member+"="+width+"x"+height);}
    }
    private JSONObject audioStatus()throws Exception{return new BridgeClient(getTargetContext()).request("status",null).optJSONObject("audio");}
    private void exerciseAppAudioMute(UiAutomation automation,String id,View fader,View button,boolean routeAvailable,boolean upper,int gain,String profileKey,ProfileStore profiles)throws Exception{
        bringFaderIntoView(fader);
        String muteLabel=id.equals("audio_main")?"Mute main app audio":"Mute external app audio",unmuteLabel=id.equals("audio_main")?"Unmute main app audio":"Unmute external app audio";
        require(button.getContentDescription()!=null&&button.getContentDescription().toString().startsWith(muteLabel),"App-audio mute button label/state is incorrect before muting: "+button.getContentDescription());
        if(routeAvailable)tapView(automation,button);else ui(()->controller.toggleAudioMute(id));
        waitFor(()->upper?controller.state.upperAudioMuted&&controller.state.upperAudio==0:controller.state.mainAudioMuted&&controller.state.mainAudio==0,"App-audio mute did not set the selected profile gain to zero");
        require(profiles.audioMuted(profileKey,upper),"App-audio mute flag did not persist for its profile and screen");
        require(profiles.audioRestore(profileKey,upper)==gain,"App-audio mute did not remember the exact prior nonzero gain");
        waitFor(()->{try{JSONObject status=audioStatus();return status!=null&&status.optInt(upper?"upperGain":"mainGain",-1)==0;}catch(Exception e){return false;}},"Audio backend did not receive the muted zero gain");
        require(button.getContentDescription()!=null&&button.getContentDescription().toString().startsWith(unmuteLabel),"App-audio button did not expose Unmute after muting");
        if(routeAvailable)tapView(automation,button);else ui(()->controller.toggleAudioMute(id));
        waitFor(()->upper?!controller.state.upperAudioMuted&&controller.state.upperAudio==gain:!controller.state.mainAudioMuted&&controller.state.mainAudio==gain,"App-audio unmute did not restore the exact previous gain");
        require(!profiles.audioMuted(profileKey,upper)&&profiles.audioRestore(profileKey,upper)==gain,"App-audio unmute did not restore persisted mute/gain state");
        waitFor(()->{try{JSONObject status=audioStatus();return status!=null&&status.optInt(upper?"upperGain":"mainGain",-1)==gain;}catch(Exception e){return false;}},"Audio backend did not restore the exact app gain");
    }
    private void checkFaderMute(UiAutomation automation)throws Exception{
        rebindEnabledAccessibilityService(automation);
        Context target=getTargetContext();target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{try{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;}catch(Exception ignored){return false;}},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{try{DashboardView current=panel();return current!=null&&current.isAttachedToWindow()&&current.getWidth()>0&&current.getHeight()>0;}catch(Exception ignored){return false;}},"Dashboard did not attach");waitForStableProfile();
        final String key=controller.layoutKey(),originalOrder=controller.state.controlOrder,originalHidden=controller.state.hiddenControls,originalWide=controller.state.wideControls;
        final int originalMain=controller.state.main,originalUpper=controller.state.upper,originalVolume=controller.state.volume,originalMainAudio=controller.state.mainAudio,originalUpperAudio=controller.state.upperAudio;
        final boolean originalHorizontal=controller.state.horizontalSliders,originalCompact=controller.state.compactControls,originalMasterMuted=controller.state.volumeMuted,originalMainMuted=controller.state.mainAudioMuted,originalUpperMuted=controller.state.upperAudioMuted;
        AudioManager audio=target.getSystemService(AudioManager.class);require(audio!=null,"Android media volume service is unavailable");final int originalStreamVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);final boolean originalStreamMuted=audio.isStreamMute(AudioManager.STREAM_MUSIC);
        SharedPreferences prefs=target.getSharedPreferences("display_profiles",Context.MODE_PRIVATE);Map<String,?> originalPrefs=new HashMap<>(prefs.getAll());
        String isolationProfile=ControlLogic.outputKey("fadermute-isolation-test");Set<String> prefKeys=new LinkedHashSet<>(Arrays.asList(key+":main",key+":upper",key+":volume",key+":mainAudio",key+":upperAudio",key+":mainAudioLast",key+":upperAudioLast",key+":mainAudioMuted",key+":upperAudioMuted",key+":order",key+":hidden",key+":wide",key+":horizontal",key+":compact",isolationProfile+":mainAudio",isolationProfile+":upperAudio",isolationProfile+":mainAudioLast",isolationProfile+":upperAudioLast",isolationProfile+":mainAudioMuted",isolationProfile+":upperAudioMuted"));prefKeys.addAll(sharedDashboardPreferenceKeys());
        LinkedHashSet<String> fixtureOrder=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanOrder(originalOrder).split(",")));LinkedHashSet<String> fixtureHidden=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(originalHidden).split(",")));
        for(String id:Arrays.asList("both","upper","main","audio_upper","audio_main","volume")){fixtureOrder.add(id);fixtureHidden.remove(id);}
        try{
            if(editingOnUi())finishEditingIfPresent();
            ui(()->{controller.updateLayout(controller.layoutKey(),String.join(",",fixtureOrder),String.join(",",fixtureHidden));controller.style(controller.layoutKey(),true,originalCompact);});waitForStableProfile();
            DashboardView view=panel();@SuppressWarnings("unchecked") Map<String,View> faders=(Map<String,View>)field(view,"faders");@SuppressWarnings("unchecked") Map<String,View> muteButtons=(Map<String,View>)field(view,"audioMuteButtons");@SuppressWarnings("unchecked") Map<String,View> eyeButtons=(Map<String,View>)field(view,"blackButtons");
            ViewGroup brightness=(ViewGroup)field(view,"brightnessBank"),audioBank=(ViewGroup)field(view,"audioBank");String[] ids={"both","upper","main","audio_upper","audio_main","volume"};RectF reference=null;int expectedRow=Math.round(54*target.getResources().getDisplayMetrics().density);float density=target.getResources().getDisplayMetrics().density;
            for(String id:ids){View fader=faders.get(id);require(fader!=null&&fader.getVisibility()==View.VISIBLE,"Fader fixture is missing "+id);bringFaderIntoView(fader);View row=faderRow(fader,id.startsWith("audio_")||id.equals("volume")?audioBank:brightness);require(Math.abs(row.getHeight()-expectedRow)<=1,"Fader row height does not match 54dp: "+id+" measured="+row.getHeight()+" expected="+expectedRow);require(Boolean.TRUE.equals(field(fader,"horizontal")),"Fader is not horizontal: "+id);RectF track=new RectF((RectF)field(fader,"track"));if(reference==null)reference=track;else require(Math.abs(track.left-reference.left)<=1&&Math.abs(track.top-reference.top)<=1&&Math.abs(track.right-reference.right)<=1&&Math.abs(track.bottom-reference.bottom)<=1,"Fader track bounds differ from the shared strip geometry: "+id+"="+track+" expected="+reference);}
            for(String id:Arrays.asList("volume","audio_main","audio_upper")){View button=muteButtons.get(id);require(button!=null&&button.getVisibility()==View.VISIBLE&&button.isClickable()&&button.isFocusable(),"Mute button is missing or inaccessible: "+id);require(Math.abs(button.getWidth()/density-40)<=1&&Math.abs(button.getHeight()/density-44)<=1,"Mute button is not 40×44dp: "+id+" measured="+button.getWidth()+"×"+button.getHeight());String label=id.equals("volume")?"Mute master volume":id.equals("audio_main")?"Mute main app audio":"Mute external app audio";require(button.getContentDescription()!=null&&button.getContentDescription().toString().startsWith(label),"Mute button accessibility label is wrong: "+button.getContentDescription());assertGlyphFootprint(button,"sound","mutedGlyph",density);}
            for(String id:Arrays.asList("main","upper")){View button=eyeButtons.get(id);require(button!=null&&button.getVisibility()==View.VISIBLE&&button.isClickable()&&button.isFocusable(),"Brightness eye control disappeared: "+id);require(Math.abs(button.getWidth()/density-40)<=1&&Math.abs(button.getHeight()/density-44)<=1,"Brightness eye control is not 40×44dp: "+id);require(button.getContentDescription()!=null&&button.getContentDescription().toString().startsWith(id.equals("main")?"Turn off main screen":"Turn off external screen"),"Brightness eye accessibility label changed: "+button.getContentDescription());assertGlyphFootprint(button,"eye","closedEye",density);}

            ProfileStore profiles=new ProfileStore(target);int mainProbe=37,upperProbe=63;ui(()->{controller.setMainAudio(mainProbe);controller.setUpperAudio(upperProbe);});
            waitFor(()->controller.state.mainAudio==mainProbe&&!controller.state.mainAudioMuted&&controller.state.upperAudio==upperProbe&&!controller.state.upperAudioMuted,"Could not establish nonzero per-screen app-audio mute fixtures");
            waitFor(()->{try{JSONObject status=audioStatus();return status!=null&&status.optInt("mainGain",-1)==mainProbe&&status.optInt("upperGain",-1)==upperProbe;}catch(Exception e){return false;}},"Audio backend did not receive the app-gain fixtures");
            View masterMute=muteButtons.get("volume");bringFaderIntoView(faders.get("volume"));Rect masterVisible=new Rect();require(masterMute.getGlobalVisibleRect(masterVisible),"Master mute button is not visible");
            if(originalStreamMuted||audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0||controller.state.volume==0)ui(()->controller.setVolume(45));
            waitFor(()->!audio.isStreamMute(AudioManager.STREAM_MUSIC)&&audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0,"Could not establish a nonzero unmuted media-volume fixture");int streamBeforeMute=audio.getStreamVolume(AudioManager.STREAM_MUSIC);int stateVolumeBeforeMute=controller.state.volume;
            require(masterMute.getContentDescription()!=null&&masterMute.getContentDescription().toString().startsWith("Mute master volume"),"Master mute icon is not in the Mute state");tapView(automation,masterMute);
            waitFor(()->controller.state.volumeMuted&&audio.isStreamMute(AudioManager.STREAM_MUSIC),"Master mute icon did not mute the Android media stream");
            require(masterMute.getContentDescription().toString().startsWith("Unmute master volume"),"Master mute icon did not expose its Unmute state");
            // Let at least one normal controller/audio poll run while muted. Android may
            // report stream volume 0 during mute, but that must not overwrite the saved
            // audible level used by Unmute.
            Thread.sleep(1_300L);
            require(controller.state.volumeMuted&&audio.isStreamMute(AudioManager.STREAM_MUSIC),"Master stream lost its muted state during the normal status-poll interval");
            tapView(automation,masterMute);waitFor(()->!controller.state.volumeMuted&&!audio.isStreamMute(AudioManager.STREAM_MUSIC),"Master Unmute icon did not restore the Android media stream");
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==streamBeforeMute,"Master Unmute did not restore the exact pre-mute stream level");require(controller.state.volume==stateVolumeBeforeMute,"Master Unmute changed the saved media volume level");

            ui(()->profiles.audio(isolationProfile,29,71));profiles.audioMuted(isolationProfile,false,true);
            require(profiles.audioMuted(isolationProfile,false)&&profiles.audioRestore(isolationProfile,false)==29&&!profiles.audioMuted(key,false),"Main app-audio mute leaked between output profiles");
            require(!profiles.audioMuted(isolationProfile,true)&&profiles.audio(isolationProfile,true)==71,"Main app-audio mute changed the other profile's External gain");
            boolean mainRoute=controller.state.mainAudioAvailable&&controller.state.bridgeReady,upperRoute=controller.state.upperAudioAvailable&&controller.state.bridgeReady;
            mainMuteRouteVerified=mainRoute;externalMuteRouteVerified=upperRoute;
            exerciseAppAudioMute(automation,"audio_main",faders.get("audio_main"),muteButtons.get("audio_main"),mainRoute,false,mainProbe,key,profiles);
            require(!controller.state.upperAudioMuted&&controller.state.upperAudio==upperProbe,"Main app-audio mute changed External gain or mute state");
            exerciseAppAudioMute(automation,"audio_upper",faders.get("audio_upper"),muteButtons.get("audio_upper"),upperRoute,true,upperProbe,key,profiles);
            require(!controller.state.mainAudioMuted&&controller.state.mainAudio==mainProbe,"External app-audio mute changed Main gain or mute state");
            require(profiles.audioMuted(isolationProfile,false)&&profiles.audioRestore(isolationProfile,false)==29&&!profiles.audioMuted(key,false),"Main app-audio mute state crossed profile keys");
        }finally{
            try{if(editingOnUi())finishEditingIfPresent();}catch(Exception ignored){}
            ui(()->{
                controller.setMain(originalMain);controller.setUpper(originalUpper);controller.setMainAudio(originalMainAudio);controller.setUpperAudio(originalUpperAudio);controller.setVolume(originalVolume);
                controller.updateLayout(controller.layoutKey(),originalOrder,originalHidden);controller.wide(controller.layoutKey(),originalWide);controller.style(controller.layoutKey(),originalHorizontal,originalCompact);
                if(controller.state.mainAudioMuted!=originalMainMuted)controller.toggleAudioMute("audio_main");
                if(controller.state.upperAudioMuted!=originalUpperMuted)controller.toggleAudioMute("audio_upper");
            });
            try{if(audio.isStreamMute(AudioManager.STREAM_MUSIC)!=originalStreamMuted)ui(()->controller.toggleAudioMute("volume"));}catch(Exception ignored){}
            if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=originalStreamVolume)audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalStreamVolume,0);
            if(audio.isStreamMute(AudioManager.STREAM_MUSIC)!=originalStreamMuted)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,originalStreamMuted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
            restorePreferenceKeys(prefs,originalPrefs,prefKeys);
            waitFor(()->controller.state.main==originalMain&&controller.state.upper==originalUpper&&(!originalStreamMuted&&controller.state.volume==originalVolume||originalStreamMuted)&&controller.state.mainAudio==originalMainAudio&&controller.state.upperAudio==originalUpperAudio&&controller.state.volumeMuted==originalStreamMuted&&controller.state.mainAudioMuted==originalMainMuted&&controller.state.upperAudioMuted==originalUpperMuted,"Fader mute test did not restore the original controller values");
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==originalStreamVolume&&audio.isStreamMute(AudioManager.STREAM_MUSIC)==originalStreamMuted,"Fader mute test did not restore exact media stream level/mute state");
            for(String prefKey:prefKeys)require(originalPrefs.containsKey(prefKey)==prefs.contains(prefKey)&&Objects.equals(originalPrefs.get(prefKey),prefs.getAll().get(prefKey)),"Fader mute test did not restore exact profile preference "+prefKey);
        }
    }
    private Set<String> wideSet(String value){Set<String> result=new LinkedHashSet<>();for(String id:ControlLogic.cleanHidden(value).split(","))if(!id.isEmpty())result.add(id);return result;}
    private JSONObject checkBluetoothWidth(UiAutomation automation)throws Exception{
        Context target=getTargetContext();target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{try{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;}catch(Exception ignored){return false;}} ,"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");waitForStableProfile();
        String key=controller.layoutKey(),before=controller.state.wideControls,order=controller.state.controlOrder,hidden=controller.state.hiddenControls;int columns=controller.state.actionColumns;
        LinkedHashSet<String> oldWide=new LinkedHashSet<>(wideSet(before));boolean wasWide=oldWide.contains("bluetooth");
        if(wasWide)ui(()->controller.setControlWide(key,"bluetooth",false));
        waitFor(()->controller.layoutKey().equals(key)&&!new HashSet<>(Arrays.asList(ControlLogic.cleanHidden(controller.state.wideControls).split(","))).contains("bluetooth"),"Bluetooth wide flag was not removed from the active output profile");
        require(controller.state.controlOrder.equals(order)&&controller.state.hiddenControls.equals(hidden)&&controller.state.actionColumns==columns,"Bluetooth width correction changed another part of the profile layout");
        String wideAfterValue=ControlLogic.cleanHidden(controller.state.wideControls);LinkedHashSet<String> wideAfter=new LinkedHashSet<>(wideSet(wideAfterValue));oldWide.remove("bluetooth");require(oldWide.equals(wideAfter),"Bluetooth width correction changed another control's width: profile="+key+" before="+before+" after="+wideAfterValue+" expected="+oldWide);
        Set<String> inactive=new HashSet<>(Arrays.asList(hidden.split(",")));List<String> active=new ArrayList<>();for(String id:order.split(","))if(ControlLogic.action(id)&&!inactive.contains(id))active.add(id);
        int bluetoothSpan=-1;for(ControlLogic.GridPlacement placement:ControlLogic.packActionGrid(active,controller.state.wideControls,columns))if(placement.id.equals("bluetooth"))bluetoothSpan=placement.span;
        if(!inactive.contains("bluetooth"))require(bluetoothSpan==1,"Visible Bluetooth tile still spans "+bluetoothSpan+" columns");
        return new JSONObject().put("profile_key",key).put("wide_before",before).put("wide_after",controller.state.wideControls).put("bluetooth_was_wide",wasWide).put("bluetooth_visible",!inactive.contains("bluetooth")).put("bluetooth_span",bluetoothSpan).put("other_wide_preserved",true).put("action_columns",columns);
    }
    private void holdAndAdjustTrack(UiAutomation automation,DashboardView expectedPanel,View fader,RectF track,int originalValue,int targetValue)throws Exception{
        int[] location=new int[2];fader.getLocationOnScreen(location);float density=getTargetContext().getResources().getDisplayMetrics().density;
        float startX=location[0]+(track.left+track.width()*Math.max(0,Math.min(100,originalValue))/100f)*density;
        float targetX=location[0]+(track.left+track.width()*targetValue/100f)*density,y=location[1]+track.centerY()*density;
        long down=SystemClock.uptimeMillis();boolean released=false;
        try{
            injectTouch(automation,down,down,MotionEvent.ACTION_DOWN,startX,y);
            Thread.sleep(ViewConfiguration.getLongPressTimeout()+140L);
            require(sameAttachedPanel(expectedPanel),"Fullscreen accessibility panel was replaced/detached during Master-track hold");
            require(!editingOnUi(),"Holding on the fader track entered control edit mode");
            require(controller.state.volume==originalValue,"Holding on the fader track changed Master volume before the drag");
            injectTouch(automation,down,SystemClock.uptimeMillis()+30L,MotionEvent.ACTION_MOVE,targetX,y);
            Thread.sleep(180L);
            require(sameAttachedPanel(expectedPanel),"Fullscreen accessibility panel was replaced/detached during Master-track drag");
            injectTouch(automation,down,SystemClock.uptimeMillis()+30L,MotionEvent.ACTION_UP,targetX,y);released=true;
        }finally{if(!released)injectTouch(automation,down,SystemClock.uptimeMillis()+30L,MotionEvent.ACTION_UP,targetX,y);}
    }
    private void holdLabelAndReorder(UiAutomation automation,DashboardView expectedPanel,View source,View target,float labelX,float labelY)throws Exception{
        int[] sourceLocation=new int[2],targetLocation=new int[2];source.getLocationOnScreen(sourceLocation);target.getLocationOnScreen(targetLocation);
        float sx=sourceLocation[0]+labelX,sy=sourceLocation[1]+labelY,tx=targetLocation[0]+target.getWidth()/2f,ty=targetLocation[1]+target.getHeight()/2f;
        long down=SystemClock.uptimeMillis();boolean released=false;
        try{
            injectTouch(automation,down,down,MotionEvent.ACTION_DOWN,sx,sy);
            Thread.sleep(ViewConfiguration.getLongPressTimeout()+160L);
            require(sameAttachedPanel(expectedPanel),"Fullscreen accessibility panel was replaced/detached during fader-label hold");
            require(editingOnUi(),"Fader drag left control edit mode");
            injectTouch(automation,down,SystemClock.uptimeMillis()+25L,MotionEvent.ACTION_MOVE,sx,sy);
            Thread.sleep(90L);
            injectTouch(automation,down,SystemClock.uptimeMillis()+25L,MotionEvent.ACTION_MOVE,tx,ty);
            Thread.sleep(140L);
            require(sameAttachedPanel(expectedPanel),"Fullscreen accessibility panel was replaced/detached during fader-label drag");
            injectTouch(automation,down,SystemClock.uptimeMillis()+25L,MotionEvent.ACTION_UP,tx,ty);released=true;
        }finally{if(!released)injectTouch(automation,down,SystemClock.uptimeMillis()+25L,MotionEvent.ACTION_UP,tx,ty);}
    }
    private EditText editText(View root){if(root instanceof EditText)return (EditText)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){EditText found=editText(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private AlertDialog activeDialog()throws Exception{return (AlertDialog)field(service,"activeDialog");}
    private void chooseDialogItem(String label)throws Exception{
        waitFor(()->{try{AlertDialog dialog=activeDialog();if(dialog==null||dialog.getListView()==null||dialog.getListView().getAdapter()==null)return false;for(int i=0;i<dialog.getListView().getAdapter().getCount();i++)if(label.contentEquals(dialog.getListView().getAdapter().getItem(i).toString()))return true;}catch(Exception ignored){}return false;},"Dialog item missing: "+label);
        ui(()->{try{ListView list=activeDialog().getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(label.contentEquals(list.getAdapter().getItem(i).toString())){list.performItemClick(list.getChildAt(i),i,list.getAdapter().getItemId(i));return;}throw new AssertionError("Dialog item missing: "+label);}catch(Exception e){throw new RuntimeException(e);}});
    }
    private void drag(UiAutomation automation,View source,View target)throws Exception{
        int[] from=new int[2],to=new int[2];source.getLocationOnScreen(from);target.getLocationOnScreen(to);float sx=from[0]+source.getWidth()/2f,sy=from[1]+source.getHeight()/2f,tx=to[0]+target.getWidth()/2f,ty=to[1]+target.getHeight()/2f;
        long down=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,sx,sy,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);require(automation.injectInputEvent(event,true),"Touch down was not injected");event.recycle();
        Thread.sleep(ViewConfiguration.getLongPressTimeout()+180L);long held=SystemClock.uptimeMillis();event=MotionEvent.obtain(down,held,MotionEvent.ACTION_MOVE,sx,sy,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,true);event.recycle();
        Thread.sleep(80);long moving=SystemClock.uptimeMillis();event=MotionEvent.obtain(down,moving,MotionEvent.ACTION_MOVE,tx,ty,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,true);event.recycle();
        Thread.sleep(180);long up=SystemClock.uptimeMillis();event=MotionEvent.obtain(down,up,MotionEvent.ACTION_UP,tx,ty,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,true);event.recycle();
    }
    private void checkSettingsAndDashboardEditing(UiAutomation automation)throws Exception{
        final String profileKey=controller.layoutKey(),identity=controller.identity(),profile=controller.state.profile;
        ProfileStore profiles=new ProfileStore(getTargetContext());String savedName=identity.isEmpty()?null:profiles.name(identity);
        String oldOrder=controller.state.controlOrder,oldHidden=controller.state.hiddenControls,oldWide=controller.state.wideControls;
        try{
            DashboardView visible=panel();ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Settings sheet did not open");
            final boolean[] settingsPanelRetained={false},visibleActionButton={false},closeButtonPresent={false};ui(()->{try{AlertDialog dialog=activeDialog();Button[] buttons={dialog.getButton(AlertDialog.BUTTON_POSITIVE),dialog.getButton(AlertDialog.BUTTON_NEGATIVE),dialog.getButton(AlertDialog.BUTTON_NEUTRAL)};for(Button button:buttons)if(button!=null&&button.getVisibility()==View.VISIBLE&&button.getText().length()>0)visibleActionButton[0]=true;settingsPanelRetained[0]=panel()==visible&&visible.getParent()!=null;View decor=dialog.getWindow().getDecorView();closeButtonPresent[0]=textView(decor,"×")!=null;for(String removed:new String[]{"Stop overlay","Rename output","Back to controls"})require(textView(decor,removed)==null,"Removed Settings action returned: "+removed);
                if(!identity.isEmpty()){EditText name=editText(decor);require(name!=null,"Inline output-name field missing");name.setText("DuoDash Validation Name");require("DuoDash Validation Name".equals(controller.state.profile)&&"DuoDash Validation Name".equals(profiles.name(identity)),"Output name did not save immediately to its stable display identity");}
                textView(decor,"×").performClick();}catch(Exception e){throw new RuntimeException(e);}});require(!visibleActionButton[0],"Settings still shows a bottom action button");require(settingsPanelRetained[0],"Settings hid or replaced the dashboard");require(closeButtonPresent[0],"Settings close X is missing");
            waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Settings X did not close the sheet");require(panel()==visible,"Closing Settings removed the retained dashboard");
            ui(()->controller.updateLayout(profileKey,ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden()));Thread.sleep(200);
            Map<String,View> tiles=(Map<String,View>)field(panel(),"tiles");View source=tiles.get("swap"),target=tiles.get("down");require(source!=null&&target!=null,"Default action widgets missing");String preMove=controller.state.controlOrder;
            final View firstSource=source;ui(firstSource::performLongClick);chooseDialogItem("Move");
            waitFor(()->{try{return (boolean)field(panel(),"editing");}catch(Exception e){return false;}},"Move mode did not start");
            float originalFan=controller.state.fanPercent;ui(()->{controller.state.fanPercent=Float.isNaN(originalFan)?1:originalFan+1;panel().render(controller.state);require(tiles.get("swap")==firstSource&&firstSource.getForeground()!=null,"A status poll recreated controls during move mode");});
            ui(firstSource::performClick);require(!(boolean)field(panel(),"editing"),"Tapping a widget did not exit move mode");require(preMove.equals(controller.state.controlOrder)&&!controller.state.busy,"Tap-to-exit activated a control");
            source=tiles.get("swap");target=tiles.get("down");final View dragSource=source,dragTarget=target;
            ui(()->dragSource.performLongClick());chooseDialogItem("Move");ui(()->{});drag(automation,dragSource,dragTarget);
            waitFor(()->!controller.state.controlOrder.equals(preMove),"Dragging did not reorder the quick-action widgets");require(controller.state.controlOrder.equals(ControlLogic.moveOrder(preMove,"swap","down")),"Drag landed in the wrong order");
            ui(()->tiles.get("swap").performLongClick());chooseDialogItem("Replace");
            waitFor(()->{try{return activeDialog()!=null&&activeDialog().getListView()!=null;}catch(Exception e){return false;}},"Inactive-control picker did not open");
            ui(()->{try{AlertDialog dialog=activeDialog();ListView list=dialog.getListView();require(textView(dialog.getWindow().getDecorView(),"Auto-rotate")!=null,"Inactive control missing from replacement picker");require(textView(dialog.getWindow().getDecorView(),"Bluetooth")==null,"Active control appeared in replacement picker");require(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)!=null&&"Choose app".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText()),"Choose app option missing");}catch(Exception e){throw new RuntimeException(e);}});
            String[] expectedReplacement=ControlLogic.replaceLayout(controller.state.controlOrder,controller.state.hiddenControls,"swap","rotation");require(expectedReplacement!=null,"Replacement test setup was not a valid inactive control");chooseDialogItem("Auto-rotate");String[] replaced=expectedReplacement;
            require(visibleOrder(controller.state.controlOrder,controller.state.hiddenControls).equals(visibleOrder(replaced[0],replaced[1])),"Replacement did not put the replacement in the old visible slot");require(controller.state.hiddenControls.equals(replaced[1]),"Replacement hidden-controls mismatch; expected="+replaced[1]+" actual="+controller.state.hiddenControls);require(ControlLogic.actionCount(controller.state.controlOrder,controller.state.hiddenControls)==9,"Replacement changed the saved nine-action count");
            require(profiles.order(profileKey).equals(controller.state.controlOrder)&&profiles.hidden(profileKey).equals(controller.state.hiddenControls),"Replacement did not persist for the active profile");
        }finally{
            if(!identity.isEmpty()){if(savedName==null)getTargetContext().getSharedPreferences("display_profiles",0).edit().remove("outputName:"+identity).commit();else profiles.name(identity,savedName);controller.state.profile=profile;}
            ui(()->{controller.updateLayout(profileKey,oldOrder,oldHidden);controller.wide(profileKey,oldWide);controller.state.profile=profile;panel().render(controller.state);try{if(activeDialog()!=null)activeDialog().dismiss();}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    private void checkDeveloperOptionsHandoff(UiAutomation automation)throws Exception{
        String savedShortcuts=getTargetContext().getSharedPreferences("ui",0).getString("shortcuts","");
        boolean initiallyPaired=OnDeviceBridge.isPaired(getTargetContext());
        try{
            if(!initiallyPaired){
                require(BridgeActivationService.preparePairing(getTargetContext()),"Pairing notification permission is unavailable");
                Notification ready=activePairingNotification();require(ready!=null&&ready.actions!=null,"Pairing notification was not posted before Settings handoff");
                Notification.Action reply=null;for(Notification.Action action:ready.actions)if(action.getRemoteInputs()!=null&&action.getRemoteInputs().length>0)reply=action;
                require(reply!=null&&"duodash_pairing_code".equals(reply.getRemoteInputs()[0].getResultKey()),"Pairing notification is missing its inline code field");
                require(reply.actionIntent!=null&&!reply.actionIntent.isImmutable(),"Inline reply PendingIntent must be mutable for RemoteInput");
            }
            ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Settings did not open before reconnect handoff");
            ui(()->{try{
                AlertDialog settings=activeDialog();View root=settings.getWindow().getDecorView();TextView reconnect=textView(root,"Reconnect system controls");if(reconnect==null)reconnect=textView(root,"System controls connected");require(reconnect!=null,"Reconnect action missing in Settings");reconnect.performClick();
            }catch(Exception e){throw new RuntimeException(e);}});
            waitFor(()->{try{return activeDialog()!=null&&(textView(activeDialog().getWindow().getDecorView(),"Start pairing")!=null||textView(activeDialog().getWindow().getDecorView(),"Connect")!=null);}catch(Exception e){return false;}},"Wireless debugging setup did not open");
            ui(()->{try{AlertDialog dialog=activeDialog();TextView startPair=textView(dialog.getWindow().getDecorView(),"Start pairing");if(startPair!=null)startPair.performClick();else textView(dialog.getWindow().getDecorView(),"Open Developer options").performClick();}catch(Exception e){throw new RuntimeException(e);}});
            waitFor(()->{try{return activeDialog()==null&&panel()==null;}catch(Exception e){return false;}},"DuoDash overlay still covers Android Developer options");
            if(!initiallyPaired)require(activePairingNotification()!=null,"Pairing notification disappeared while Android Settings opened");
            boolean floatingWasEnabled=getTargetContext().getSharedPreferences("ui",0).getBoolean("floatingButton",true);View handle=(View)field(service,"handle");
            require(floatingWasEnabled?handle!=null&&handle.isAttachedToWindow():handle==null,"Settings handoff changed the saved floating-button preference");
            String focused=shellOutput(automation,"dumpsys activity activities | grep -E 'topResumedActivity|mFocusedApp' | head -4");
            require(focused.contains("com.android.settings"),"Android Developer options did not become the foreground app: "+focused);

            rebindEnabledAccessibilityService(automation);
            getTargetContext().getSharedPreferences("ui",0).edit().putString("shortcuts","139").commit();DeckAccessibilityService.connected.reloadShortcuts();
            ui(()->{DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,139));DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,139));});
            waitFor(()->panel()!=null,"Shortcut could not reopen DuoDash after the Android Settings handoff");
            ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"DuoDash Settings did not reopen after the handoff");
            ui(()->{try{AlertDialog settings=activeDialog();TextView reconnect=textView(settings.getWindow().getDecorView(),"Reconnect system controls");if(reconnect==null)reconnect=textView(settings.getWindow().getDecorView(),"System controls connected");require(reconnect!=null,"Reconnect action missing after returning by shortcut");reconnect.performClick();}catch(Exception e){throw new RuntimeException(e);}});
            waitFor(()->{try{return activeDialog()!=null&&(textView(activeDialog().getWindow().getDecorView(),"Start pairing")!=null||activeDialog().getButton(AlertDialog.BUTTON_POSITIVE)!=null);}catch(Exception e){return false;}},"Reconnect dialog did not reopen after returning by shortcut");
            ui(()->{try{activeDialog().dismiss();service.closePanel();}catch(Exception e){throw new RuntimeException(e);}});
        }finally{
            if(!initiallyPaired){NotificationManager manager=getTargetContext().getSystemService(NotificationManager.class);if(manager!=null)manager.cancel(54);}
            getTargetContext().getSharedPreferences("ui",0).edit().putString("shortcuts",savedShortcuts).commit();if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();
            ui(()->{try{if(activeDialog()!=null)activeDialog().dismiss();if(panel()!=null)service.closePanel();}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    private void popupSnapshot(String filename){ui(()->{try{
        DashboardView backdrop=panel();AlertDialog dialog=(AlertDialog)field(service,"activeDialog");require(backdrop!=null&&dialog!=null&&backdrop.isAttachedToWindow(),"Popup backdrop state: view="+(backdrop!=null)+", dialog="+(dialog!=null)+", attached="+(backdrop!=null&&backdrop.isAttachedToWindow())+", panelShowing="+field(service,"panelShowing"));
        Bitmap bitmap=Bitmap.createBitmap(backdrop.getWidth(),backdrop.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);backdrop.draw(canvas);
        View decor=dialog.getWindow().getDecorView();int[] offset=new int[2];decor.getLocationOnScreen(offset);canvas.save();canvas.translate(offset[0],offset[1]);decor.draw(canvas);canvas.restore();
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }catch(Exception e){throw new RuntimeException(e);}});}
    private void snapshot(String filename){
        final boolean[] captured={false},opaqueCorners={false};
        ui(()->{
            DashboardView view=panel();if(view==null||!view.isAttachedToWindow()||view.getWidth()<=0||view.getHeight()<=0)return;
            Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
            opaqueCorners[0]=Color.alpha(bitmap.getPixel(0,0))==255&&Color.alpha(bitmap.getPixel(bitmap.getWidth()-1,bitmap.getHeight()-1))==255;
            try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){captured[0]=bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}catch(IOException e){throw new RuntimeException(e);}finally{bitmap.recycle();}
        });
        require(captured[0],"Snapshot dashboard window was not attached or measured");
        require(opaqueCorners[0],"Fullscreen outer corners are not opaque square corners");
    }
    private void setupWizardSnapshot(UiAutomation automation,String filename)throws Exception{
        String previous=null;int stableFrames=0;long firstStableDraw=0,deadline=SystemClock.elapsedRealtime()+8_000L;
        while(SystemClock.elapsedRealtime()<deadline){
            AtomicReference<String> frame=new AtomicReference<>();AtomicReference<Boolean> ready=new AtomicReference<>(false);
            ui(()->{
                MainActivity activity=setupActivity;
                if(activity==null||activity.isFinishing()||!activity.hasWindowFocus())return;
                View decor=activity.getWindow().getDecorView();SetupWizardView wizard=(SetupWizardView)fieldUnchecked(activity,"wizard");
                if(!decor.isAttachedToWindow()||!decor.isLaidOut()||wizard==null||!wizard.isAttachedToWindow()||!wizard.isLaidOut()
                        ||decor.getWidth()<=0||decor.getHeight()<=0||wizard.getWidth()<=0||wizard.getHeight()<=0||decor.getAlpha()<0.99f)return;
                int[] location=new int[2];decor.getLocationOnScreen(location);
                frame.set(location[0]+":"+location[1]+":"+decor.getWidth()+":"+decor.getHeight()+":"+wizard.getWidth()+":"+wizard.getHeight());
                ready.set(true);
            });
            if(Boolean.TRUE.equals(ready.get())&&frame.get()!=null&&frame.get().equals(previous)){
                stableFrames++;
                if(stableFrames>=5)break;
            }else{previous=frame.get();stableFrames=Boolean.TRUE.equals(ready.get())?1:0;}
            Thread.sleep(150L);
        }
        require(stableFrames>=5,"Setup window did not reach stable attached full-frame bounds before capture: "+previous);
        // The launcher theme is translucent; leave a frame after the stable bounds check so
        // its opening transition cannot expose the previous app in the screenshot.
        Thread.sleep(350L);
        Bitmap image=automation.takeScreenshot();require(image!=null,"Android did not provide a setup screenshot for "+filename);
        try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){
            require(image.compress(Bitmap.CompressFormat.PNG,100,output),"Could not save setup screenshot "+filename);
        }finally{image.recycle();}
    }
    private Object fieldUnchecked(Object object,String name){try{return field(object,name);}catch(Exception error){throw new RuntimeException(error);}}
    private void clickWizardButton(String fieldName)throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();
        ui(()->{try{
            MainActivity activity=setupActivity;require(activity!=null&&!activity.isFinishing(),"Setup activity ended before clicking "+fieldName);
            Object wizard=field(activity,"wizard");Object candidate=field(wizard,fieldName);
            require(candidate instanceof View&&((View)candidate).isEnabled(),"Wizard control is unavailable: "+fieldName);
            require(((View)candidate).performClick(),"Wizard control did not handle click: "+fieldName);
        }catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Wizard UI action failed: "+fieldName,failure.get());
    }
    private int wizardStep()throws Exception{
        AtomicReference<Integer> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        ui(()->{try{value.set((Integer)field(setupActivity,"step"));}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Could not read setup step",failure.get());
        require(value.get()!=null,"Setup step was not available");return value.get();
    }
    private SetupReadiness.Status wizardStatus()throws Exception{
        AtomicReference<SetupReadiness.Status> value=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        ui(()->{try{value.set((SetupReadiness.Status)field(setupActivity,"status"));}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Could not read setup readiness",failure.get());return value.get();
    }
    private boolean wizardHasText(String text)throws Exception{
        AtomicReference<Boolean> value=new AtomicReference<>(false);AtomicReference<Throwable> failure=new AtomicReference<>();
        ui(()->{try{value.set(textView((View)field(setupActivity,"wizard"),text)!=null);}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError("Could not inspect setup view text",failure.get());return value.get();
    }
    private void rebindOwnAccessibilityForSetupGate(UiAutomation automation)throws Exception{
        if(DeckAccessibilityService.connected!=null)return;
        String key=android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),key);
        String canonical="dev.nova.deck/dev.nova.deck.DeckAccessibilityService",shortName="dev.nova.deck/.DeckAccessibilityService";
        require(original!=null,"Accessibility service list is unavailable");
        ArrayList<String> others=new ArrayList<>();boolean found=false;
        for(String item:original.split(":",-1)){if(item.equals(canonical)||item.equals(shortName))found=true;else if(!item.isEmpty())others.add(item);}
        require(found,"DuoDash AccessibilityService is not enabled; setupgate will not change user permissions");
        String disabled=String.join(":",others);require(!disabled.equals(original),"Could not form a temporary list without DuoDash's own service");
        boolean restored=false;
        try{
            shell(automation,"settings put secure enabled_accessibility_services "+disabled);
            long deadline=SystemClock.elapsedRealtime()+3_000L;boolean disabledObserved=false;
            while(SystemClock.elapsedRealtime()<deadline){String current=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),key);if(Objects.equals(disabled,current)){disabledObserved=true;break;}Thread.sleep(50L);}
            require(disabledObserved,"Android did not apply the temporary own-service disconnect");
            deadline=SystemClock.elapsedRealtime()+3_000L;while(DeckAccessibilityService.connected!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50L);
            require(DeckAccessibilityService.connected==null,"DuoDash AccessibilityService did not disconnect before restoring the exact enabled-service list");
            shell(automation,"settings put secure enabled_accessibility_services "+original);
            deadline=SystemClock.elapsedRealtime()+3_000L;boolean restoredObserved=false;
            while(SystemClock.elapsedRealtime()<deadline){String current=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),key);if(Objects.equals(original,current)){restoredObserved=true;break;}Thread.sleep(50L);}
            require(restoredObserved,"Android did not restore the original enabled-service list");
            waitFor(()->DeckAccessibilityService.connected!=null,"DuoDash AccessibilityService did not bind after restoring the original enabled-service list");
            restored=true;
        }finally{
            if(!restored){
                String current=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),key);
                if(!Objects.equals(original,current))shell(automation,"settings put secure enabled_accessibility_services "+original);
            }
        }
    }
    private void checkSetupGate(UiAutomation automation)throws Exception{
        Context target=getTargetContext();
        android.content.SharedPreferences prefs=target.getSharedPreferences("setup_readiness",Context.MODE_PRIVATE);
        boolean hadCompletion=prefs.contains("completed_schema");int originalCompletion=prefs.getInt("completed_schema",0);boolean completed=false;
        boolean floatingEnabled=target.getSharedPreferences("ui",Context.MODE_PRIVATE).getBoolean("floatingButton",true);
        SetupReadiness.Status local=SetupReadiness.inspect(target,false);
        require(local.overlayAllowed&&local.accessibilityEnabled&&local.paired&&local.persistentReconnectGranted,"Native setupgate requires the existing real overlay/accessibility/pairing/reconnect grants: overlay="+local.overlayAllowed+" accessibility="+local.accessibilityEnabled+" paired="+local.paired+" reconnect="+local.persistentReconnectGranted);
        rebindOwnAccessibilityForSetupGate(automation);
        require(DeckAccessibilityService.connected!=null,"Enabled DuoDash AccessibilityService is not bound; refusing to fake setup readiness");
        boolean helperReady=false;try{helperReady=BridgeClient.isCurrentHelper(new BridgeClient(target).request("status",null));}catch(Exception ignored){}
        if(!helperReady)BridgeActivationService.connect(target);
        long helperDeadline=SystemClock.elapsedRealtime()+45_000L;
        while(SystemClock.elapsedRealtime()<helperDeadline){try{helperReady=BridgeClient.isCurrentHelper(new BridgeClient(target).request("status",null));}catch(Exception ignored){}if(helperReady)break;Thread.sleep(250L);}
        require(helperReady,"App-owned saved-pairing reconnect did not provide the current helper");
        SetupReadiness.Status baseline=SetupReadiness.inspect(target,true);
        require(baseline.canFinish(),"Native setupgate could not establish all actual live readiness checks");
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        try{
            prefs.edit().remove("completed_schema").commit();
            SetupReadiness.Status unstamped=SetupReadiness.inspect(target,true);
            require(unstamped.canFinish()&&!unstamped.isCompleted()&&!unstamped.canShowDashboard()&&SetupReadiness.shouldRouteToSetup(unstamped),"Removing the setup stamp did not route a fully ready install into setup");
            target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));
            long activityDeadline=SystemClock.elapsedRealtime()+12_000L;while(monitor.getLastActivity()==null&&SystemClock.elapsedRealtime()<activityDeadline)Thread.sleep(100L);
            Activity started=monitor.getLastActivity();require(started instanceof MainActivity,"Service SHOW did not launch the mandatory setup activity");setupActivity=(MainActivity)started;
            waitFor(()->{try{return setupActivity!=null&&!setupActivity.isFinishing()&&wizardStatus()!=null;}catch(Exception ignored){return false;}} ,"Wizard did not render a live readiness snapshot");
            AtomicReference<String> missingWindows=new AtomicReference<>();ui(()->{try{
                OverlayService current=OverlayService.current;Object panel=current==null?null:field(current,"panel");Object handle=current==null?null:field(current,"handle");
                if(panel!=null||handle!=null)missingWindows.set("service="+(current!=null)+" panel="+(panel!=null)+" handle="+(handle!=null));
            }catch(Throwable error){missingWindows.set("inspection error: "+error);}});
            require(missingWindows.get()==null,"Unstamped setup entry left a dashboard or handle window: "+missingWindows.get());
            require(wizardStatus().canFinish(),"Wizard did not receive actual ready permissions and helper state");
            int backs=0;while(wizardStep()>0&&backs++<3)clickWizardButton("backButton");
            require(wizardStep()==0,"Could not open the first actual wizard step");
            require(wizardHasText("Give DuoDash access"),"First setup step copy was not rendered");
            setupWizardSnapshot(automation,"setup-beta-step1.png");
            clickWizardButton("primary");waitFor(()->{try{return wizardStep()==1;}catch(Exception ignored){return false;}} ,"Continue did not open the pairing step");
            require(wizardHasText("Reconnect this device"),"Saved pairing recovery copy was not rendered");
            setupWizardSnapshot(automation,"setup-beta-step2.png");
            clickWizardButton("primary");waitFor(()->{try{return wizardStep()==2;}catch(Exception ignored){return false;}} ,"Continue did not open the live checks step");
            SetupReadiness.Status ready=wizardStatus();require(ready!=null&&ready.canFinish(),"Live setup checks do not allow Finish");
            require(wizardHasText("Set a button shortcut"),"Final setup step copy was not rendered");
            setupWizardSnapshot(automation,"setup-beta-step3.png");
            clickWizardButton("primary");
            waitFor(()->setupActivity!=null&&setupActivity.isFinishing(),"Successful Finish did not close the wizard");
            waitFor(()->{try{service=OverlayService.current;return service!=null&&panel()!=null&&panel().isAttachedToWindow()&&panel().getWidth()>0;}catch(Exception ignored){return false;}} ,"Successful Finish did not open the dashboard");
            waitFor(()->prefs.getInt("completed_schema",0)>=SetupReadiness.CURRENT_SCHEMA_VERSION,"Finish did not persist the setup completion stamp");
            require(BridgeClient.isCurrentHelper(new BridgeClient(target).request("status",null)),"System helper stopped responding after Finish");
            require(SetupReadiness.inspect(target,true).canShowDashboard(),"Dashboard opened without a current live readiness snapshot");
            snapshot("setup-beta-dashboard.png");
            View collapse=described(panel(),"Hide DuoDash controls");require(collapse!=null,"Dashboard logo hide action is missing");tapView(automation,collapse);
            waitFor(()->{try{DashboardView current=panel();return current==null||!current.isAttachedToWindow();}catch(Exception ignored){return false;}} ,"Hide-controls chevron did not close the panel");
            AtomicReference<View> handleRef=new AtomicReference<>();ui(()->{try{service=OverlayService.current;View handle=(View)field(service,"handle");if(handle!=null&&handle.isAttachedToWindow()&&handle.getVisibility()==View.VISIBLE)handleRef.set(handle);}catch(Throwable ignored){}});
            if(floatingEnabled){require(handleRef.get()!=null,"Dashboard close removed the enabled floating handle");ui(()->handleRef.get().performClick());}
            else{require(handleRef.get()==null,"Dashboard created a floating handle while the saved preference is off");target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));}
            waitFor(()->{try{return panel().isAttachedToWindow()&&panel().getWidth()>0;}catch(Exception ignored){return false;}} ,"Floating handle did not reopen the dashboard");
            completed=true;
        }finally{
            removeMonitor(monitor);
            if(!completed){
                android.content.SharedPreferences.Editor restore=prefs.edit();
                if(hadCompletion)restore.putInt("completed_schema",originalCompletion);else restore.remove("completed_schema");
                restore.commit();
                ui(()->{try{if(setupActivity!=null&&!setupActivity.isFinishing())setupActivity.finish();}catch(Throwable ignored){}});
                try{target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));}catch(Exception ignored){}
            }
            setupActivity=null;
        }
    }
    private String checkPairingPanel()throws Exception{
        DeckAccessibilityService accessibility=DeckAccessibilityService.connected;
        require(accessibility!=null&&SetupReadiness.isAccessibilityServiceEnabled(getTargetContext()),
            "Pairing panel check requires the already-enabled DuoDash accessibility service");
        final boolean[] shown={false};ui(()->shown[0]=accessibility.showPairingPanel());
        require(shown[0],"Accessibility overlay could not attach the pairing keypad");
        boolean pairedBefore=OnDeviceBridge.isPaired(getTargetContext());
        PairingCodePanel keypad=(PairingCodePanel)field(accessibility,"pairingPanel");
        require(keypad!=null,"Pairing keypad view is missing");
        waitFor(()->keypad.isAttachedToWindow()&&keypad.getWidth()>0&&keypad.getHeight()>0,"Pairing keypad did not measure and attach");
        Context context=keypad.getContext();WindowManager manager=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)field(accessibility,"pairingParams");
        Rect bounds=manager.getMaximumWindowMetrics().getBounds();float density=context.getResources().getDisplayMetrics().density;
        Display display=keypad.getDisplay();int[] location=new int[2];keypad.getLocationOnScreen(location);
        String geometry="display="+(display==null?-1:display.getDisplayId())+", type="+params.type+", flags="+params.flags+
            ", view="+keypad.getWidth()+"x"+keypad.getHeight()+", bounds="+bounds.width()+"x"+bounds.height()+
            ", location="+location[0]+","+location[1];
        require(display!=null&&display.getDisplayId()==0,"Pairing keypad is not bound to built-in display 0: "+geometry);
        require(params.type==WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,"Pairing keypad is not a trusted accessibility window: "+geometry);
        require((params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0
                &&(params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0,
            "Pairing keypad can steal Settings focus or block outside touches: "+geometry);
        require(keypad.getWidth()<=Math.round(310*density)+2&&keypad.getHeight()<=Math.round(392*density)+2,
            "Pairing keypad exceeds its compact landscape bounds: "+geometry);
        require(location[0]>=0&&location[1]>=0&&location[0]+keypad.getWidth()<=bounds.width()
                &&location[1]+keypad.getHeight()<=bounds.height(),"Pairing keypad is clipped on display 0: "+geometry);
        View digit=described(keypad,"Digit 1"),clear=described(keypad,"Clear current field");
        View code=described(keypad,"Pairing code, empty");
        require(digit!=null&&clear!=null&&code instanceof TextView,"Pairing keypad accessible controls are missing");
        ui(()->digit.performClick());require("1".contentEquals(((TextView)code).getText()),"Dummy keypad digit did not reach the in-memory code field");
        ui(()->clear.performClick());require("Enter the six-digit code".contentEquals(((TextView)code).getText()),"Clear did not erase the dummy digit");
        Bitmap bitmap=Bitmap.createBitmap(keypad.getWidth(),keypad.getHeight(),Bitmap.Config.ARGB_8888);keypad.draw(new Canvas(bitmap));
        try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),"pairing-panel-beta3.png"))){
            require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Pairing panel preview could not be written");
        }finally{bitmap.recycle();}
        View close=described(keypad,"Close pairing code entry");require(close!=null,"Pairing keypad close button is missing");ui(close::performClick);
        waitFor(()->{try{return field(accessibility,"pairingPanel")==null;}catch(Exception ignored){return false;}},"Pairing keypad did not close");
        require(OnDeviceBridge.isPaired(getTargetContext())==pairedBefore,"Opening/closing the empty keypad changed saved pairing state");
        return geometry;
    }
    private void rebindEnabledAccessibilityService(UiAutomation automation)throws Exception{
        if(DeckAccessibilityService.connected!=null)return;
        String component="dev.nova.deck/dev.nova.deck.DeckAccessibilityService";
        String enabled=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),"enabled_accessibility_services");
        ArrayList<String> others=new ArrayList<>();boolean wasEnabled=false;if(enabled!=null)for(String item:enabled.split(":")){if(item.equals(component)||item.equals("dev.nova.deck/.DeckAccessibilityService"))wasEnabled=true;else others.add(item);}
        require(wasEnabled,"DuoDash accessibility service was not enabled before this test");
        shell(automation,"settings put secure enabled_accessibility_services "+String.join(":",others));Thread.sleep(200);others.add(component);
        shell(automation,"settings put secure enabled_accessibility_services "+String.join(":",others));
        waitFor(()->DeckAccessibilityService.connected!=null,"Shortcut service did not reconnect");
    }
    private AccessibilityNodeInfo findText(AccessibilityNodeInfo node,String exact){
        if(node==null)return null;CharSequence text=node.getText();if(text!=null&&exact.contentEquals(text))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=findText(node.getChild(i),exact);if(found!=null)return found;}return null;
    }
    private String findPairCode(AccessibilityNodeInfo node){
        if(node==null)return null;CharSequence text=node.getText();if(text!=null){String digits=text.toString().replaceAll("[^0-9]","");if(digits.matches("[0-9]{6}"))return digits;}
        for(int i=0;i<node.getChildCount();i++){String found=findPairCode(node.getChild(i));if(found!=null)return found;}return null;
    }
    private boolean scrollAndClick(UiAutomation automation,String label)throws Exception{
        for(int i=0;i<18;i++){
            AccessibilityNodeInfo node=findText(automation.getRootInActiveWindow(),label);
            if(node!=null){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
                if(bounds.top>=370&&bounds.bottom<=920&&node.isVisibleToUser())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(bounds.top<370)shell(automation,"input swipe 640 450 640 650 220");
                else shell(automation,"input swipe 640 700 640 480 220");
            }else shell(automation,"input swipe 640 700 640 480 220");
            Thread.sleep(220);
        }return false;
    }
    private void checkAppOwnedPairing(UiAutomation automation)throws Exception{
        String settingsBefore=shellOutput(automation,"dumpsys activity activities | grep -E 'topResumedActivity|mFocusedApp' | head -4");
        require(settingsBefore.contains("com.android.settings"),"Keep Android Wireless debugging pairing dialog foreground during this check");
        String code=null;for(int i=0;i<30&&code==null;i++){Thread.sleep(200);code=findPairCode(automation.getRootInActiveWindow());}
        require(code!=null,"Temporary pairing code is not visible in Android's foreground pairing dialog");
        String oldPid=shellOutput(automation,"pidof nova_deck").trim();
        require(BridgeActivationService.preparePairing(getTargetContext()),"DuoDash pairing notification permission is unavailable");
        Notification ready=activePairingNotification();require(ready!=null&&ready.actions!=null,"DuoDash pairing notification is missing");
        Notification.Action reply=null;for(Notification.Action action:ready.actions)if(action.getRemoteInputs()!=null&&action.getRemoteInputs().length>0)reply=action;
        require(reply!=null,"DuoDash notification does not expose inline pairing input");RemoteInput input=reply.getRemoteInputs()[0];
        Bundle results=new Bundle();results.putCharSequence(input.getResultKey(),code);Intent replyIntent=new Intent();RemoteInput.addResultsToIntent(new RemoteInput[]{input},replyIntent,results);code=null;
        try{reply.actionIntent.send(getTargetContext(),0,replyIntent);}catch(PendingIntent.CanceledException error){throw new AssertionError("Inline pairing action was canceled",error);}
        long deadline=SystemClock.elapsedRealtime()+30000;boolean connected=false;String pid="";
        while(SystemClock.elapsedRealtime()<deadline){if(OnDeviceBridge.isPaired(getTargetContext()))try{new BridgeClient(getTargetContext()).request("status",null);connected=true;pid=shellOutput(automation,"pidof nova_deck").trim();if(!pid.isEmpty())break;}catch(Exception ignored){}Thread.sleep(250);}
        require(connected,"RemoteInput did not pair DuoDash and start the system helper");require(!pid.isEmpty(),"Pairing completed without a running DuoDash bridge process");
        if(!oldPid.isEmpty())require(!oldPid.equals(pid),"Pairing kept the previous helper PID instead of restarting it");
        String settingsAfter=shellOutput(automation,"dumpsys activity activities | grep -E 'topResumedActivity|mFocusedApp' | head -4");
        require(settingsAfter.contains("com.android.settings"),"RemoteInput pairing took Android Settings out of the foreground");verifyAdbIdentityPersistence();
    }
    private void checkPairingNotificationOnly(UiAutomation automation)throws Exception{
        String before=shellOutput(automation,"dumpsys activity activities | grep -E 'topResumedActivity|mFocusedApp' | head -4");
        require(before.contains("com.android.settings"),"Keep Android Wireless debugging pairing dialog foreground");
        require(BridgeActivationService.preparePairing(getTargetContext()),"DuoDash notification permission is unavailable");
        Notification ready=activePairingNotification();require(ready!=null&&ready.actions!=null,"Pairing notification was not posted");
        Notification.Action reply=null;for(Notification.Action action:ready.actions)if(action.getRemoteInputs()!=null&&action.getRemoteInputs().length>0)reply=action;
        require(reply!=null&&reply.actionIntent!=null,"Pairing notification has no inline reply action");
        require(reply.getRemoteInputs().length==1&&"duodash_pairing_code".equals(reply.getRemoteInputs()[0].getResultKey()),"Inline reply key is incorrect");
        require(!reply.actionIntent.isImmutable(),"Inline reply PendingIntent must accept RemoteInput");
        String after=shellOutput(automation,"dumpsys activity activities | grep -E 'topResumedActivity|mFocusedApp' | head -4");
        require(after.contains("com.android.settings"),"Posting the pairing notification displaced Android Settings");
    }
    private void checkBatteryHeaderUi()throws Exception{
        getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;} ,"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0;},"Dashboard window did not attach with measured bounds");
        DashboardView originalPanel=panel();boolean originalShow=controller.state.showBatteryTime;
        try{
            final String[] batteryDescription={null};final boolean[] headerGearVisible={false};final boolean[] disabledLabel={false},enabledLabel={false};
            ui(()->{try{
                View gauge=(View)field(originalPanel,"batteryGauge");batteryDescription[0]=String.valueOf(gauge.getContentDescription());
                headerGearVisible[0]=described(originalPanel,"Settings")!=null;
                TextView time=(TextView)field(originalPanel,"batteryTime");controller.showBatteryTime(false);disabledLabel[0]=time.getVisibility()==View.GONE;
                controller.showBatteryTime(true);enabledLabel[0]=time.getVisibility()==View.VISIBLE;
            }catch(Exception e){throw new RuntimeException(e);}});
            require(batteryDescription[0]!=null&&batteryDescription[0].matches("Battery [0-9]+ percent(?:\\. (?:Charging|Full))?"),"Battery percent is missing from the battery icon description");
            require(headerGearVisible[0],"Top-right Settings gear is missing");require(disabledLabel[0]&&enabledLabel[0],"Show estimated battery time did not hide/show the adjacent label");
            waitFor(()->{DashboardView view=panel();return view==originalPanel&&view.isAttachedToWindow()&&view.getWidth()>0;},"Dashboard was not ready for a snapshot");snapshot("dashboard-battery-header.png");
            ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}} ,"Settings sheet did not open");
            final boolean[] retained={false},hasX={false},hasFooter={false};
            ui(()->{try{AlertDialog dialog=activeDialog();View decor=dialog.getWindow().getDecorView();retained[0]=panel()==originalPanel&&originalPanel.getParent()!=null;hasX[0]=textView(decor,"×")!=null;for(String label:new String[]{"Stop overlay","Rename output","Back to controls"})if(textView(decor,label)!=null)hasFooter[0]=true;if(hasX[0])textView(decor,"×").performClick();}catch(Exception e){throw new RuntimeException(e);}});
            require(retained[0]&&hasX[0]&&!hasFooter[0],"Settings modal backdrop or close-only header failed");
        }finally{ui(()->controller.showBatteryTime(originalShow));}
    }
    private void checkFocusedDashboardUi(UiAutomation automation,boolean verifyDrag)throws Exception{
        getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0;},"Dashboard window did not attach with measured bounds");
        waitForStableProfile();
        require(panel().getDisplay()!=null&&panel().getDisplay().getDisplayId()==0,"DuoDash control surface is not attached to the built-in display");
        final String key=controller.layoutKey(),originalOrder=controller.state.controlOrder,originalHidden=controller.state.hiddenControls,originalWide=controller.state.wideControls,originalFps=controller.state.fpsScreen;
        final int originalColumns=controller.state.actionColumns;final boolean originalHorizontal=controller.state.horizontalSliders,originalCompact=controller.state.compactControls;
        final ProfileStore profiles=new ProfileStore(getTargetContext());final String originalStoredFps=profiles.fpsScreen(key);
        final android.content.SharedPreferences uiPrefs=getTargetContext().getSharedPreferences("ui",Context.MODE_PRIVATE);final boolean hadShowTime=uiPrefs.contains("showBatteryTime"),originalShowTime=controller.state.showBatteryTime;
        try{
            ui(()->controller.updateLayout(key,ControlCatalog.defaultOrder(),ControlCatalog.defaultHidden()));
            waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0;},"Dashboard did not rebuild after validation layout");
            DashboardView view=panel();
            View gear=described(view,"Settings");TextView title=textView(view,"DuoDash");TextView profile=(TextView)field(view,"profileName");
            require(gear!=null,"Top-right Settings gear is missing");require(title!=null&&profile!=null,"Header title or output name is missing");
            int[] titleLocation=new int[2],profileLocation=new int[2];title.getLocationOnScreen(titleLocation);profile.getLocationOnScreen(profileLocation);
            require(Math.abs((titleLocation[1]+title.getHeight()/2)-(profileLocation[1]+profile.getHeight()/2))<=Math.round(12*getTargetContext().getResources().getDisplayMetrics().density),"Title and output name are not on one header line");
            TextView batteryTime=(TextView)field(view,"batteryTime");View batteryGauge=(View)field(view,"batteryGauge");
            require(batteryGauge.getContentDescription()!=null&&batteryGauge.getContentDescription().toString().matches("Battery [0-9]+ percent(?:\\. (?:Charging|Full))?"),"Battery percentage is missing from the in-glyph reading");
            if(batteryTime.getVisibility()==View.VISIBLE)require(!Arrays.asList("Charging","Full","On power").contains(batteryTime.getText().toString()),"Battery status word is shown as the runtime estimate");
            String selected=controller.state.fpsScreen;View fpsMetric=described(view,"FPS, "+(selected.equals("main")?"Main":"External")+" screen:");
            require(fpsMetric!=null,"Selected main/upper FPS source is not exposed by the metric");ui(fpsMetric::performClick);
            waitFor(()->!controller.state.fpsScreen.equals(selected),"Tapping the FPS metric did not switch its selected screen");
            require(profiles.fpsScreen(key).equals(controller.state.fpsScreen),"FPS source was not saved for this display profile");
            View selectedFps=described(panel(),"FPS, "+(controller.state.fpsScreen.equals("main")?"Main":"External")+" screen:");require(selectedFps!=null,"FPS source indicator did not follow selection");
            ui(selectedFps::performLongClick);waitFor(()->{try{return dashboardEditing();}catch(Exception e){return false;}},"Long-press did not enter edit mode");
            require(activeDialog()==null,"Long-press opened a popup instead of entering edit mode");
            String editingFps=controller.state.fpsScreen;ui(selectedFps::performClick);
            waitFor(()->{try{return !dashboardEditing();}catch(Exception e){return false;}},"Short tap did not leave edit mode");
            require(controller.state.fpsScreen.equals(editingFps),"Short tap activated the FPS metric while leaving edit mode");

            @SuppressWarnings("unchecked") Map<String,View> tiles=(Map<String,View>)field(panel(),"tiles");
            require(tiles.size()>=2,"At least two action controls are needed for the drag-order check");
            List<String> actionIds=new ArrayList<>(tiles.keySet());String dragFrom=actionIds.get(0),dragTo=actionIds.get(1);
            View source=tiles.get(dragFrom),destination=tiles.get(dragTo);String beforeDrag=controller.state.controlOrder;
            if(verifyDrag){
                drag(automation,source,destination);waitFor(()->!controller.state.controlOrder.equals(beforeDrag),"Drag/drop did not reorder the selected buttons");
                require(dashboardEditing(),"Drag/drop unexpectedly ended edit mode");waitFor(()->profiles.order(key).equals(controller.state.controlOrder),"Reordered controls were not persisted");
            }else{ui(selectedFps::performLongClick);waitFor(()->{try{return dashboardEditing();}catch(Exception e){return false;}} ,"Could not enter edit mode for remove/Add checks");}
            List<View> removeBadges=new ArrayList<>();findDescriptions(panel(),"Edit mode. Remove ",removeBadges);
            require(removeBadges.size()>=tiles.size(),"Visible controls do not all expose remove badges");
            View firstTile=tiles.get(dragFrom);ControlCatalog.Entry removedEntry=ControlCatalog.find(dragFrom);String removeLabel=removedEntry==null?String.valueOf(firstTile.getContentDescription()):removedEntry.label;
            View removeBadge=described(panel(),"Edit mode. Remove "+removeLabel);require(removeBadge!=null,"Action remove badge is missing");
            float density=getTargetContext().getResources().getDisplayMetrics().density;require(removeBadge.getWidth()/density>=44&&removeBadge.getHeight()/density>=44,"Remove badge is smaller than 44dp");
            snapshot("dashboard-0.9.3-edit-mode.png");String activeBeforeRemove=visibleOrder(controller.state.controlOrder,controller.state.hiddenControls);
            final String[] editTrace={""};ui(()->{try{DashboardView beforePanel=panel();editTrace[0]="before: panelSame="+(beforePanel==view)+", attached="+beforePanel.isAttachedToWindow()+", profileKey="+controller.layoutKey()+", renderedKey="+field(beforePanel,"renderedLayoutKey")+", editing="+field(beforePanel,"editing")+", editingKey="+field(beforePanel,"editingKey");long now=SystemClock.uptimeMillis();float inset=22*getTargetContext().getResources().getDisplayMetrics().density;MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,removeBadge.getWidth()-inset,inset,0);removeBadge.dispatchTouchEvent(down);down.recycle();MotionEvent up=MotionEvent.obtain(now,now+70,MotionEvent.ACTION_UP,removeBadge.getWidth()-inset,inset,0);removeBadge.dispatchTouchEvent(up);up.recycle();DashboardView afterPanel=panel();editTrace[0]+="; after-up: panelSame="+(afterPanel==view)+", attached="+afterPanel.isAttachedToWindow()+", profileKey="+controller.layoutKey()+", renderedKey="+field(afterPanel,"renderedLayoutKey")+", editing="+field(afterPanel,"editing")+", editingKey="+field(afterPanel,"editingKey");}catch(Exception e){editTrace[0]+="; trace error="+e;}});waitFor(()->!visibleOrder(controller.state.controlOrder,controller.state.hiddenControls).equals(activeBeforeRemove),"Remove badge did not remove the action");
            boolean stillEditing=dashboardEditing();if(!stillEditing){ui(()->{try{DashboardView live=panel();editTrace[0]+="; after-wait: panelSame="+(live==view)+", attached="+live.isAttachedToWindow()+", profileKey="+controller.layoutKey()+", renderedKey="+field(live,"renderedLayoutKey")+", editing="+field(live,"editing")+", editingKey="+field(live,"editingKey");}catch(Exception e){editTrace[0]+="; after-wait trace error="+e;}});}require(stillEditing,"Removing a control unexpectedly ended edit mode: "+editTrace[0]);require(profiles.order(key).equals(controller.state.controlOrder)&&profiles.hidden(key).equals(controller.state.hiddenControls),"Removed control was not persisted");
            String removedId="";for(String id:activeBeforeRemove.split(","))if(!Arrays.asList(visibleOrder(controller.state.controlOrder,controller.state.hiddenControls).split(",")).contains(id)){removedId=id;break;}
            require(!removedId.isEmpty(),"Could not identify removed action");ControlCatalog.Entry removedControl=ControlCatalog.find(removedId);require(removedControl!=null,"Removed control has no catalog entry");
            final String removedControlId=removedId;
            View add=textContaining(panel(),"Add control");require(add!=null&&add.getVisibility()==View.VISIBLE,"Edit mode does not show Add control in the empty grid space");ui(add::performClick);
            waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Add control did not open the picker");require(dashboardEditing(),"Opening Add control exited edit mode");
            View option=described(activeDialog().getWindow().getDecorView(),removedControl.label);require(option!=null,"Removed control is not available in the Add picker");
            ui(()->option.performClick());waitFor(()->!Arrays.asList(controller.state.hiddenControls.split(",")).contains(removedControlId),"Selecting Add control did not restore the action");
            require(dashboardEditing(),"Selecting Add control exited edit mode");require(profiles.order(key).equals(controller.state.controlOrder)&&profiles.hidden(key).equals(controller.state.hiddenControls),"Added control was not persisted");
            AlertDialog picker=activeDialog();if(picker!=null&&picker.getButton(AlertDialog.BUTTON_POSITIVE)!=null)ui(()->picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Add picker did not close");
            @SuppressWarnings("unchecked") Map<String,View> metricViews=(Map<String,View>)field(panel(),"metrics");View temperature=metricViews.get("temp");
            require(temperature!=null&&temperature.getParent()!=null&&temperature.getVisibility()==View.VISIBLE,"Visible Temperature metric is missing");
            ui(temperature::performLongClick);waitFor(()->{try{return dashboardEditing();}catch(Exception e){return false;}},"Long-press on a metric did not enter edit mode");
            float badgeInset=4*getTargetContext().getResources().getDisplayMetrics().density;ui(()->tapLocal(temperature,temperature.getWidth()-badgeInset,badgeInset));
            waitFor(()->Arrays.asList(controller.state.hiddenControls.split(",")).contains("temp"),"Removing the metric did not update the saved layout");require(temperature.getVisibility()==View.GONE,"Removed metric stayed visible");
            View restoreTile=textContaining(panel(),"Add control");require(restoreTile!=null&&restoreTile.getVisibility()==View.VISIBLE,"Hidden metric has no Add recovery path");ui(restoreTile::performClick);
            waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Add control did not open after hiding a metric");View restoreMetric=described(activeDialog().getWindow().getDecorView(),"Temperature");require(restoreMetric!=null,"Temperature is missing from Add controls");ui(restoreMetric::performClick);
            waitFor(()->!Arrays.asList(controller.state.hiddenControls.split(",")).contains("temp")&&temperature.getVisibility()==View.VISIBLE&&temperature.getParent()!=null&&temperature.getGlobalVisibleRect(new Rect()),"Restored metric view did not become visible again");require(dashboardEditing(),"Restoring a metric exited edit mode");
            AlertDialog metricPicker=activeDialog();if(metricPicker!=null&&metricPicker.getButton(AlertDialog.BUTTON_POSITIVE)!=null)ui(()->metricPicker.getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Metric picker did not close");
            @SuppressWarnings("unchecked") Map<String,View> faderViews=(Map<String,View>)field(panel(),"faders");View mainFader=faderViews.get("main");require(mainFader!=null&&mainFader.getParent()!=null,"Main brightness fader is missing");ui(()->tapLocal(mainFader,mainFader.getWidth()-badgeInset,badgeInset));
            waitFor(()->Arrays.asList(controller.state.hiddenControls.split(",")).contains("main"),"Removing the fader did not update the saved layout");View restoreFaderTile=textContaining(panel(),"Add control");require(restoreFaderTile!=null&&restoreFaderTile.getVisibility()==View.VISIBLE,"Hidden fader has no Add recovery path");ui(restoreFaderTile::performClick);
            waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Add control did not open after hiding a fader");View restoreMain=described(activeDialog().getWindow().getDecorView(),"Main screen");require(restoreMain!=null,"Main screen fader is missing from Add controls");ui(restoreMain::performClick);
            waitFor(()->!Arrays.asList(controller.state.hiddenControls.split(",")).contains("main")&&mainFader.getVisibility()==View.VISIBLE&&mainFader.getParent()!=null&&mainFader.getGlobalVisibleRect(new Rect())&&((View)mainFader.getParent()).getVisibility()==View.VISIBLE,"Restored fader did not become visible again");require(dashboardEditing(),"Restoring a fader exited edit mode");
            AlertDialog faderPicker=activeDialog();if(faderPicker!=null&&faderPicker.getButton(AlertDialog.BUTTON_POSITIVE)!=null)ui(()->faderPicker.getButton(AlertDialog.BUTTON_POSITIVE).performClick());waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Fader picker did not close");
            View leave=described(panel(),"FPS, "+(controller.state.fpsScreen.equals("main")?"Main":"External")+" screen:");require(leave!=null,"FPS widget missing for edit-mode exit check");String beforeExitFps=controller.state.fpsScreen;ui(leave::performClick);
            waitFor(()->{try{return !dashboardEditing();}catch(Exception e){return false;}},"Short tap did not end edit mode");require(controller.state.fpsScreen.equals(beforeExitFps),"Tap while editing triggered the widget action");

            ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Settings modal did not open");
            final boolean[] retained={false},xPresent={false},footerAction={false},batterySetting={false};
            ui(()->{try{AlertDialog dialog=activeDialog();View decor=dialog.getWindow().getDecorView();retained[0]=panel()==view&&view.isAttachedToWindow();xPresent[0]=textView(decor,"×")!=null;for(Button button:new Button[]{dialog.getButton(AlertDialog.BUTTON_POSITIVE),dialog.getButton(AlertDialog.BUTTON_NEGATIVE),dialog.getButton(AlertDialog.BUTTON_NEUTRAL)})if(button!=null&&button.getVisibility()==View.VISIBLE&&button.getText().length()>0)footerAction[0]=true;batterySetting[0]=textContaining(decor,"Show estimated battery time")!=null;}catch(Exception ignored){}});
            require(retained[0]&&xPresent[0]&&!footerAction[0],"Settings no longer overlays the retained dashboard with only its close X");require(batterySetting[0],"Battery-time setting is missing from Settings");snapshot("dashboard-0.9.3-settings.png");
            AlertDialog settings=activeDialog();View close=textView(settings.getWindow().getDecorView(),"×");ui(()->close.performClick());waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Settings X did not close the sheet");
            waitFor(()->{DashboardView current=panel();return current!=null&&current.isAttachedToWindow()&&current.getWidth()>0;},"Dashboard not ready for final snapshot");snapshot("dashboard-0.8-focused.png");
        }finally{
            try{ui(()->{
                try{AlertDialog dialog=activeDialog();if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();}}catch(Exception ignored){}
                if(controller.state.fpsScreen.equals("main")!=originalFps.equals("main"))controller.control("fps_cycle");
                try{if(dashboardEditing()){View settings=described(panel(),"Settings");if(settings!=null)settings.performClick();}}catch(Exception ignored){}
                controller.updateLayout(key,originalOrder,originalHidden);controller.wide(key,originalWide);controller.actionColumns(key,originalColumns);controller.style(key,originalHorizontal,originalCompact);controller.showBatteryTime(originalShowTime);
            });}catch(Exception ignored){}
            if(originalStoredFps==null)getTargetContext().getSharedPreferences("display_profiles",Context.MODE_PRIVATE).edit().remove(key+":fpsScreen").apply();else profiles.fpsScreen(key,originalStoredFps);
            if(!hadShowTime)uiPrefs.edit().remove("showBatteryTime").apply();
        }
    }

    private CompoundButton settingsSwitch(AlertDialog dialog,String label){
        TextView found=textView(dialog.getWindow().getDecorView(),label);
        return found instanceof CompoundButton?(CompoundButton)found:null;
    }
    private void openSettings()throws Exception{
        ui(()->service.showSetup());waitFor(()->{try{AlertDialog dialog=activeDialog();return dialog!=null&&dialog.getWindow()!=null&&dialog.getWindow().getDecorView().getWidth()>0;}catch(Exception e){return false;}} ,"Settings did not open and measure");
    }
    private void closeSettings()throws Exception{
        AlertDialog dialog=activeDialog();require(dialog!=null,"Settings dialog is missing");
        View close=textView(dialog.getWindow().getDecorView(),"×");require(close!=null,"Settings close X is missing");ui(close::performClick);
        waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}} ,"Settings X did not close the sheet");
    }
    private View headerStatus(DashboardView view,String type)throws Exception{
        if(type.equals("hz"))return (View)field(view,"refreshRate");
        if(type.equals("wifi")){View found=described(view,"Wi-Fi connected");return found!=null?found:described(view,"Wi-Fi disconnected");}
        if(type.equals("clock"))return described(view,"Current time,");
        throw new IllegalArgumentException(type);
    }
    private void assertHeaderStatus(DashboardView view,String type,boolean visible)throws Exception{
        View status=headerStatus(view,type);require(status!=null,"Header "+type+" view is missing");
        require((status.getVisibility()==View.VISIBLE)==visible,"Header "+type+" visibility did not follow its saved setting");
        if(visible){
            float density=getTargetContext().getResources().getDisplayMetrics().density;
            require(status.getContentDescription()!=null&&!status.getContentDescription().toString().isEmpty(),"Header "+type+" lacks an accessible description");
            if(type.equals("hz"))require(status.isClickable()&&status.isFocusable()&&status.getWidth()/density>=44&&status.getHeight()/density>=44,"Refresh-rate header target is not accessible");
            if(type.equals("clock"))require(status.getHeight()/density>=44,"Clock header hit area is smaller than 44dp");
        }
    }
    private void assertAndroidClockFormat(DashboardView view)throws Exception{
        TextView clock=(TextView)headerStatus(view,"clock");String actual=clock.getText().toString();
        java.text.DateFormat format=android.text.format.DateFormat.getTimeFormat(getTargetContext());
        String now=format.format(new Date()),prior=format.format(new Date(System.currentTimeMillis()-60_000));
        require(actual.equals(now)||actual.equals(prior),"Clock does not match Android's configured 12/24-hour time format: "+actual+" expected "+now);
    }
    private void restorePreferenceKeys(SharedPreferences prefs,Map<String,?> original,Set<String> keys){
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:keys){if(!original.containsKey(key)){edit.remove(key);continue;}Object value=original.get(key);
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);
            else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
        }
        edit.commit();
    }
    private Set<String> sharedDashboardPreferenceKeys(){
        LinkedHashSet<String> keys=new LinkedHashSet<>(Arrays.asList(
            SharedDashboardLayout.VERSION_KEY,SharedDashboardLayout.SOURCE_KEY,
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.ORDER),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.HIDDEN),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.WIDE),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.ACTION_COLUMNS),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.HORIZONTAL),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.COMPACT),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.FPS_SCREEN),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.PINNED),
            SharedDashboardLayout.sharedFieldKey(SharedDashboardLayout.LAYOUT_VERSION)));
        return keys;
    }
    private void checkHeaderTelemetry()throws Exception{
        getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;} ,"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{DashboardView current=panel();return current!=null&&current.isAttachedToWindow()&&current.getWidth()>0&&current.getHeight()>0;} ,"Dashboard window did not attach with measured bounds");waitForStableProfile();
        final String key=controller.layoutKey(),originalOrder=controller.state.controlOrder,originalHidden=controller.state.hiddenControls,originalWide=controller.state.wideControls;
        final boolean originalHz=controller.state.showHeaderHz,originalWifi=controller.state.showHeaderWifi,originalClock=controller.state.showHeaderClock;
        SharedPreferences uiPrefs=getTargetContext().getSharedPreferences("ui",Context.MODE_PRIVATE),profilePrefs=getTargetContext().getSharedPreferences("display_profiles",Context.MODE_PRIVATE);
        Map<String,?> originalUi=new HashMap<>(uiPrefs.getAll()),originalProfilePrefs=new HashMap<>(profilePrefs.getAll());
        Set<String> headerKeys=new LinkedHashSet<>(Arrays.asList("showHeaderHz","showHeaderWifi","showHeaderClock"));
        Set<String> profileKeys=new LinkedHashSet<>(Arrays.asList(key+":order",key+":hidden",key+":wide"));profileKeys.addAll(sharedDashboardPreferenceKeys());
        String hiddenNoActions;
        LinkedHashSet<String> removed=new LinkedHashSet<>();if(originalHidden!=null&&!originalHidden.isEmpty())removed.addAll(Arrays.asList(originalHidden.split(",")));
        for(String id:ControlLogic.cleanOrder(originalOrder).split(","))if(ControlLogic.action(id))removed.add(id);
        for(UiState.AppShortcut app:controller.state.pinnedApps)removed.add(app.id);
        removed.add("refresh");removed.remove("fps");hiddenNoActions=String.join(",",removed);
        try{
            DashboardView view=panel();
            verifyMainPanelBounds();int flags=view.getSystemUiVisibility();
            require((flags&View.SYSTEM_UI_FLAG_FULLSCREEN)!=0&&(flags&View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)!=0,"Fullscreen dashboard did not request immersive system UI flags");
            View gear=(View)field(view,"settingsButton");float density=getTargetContext().getResources().getDisplayMetrics().density;
            require(gear.isClickable()&&gear.isFocusable()&&"Settings".contentEquals(gear.getContentDescription()),"Settings gear is not an accessible action");
            require(gear.getWidth()/density>=48&&gear.getHeight()/density>=48,"Settings gear hit target is smaller than 48dp");
            if(originalClock)assertAndroidClockFormat(view);

            ui(()->controller.updateLayout(key,originalOrder,hiddenNoActions));
            waitFor(()->{try{@SuppressWarnings("unchecked") Map<String,View> tiles=(Map<String,View>)field(panel(),"tiles");return tiles.isEmpty()&&panel().getWidth()>0;}catch(Exception e){return false;}} ,"Action controls did not hide for the empty-actions check");
            DashboardView noActions=panel();View metricBand=(View)field(noActions,"metricBand"),actionGrid=(View)field(noActions,"actionGrid");
            require(actionGrid.getVisibility()!=View.VISIBLE,"Quick-action tiles remain visible after hiding all actions");
            require(metricBand.getVisibility()==View.VISIBLE,"Telemetry strip disappeared when all actions were hidden");
            int[] panelLocation=new int[2],metricLocation=new int[2];noActions.getLocationOnScreen(panelLocation);metricBand.getLocationOnScreen(metricLocation);
            int bottomGap=panelLocation[1]+noActions.getHeight()-(metricLocation[1]+metricBand.getHeight());
            require(bottomGap<=Math.round(28*density),"Telemetry strip is not pinned to the bottom when actions are hidden: gap="+bottomGap+"px");

            String[] labels={"Show refresh rate","Show Wi-Fi status","Show clock"};boolean[] original={originalHz,originalWifi,originalClock};
            openSettings();AlertDialog dialog=activeDialog();CompoundButton[] switches=new CompoundButton[labels.length];
            View close=textView(dialog.getWindow().getDecorView(),"×");
            require(close!=null&&close.isClickable()&&close.isFocusable(),"Settings close control is not accessible");
            require(close.getWidth()/density>=48&&close.getHeight()/density>=48,"Settings close target is smaller than 48dp");
            for(int i=0;i<labels.length;i++){
                switches[i]=settingsSwitch(dialog,labels[i]);require(switches[i]!=null,"Settings switch missing: "+labels[i]);
                require(switches[i].isClickable()&&switches[i].isFocusable()&&switches[i].getHeight()/density>=48,"Settings switch target is not accessible: "+labels[i]);
                require(switches[i].isChecked()==original[i],labels[i]+" did not reflect its saved value");
                switches[i].performClick();require(switches[i].isChecked()!=original[i],labels[i]+" did not toggle");
            }
            closeSettings();
            boolean[] changed={!originalHz,!originalWifi,!originalClock};
            DashboardView changedView=panel();assertHeaderStatus(changedView,"hz",changed[0]);assertHeaderStatus(changedView,"wifi",changed[1]);assertHeaderStatus(changedView,"clock",changed[2]);
            if(changed[2])assertAndroidClockFormat(changedView);
            openSettings();dialog=activeDialog();
            for(int i=0;i<labels.length;i++){
                CompoundButton persisted=settingsSwitch(dialog,labels[i]);require(persisted!=null&&persisted.isChecked()==changed[i],labels[i]+" did not persist after reopening Settings");
                persisted.performClick();require(persisted.isChecked()==original[i],labels[i]+" could not be restored through Settings");
            }
            closeSettings();
            assertHeaderStatus(panel(),"hz",originalHz);assertHeaderStatus(panel(),"wifi",originalWifi);assertHeaderStatus(panel(),"clock",originalClock);
            require(ControlCatalog.find("refresh_mode")!=null&&ControlCatalog.find("refresh_mode").kind.equals("action")&&"Refresh rate".equals(ControlCatalog.find("refresh_mode").label),"Refresh-rate action is not present in the control catalog");
            require(ControlCatalog.find("refresh")!=null&&ControlCatalog.find("refresh").kind.equals("metric")&&"Refresh monitor".equals(ControlCatalog.find("refresh").label),"Refresh-monitor metric is not distinct from the rate action");

            @SuppressWarnings("unchecked") Map<String,View> metrics=(Map<String,View>)field(panel(),"metrics");View fps=metrics.get("fps");require(fps!=null&&fps.getVisibility()==View.VISIBLE,"FPS metric is unavailable for adding controls");ui(fps::performLongClick);
            waitFor(()->{try{return dashboardEditing();}catch(Exception e){return false;}} ,"Long-press did not enter metric edit mode");
            View add=textContaining(panel(),"Add control");require(add!=null&&add.getVisibility()==View.VISIBLE,"Metric edit mode has no Add control button");ui(add::performClick);
            waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}} ,"Add control picker did not open from metric edit mode");
            final String refreshId="refresh_mode";List<View> refreshChoices=new ArrayList<>(),monitorChoices=new ArrayList<>();findDescriptions(activeDialog().getWindow().getDecorView(),"Refresh rate",refreshChoices);findDescriptions(activeDialog().getWindow().getDecorView(),"Refresh monitor",monitorChoices);
            List<CheckBox> refreshOptions=new ArrayList<>(),monitorOptions=new ArrayList<>();for(View choice:refreshChoices)if(choice instanceof CheckBox)refreshOptions.add((CheckBox)choice);for(View choice:monitorChoices)if(choice instanceof CheckBox)monitorOptions.add((CheckBox)choice);
            require(refreshOptions.size()==1&&monitorOptions.size()==1,"Picker does not distinguish the Refresh rate action from Refresh monitor telemetry");CheckBox refreshAction=refreshOptions.get(0);
            require(!monitorOptions.get(0).isChecked(),"Refresh monitor telemetry should remain inactive in the separate-action check");
            require(!refreshAction.isChecked(),"Refresh-rate action should be inactive before the add check");ui(refreshAction::performClick);
            waitFor(()->!Arrays.asList(controller.state.hiddenControls.split(",")).contains(refreshId),"Selecting Refresh rate did not activate the action");
            @SuppressWarnings("unchecked") Map<String,View> addedTiles=(Map<String,View>)field(panel(),"tiles");
            require(addedTiles.containsKey(refreshId)&&addedTiles.get(refreshId).getVisibility()==View.VISIBLE,"Re-added refresh-rate action is not visible in the dashboard");
            dialog=activeDialog();if(dialog!=null&&dialog.getButton(AlertDialog.BUTTON_POSITIVE)!=null){AlertDialog picker=dialog;ui(()->picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick());}
            waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}} ,"Add picker did not close");
        }finally{
            try{ui(()->{
                try{AlertDialog dialog=activeDialog();if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();}}catch(Exception ignored){}
                try{if(dashboardEditing()){View done=described(panel(),"Done editing");if(done!=null)done.performClick();}}catch(Exception ignored){}
                controller.showHeaderHz(originalHz);controller.showHeaderWifi(originalWifi);controller.showHeaderClock(originalClock);
                controller.updateLayout(key,originalOrder,originalHidden);controller.wide(key,originalWide);
                restorePreferenceKeys(uiPrefs,originalUi,headerKeys);restorePreferenceKeys(profilePrefs,originalProfilePrefs,profileKeys);
            });}catch(Exception ignored){}
        }
    }
    private void checkFaderGesture(UiAutomation automation)throws Exception{
        rebindEnabledAccessibilityService(automation);
        Context target=getTargetContext();target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{try{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0;}catch(Exception ignored){return false;}},"Dashboard did not attach");waitForStableProfile();
        waitForStableFaderPanel();verifyMainPanelBounds();
        final String key=controller.layoutKey(),originalOrder=controller.state.controlOrder,originalHidden=controller.state.hiddenControls,originalWide=controller.state.wideControls;
        final int originalMain=controller.state.main,originalUpper=controller.state.upper,originalVolume=controller.state.volume,originalMainAudio=controller.state.mainAudio,originalUpperAudio=controller.state.upperAudio;
        final boolean originalHorizontal=controller.state.horizontalSliders,originalCompact=controller.state.compactControls;
        AudioManager audio=target.getSystemService(AudioManager.class);require(audio!=null,"Android media volume service is unavailable");final int originalStreamVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        SharedPreferences profilePrefs=target.getSharedPreferences("display_profiles",Context.MODE_PRIVATE);Map<String,?> originalPrefs=new HashMap<>(profilePrefs.getAll());
        Set<String> profileKeys=new LinkedHashSet<>(Arrays.asList(key+":main",key+":upper",key+":volume",key+":mainAudio",key+":upperAudio",key+":order",key+":hidden",key+":wide",key+":horizontal",key+":compact"));profileKeys.addAll(sharedDashboardPreferenceKeys());
        LinkedHashSet<String> fixtureOrder=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanOrder(originalOrder).split(",")));
        LinkedHashSet<String> fixtureHidden=new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(originalHidden).split(",")));
        for(String id:Arrays.asList("volume","main","upper")){fixtureOrder.add(id);fixtureHidden.remove(id);}
        fixtureOrder.remove("upper");fixtureOrder.remove("main");fixtureOrder.add("upper");fixtureOrder.add("main");
        try{
            if(editingOnUi())finishEditingIfPresent();
            if(!fixtureOrder.toString().equals(new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanOrder(originalOrder).split(","))).toString())||!fixtureHidden.toString().equals(new LinkedHashSet<>(Arrays.asList(ControlLogic.cleanHidden(originalHidden).split(","))).toString()))ui(()->controller.updateLayout(controller.layoutKey(),String.join(",",fixtureOrder),String.join(",",fixtureHidden)));
            waitForStableProfile();
            DashboardView panelBeforeStyle=waitForStableFaderPanel();
            if(!originalHorizontal)ui(()->controller.style(controller.layoutKey(),true,originalCompact));
            DashboardView gesturePanel=waitForStableFaderPanel();verifyMainPanelBounds();
            @SuppressWarnings("unchecked") Map<String,View> faders=(Map<String,View>)field(gesturePanel,"faders");View volume=faders.get("volume");require(volume!=null&&volume.getVisibility()==View.VISIBLE,"Master-volume fader is not visible after the temporary test layout");
            ui(()->volume.requestRectangleOnScreen(new Rect(0,0,volume.getWidth(),volume.getHeight()),true));
            Rect visible=new Rect();waitFor(()->volume.getGlobalVisibleRect(visible),"Master-volume fader could not scroll into view");
            try{waitFor(()->{try{if(volume.getWidth()==0)return false;RectF track=(RectF)field(volume,"track");return track!=null&&track.width()>0&&track.height()>0&&Boolean.TRUE.equals(field(volume,"horizontal"));}catch(Exception e){return false;}},"Horizontal Master-volume track did not lay out");}catch(AssertionError failure){throw new AssertionError("Horizontal Master-volume track did not lay out: "+faderTrackDiagnostic(panelBeforeStyle,gesturePanel),failure);}
            RectF volumeTrack=(RectF)field(volume,"track");
            int targetVolume=originalVolume>=60?20:80;holdAndAdjustTrack(automation,gesturePanel,volume,volumeTrack,originalVolume,targetVolume);
            waitFor(()->controller.state.volume!=originalVolume,"Hold-then-horizontal drag on the Master track did not adjust volume");
            int adjustedVolume=controller.state.volume;require(Math.abs(adjustedVolume-originalVolume)>=20,"Master-volume track changed too little to prove horizontal adjustment");
            require(!editingOnUi(),"Hold-then-horizontal drag on the Master track entered edit mode");
            require(controller.state.main==originalMain&&controller.state.upper==originalUpper,"Master-volume gesture changed brightness controls");
            require(controller.state.mainAudio==originalMainAudio&&controller.state.upperAudio==originalUpperAudio,"Master-volume gesture changed app-audio controls");
            require(controller.state.controlOrder.equals(String.join(",",fixtureOrder))&&controller.state.hiddenControls.equals(String.join(",",fixtureHidden))&&controller.state.wideControls.equals(originalWide),"Master-volume gesture changed dashboard control layout");

            View main=faders.get("main"),external=faders.get("upper");require(main!=null&&external!=null&&main.getVisibility()==View.VISIBLE&&external.getVisibility()==View.VISIBLE,"Main/External brightness faders are unavailable for the label rearrange check");
            ui(()->main.requestRectangleOnScreen(new Rect(0,0,main.getWidth(),main.getHeight()),true));
            Rect mainVisible=new Rect(),externalVisible=new Rect();waitFor(()->main.getGlobalVisibleRect(mainVisible)&&external.getGlobalVisibleRect(externalVisible),"Main/External fader rows could not scroll into view for label rearranging");
            RectF mainTrack=(RectF)field(main,"track");float density=target.getResources().getDisplayMetrics().density;float labelX=18*density,labelY=main.getHeight()/2f;
            require(labelX<(mainTrack.left-10)*density,"Main fader label area overlaps the expanded slider touch zone");
            ui(gesturePanel::beginEditing);String beforeReorder=controller.state.controlOrder;holdLabelAndReorder(automation,gesturePanel,main,external,labelX,labelY);
            waitFor(()->!controller.state.controlOrder.equals(beforeReorder),"Long-hold label drag did not rearrange Main/External brightness faders");
            require(controller.state.volume==adjustedVolume,"Label rearrange gesture changed Master volume");
            require(controller.state.main==originalMain&&controller.state.upper==originalUpper,"Label rearrange gesture changed brightness values");
            require(controller.state.mainAudio==originalMainAudio&&controller.state.upperAudio==originalUpperAudio,"Label rearrange gesture changed app-audio values");
            require(controller.state.hiddenControls.equals(originalHidden)&&controller.state.wideControls.equals(originalWide),"Label rearrange gesture changed hidden/wide control settings");
            if(editingOnUi())finishEditingIfPresent();
        }finally{
            try{finishEditingIfPresent();}catch(Exception ignored){}
            ui(()->{
                if(controller.state.main!=originalMain)controller.setMain(originalMain);
                if(controller.state.upper!=originalUpper)controller.setUpper(originalUpper);
                if(controller.state.mainAudio!=originalMainAudio)controller.setMainAudio(originalMainAudio);
                if(controller.state.upperAudio!=originalUpperAudio)controller.setUpperAudio(originalUpperAudio);
                controller.setVolume(originalVolume);controller.updateLayout(key,originalOrder,originalHidden);controller.wide(key,originalWide);controller.style(key,originalHorizontal,originalCompact);
            });
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalStreamVolume,0);restorePreferenceKeys(profilePrefs,originalPrefs,profileKeys);
            waitFor(()->controller.layoutKey().equals(key)&&controller.state.main==originalMain&&controller.state.upper==originalUpper&&controller.state.volume==originalVolume&&controller.state.mainAudio==originalMainAudio&&controller.state.upperAudio==originalUpperAudio&&controller.state.controlOrder.equals(originalOrder)&&controller.state.hiddenControls.equals(originalHidden)&&controller.state.wideControls.equals(originalWide)&&controller.state.horizontalSliders==originalHorizontal&&controller.state.compactControls==originalCompact,"Fader gesture changed profile values or layout after cleanup");
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==originalStreamVolume,"Fader gesture did not restore Android media volume");
            for(String prefKey:profileKeys)require(originalPrefs.containsKey(prefKey)==profilePrefs.contains(prefKey)&&Objects.equals(originalPrefs.get(prefKey),profilePrefs.getAll().get(prefKey)),"Fader gesture did not restore exact profile preference "+prefKey);
        }
    }
    private UiState.DisplayCanvas canvasForIdentity(String identity){for(UiState.DisplayCanvas canvas:controller.state.displayCanvases)if(identity.equals(canvas.identity))return canvas;return null;}
    private UiState.DisplayCanvas canvasForId(int id){for(UiState.DisplayCanvas canvas:controller.state.displayCanvases)if(canvas.id==id)return canvas;return null;}
    private String canvasDimensionsDiagnostic(UiState.DisplayCanvas canvas){StringBuilder detail=new StringBuilder("id=").append(canvas.id).append(" identity=").append(canvas.identity).append(" current=").append(canvas.currentWidth).append("x").append(canvas.currentHeight).append(" rotation=").append(canvas.rotation).append(" native=").append(canvas.nativeWidth).append("x").append(canvas.nativeHeight).append(" options=[");for(int i=0;i<canvas.options.size();i++){UiState.CanvasOption option=canvas.options.get(i);if(i>0)detail.append(", ");detail.append(option.id).append("=").append(option.width).append("x").append(option.height);}return detail.append("]").toString();}
    private UiState.CanvasOption canvasOption(UiState.DisplayCanvas canvas,String id){for(UiState.CanvasOption option:canvas.options)if(id.equals(option.id))return option;return null;}
    private int[] logicalOptionSize(UiState.DisplayCanvas canvas,UiState.CanvasOption option){return new int[]{option.width,option.height};}
    private JSONObject bridgeDisplay(String identity)throws Exception{
        JSONArray screens=new BridgeClient(getTargetContext()).request("status",null).getJSONArray("displays");
        for(int i=0;i<screens.length();i++){JSONObject screen=screens.getJSONObject(i);if(identity.equals(screen.optString("identity")))return screen;}
        throw new AssertionError("Display identity is no longer connected: "+identity);
    }
    private Map<String,String> bridgeLogicalSizes()throws Exception{
        JSONArray screens=new BridgeClient(getTargetContext()).request("status",null).getJSONArray("displays");Map<String,String> sizes=new LinkedHashMap<>();
        for(int i=0;i<screens.length();i++){JSONObject screen=screens.getJSONObject(i);sizes.put(screen.getString("identity"),screen.getInt("width")+"x"+screen.getInt("height"));}return sizes;
    }
    private UiState.ResolutionPreview previewCanvas(String profile,UiState.DisplayCanvas canvas,String optionId)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<UiState.ResolutionPreview> result=new AtomicReference<>();
        ui(()->controller.previewResolution(profile,canvas.id,canvas.identity,optionId,value->{result.set(value);done.countDown();}));
        require(done.await(12,TimeUnit.SECONDS),"Logical-canvas preview callback timed out");return result.get();
    }
    private boolean confirmCanvas(String profile,String transactionId)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> result=new AtomicReference<>(false);
        ui(()->controller.confirmResolution(profile,transactionId,value->{result.set(value);done.countDown();}));
        require(done.await(12,TimeUnit.SECONDS),"Logical-canvas Keep callback timed out");return result.get();
    }
    private boolean revertCanvas(String transactionId)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Boolean> result=new AtomicReference<>(false);
        ui(()->controller.revertResolution(transactionId,value->{result.set(value);done.countDown();}));
        require(done.await(12,TimeUnit.SECONDS),"Logical-canvas Revert callback timed out");return result.get();
    }
    private String canvasPreferenceKey(String profile,String identity){return profile+":canvas:"+(identity==null?"":identity.replaceAll("[^A-Za-z0-9_.:-]","_"));}
    private String canvasStatusDiagnostic(){
        StringBuilder detail=new StringBuilder("feedback=").append(controller==null?"<no controller>":controller.state.feedback).append(", canvasCount=").append(controller==null?0:controller.state.displayCanvases.size());
        try{JSONArray displays=new BridgeClient(getTargetContext()).request("status",null).optJSONArray("displays");detail.append(", statusDisplays=[");if(displays!=null)for(int i=0;i<displays.length();i++){if(i>0)detail.append("; ");JSONObject display=displays.getJSONObject(i);detail.append("id=").append(display.optInt("id")).append(" identity=").append(display.optString("identity")).append(" logical=").append(display.optInt("width")).append("x").append(display.optInt("height")).append(" physical=").append(display.optInt("physicalWidth")).append("x").append(display.optInt("physicalHeight")).append(" rotation=").append(display.optInt("rotation")).append(" choices=").append(display.optJSONArray("canvasChoices"));}detail.append("]");}catch(Exception failure){detail.append(", statusError=").append(failure.getClass().getSimpleName());}return detail.toString();
    }
    private void checkLogicalCanvas()throws Exception{
        require(OnDeviceBridge.isPaired(getTargetContext()),"Saved DuoDash pairing state is missing");
        BridgeActivationService.connect(getTargetContext());
        long helperDeadline=SystemClock.elapsedRealtime()+45_000L;boolean helperReady=false;
        while(SystemClock.elapsedRealtime()<helperDeadline){try{JSONObject helperStatus=new BridgeClient(getTargetContext()).request("status",null);helperReady=BridgeClient.isCurrentHelper(helperStatus.optInt("bridgeBuild",-1),helperStatus.optString("swapStrategy"));}catch(Exception ignored){}if(helperReady)break;Thread.sleep(250L);}
        require(helperReady,"Current paired helper build is unavailable");
        getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");
        waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0;},"Dashboard did not attach");waitForStableProfile();
        long canvasDeadline=SystemClock.elapsedRealtime()+20_000L;boolean canvasReady=false;while(SystemClock.elapsedRealtime()<canvasDeadline){UiState.DisplayCanvas current=canvasForId(0);if(current!=null&&current.currentWidth>0&&current.currentHeight>0){canvasReady=true;break;}Thread.sleep(100L);}require(canvasReady,"Main logical-canvas status did not arrive: "+canvasStatusDiagnostic());
        final String profile=controller.layoutKey();final UiState.DisplayCanvas mainCanvas=canvasForId(0);
        require(mainCanvas.id==0,"Main canvas identity is not bound to display 0");require(!mainCanvas.options.isEmpty(),"Main display has no verified logical-canvas choices");
        UiState.DisplayCanvas externalCanvas=null;for(UiState.DisplayCanvas canvas:controller.state.displayCanvases)if(canvas.id!=0){externalCanvas=canvas;break;}
        require(externalCanvas!=null,"External display is required to verify independent Main/External canvas settings");
        for(UiState.DisplayCanvas canvas:controller.state.displayCanvases){
            require(!canvas.identity.isEmpty(),"Display canvas is missing its stable identity");
            for(UiState.CanvasOption option:canvas.options){require(option.width>0&&option.height>0,"Canvas option has invalid dimensions");String label=option.label.toLowerCase(Locale.ROOT);require(!label.contains("hz")&&!label.contains("refresh"),"Logical canvas option makes an unsupported physical timing claim: "+option.label);}
        }
        UiState.CanvasOption nativeOption=canvasOption(mainCanvas,"native");require(nativeOption!=null,"Main display has no native logical-canvas choice");
        int[] nativeSize=logicalOptionSize(mainCanvas,nativeOption);require(nativeSize[0]==mainCanvas.nativeWidth&&nativeSize[1]==mainCanvas.nativeHeight,"Native choice does not match the reported oriented native canvas");
        UiState.CanvasOption baseline=null,alternative=null;
        for(UiState.CanvasOption option:mainCanvas.options){int[] size=logicalOptionSize(mainCanvas,option);if(size[0]==mainCanvas.currentWidth&&size[1]==mainCanvas.currentHeight)baseline=option;else if(alternative==null&&!option.id.equals("native"))alternative=option;}
        require(baseline!=null,"Native test requires a current logical size represented by a verified canvas choice: "+canvasDimensionsDiagnostic(mainCanvas));require(alternative!=null,"Main display has no alternate safe logical canvas to preview: "+canvasDimensionsDiagnostic(mainCanvas));

        ProfileStore profiles=new ProfileStore(getTargetContext());SharedPreferences prefs=getTargetContext().getSharedPreferences("display_profiles",Context.MODE_PRIVATE);Map<String,?> originalPrefs=new HashMap<>(prefs.getAll());
        String mainPref=canvasPreferenceKey(profile,mainCanvas.identity),externalIdentity=externalCanvas.identity;
        String externalPref=canvasPreferenceKey(profile,externalIdentity),otherProfile=ControlLogic.outputKey("canvas-isolation-test"),otherProfilePref=canvasPreferenceKey(otherProfile,mainCanvas.identity);
        String savedMain=profiles.canvas(profile,mainCanvas.identity),savedExternal=profiles.canvas(profile,externalIdentity),savedOtherProfile=profiles.canvas(otherProfile,mainCanvas.identity);
        Set<String> canvasPrefs=new LinkedHashSet<>(Arrays.asList(mainPref,externalPref,otherProfilePref));Map<String,String> beforeSizes=bridgeLogicalSizes();
        try{
            UiState.CanvasOption otherOption=externalCanvas.options.get(externalCanvas.options.size()-1);
            require(otherOption!=null,"Could not find an independent logical-canvas option");
            profiles.canvas(profile,mainCanvas.identity,alternative.id);profiles.canvas(profile,externalIdentity,otherOption.id);profiles.canvas(otherProfile,mainCanvas.identity,otherOption.id);
            require(alternative.id.equals(profiles.canvas(profile,mainCanvas.identity)),"Main canvas choice was not stored for the stable identity");
            require(otherOption.id.equals(profiles.canvas(profile,externalIdentity)),"External canvas choice was not stored independently");
            require(otherOption.id.equals(profiles.canvas(otherProfile,mainCanvas.identity)),"Canvas choice was not isolated by output profile");
            profiles.canvas(profile,mainCanvas.identity,"60Hz");require(alternative.id.equals(profiles.canvas(profile,mainCanvas.identity)),"Unsupported canvas identifier overwrote the saved logical size");
        }finally{restorePreferenceKeys(prefs,originalPrefs,canvasPrefs);}

        String savedBefore=profiles.canvas(profile,mainCanvas.identity);int originalWidth=mainCanvas.currentWidth,originalHeight=mainCanvas.currentHeight;
        try{
            UiState.CanvasOption externalBaseline=null,externalAlternative=null;
            for(UiState.CanvasOption option:externalCanvas.options){int[] size=logicalOptionSize(externalCanvas,option);if(size[0]==externalCanvas.currentWidth&&size[1]==externalCanvas.currentHeight)externalBaseline=option;else if(externalAlternative==null&&!option.id.equals("native"))externalAlternative=option;}
            require(externalBaseline!=null&&externalAlternative!=null,"External display lacks a current verified canvas and a safe alternate choice: "+canvasDimensionsDiagnostic(externalCanvas));
            JSONObject originalExternal=bridgeDisplay(externalCanvas.identity);int externalWidth=originalExternal.getInt("width"),externalHeight=originalExternal.getInt("height");
            UiState.ResolutionPreview externalPreview=previewCanvas(profile,externalCanvas,externalAlternative.id);require(externalPreview.ok,"External logical-canvas preview did not start");
            JSONObject changedExternal=bridgeDisplay(externalCanvas.identity);require(changedExternal.getInt("width")==externalPreview.width&&changedExternal.getInt("height")==externalPreview.height,"External preview response does not match the applied logical dimensions");
            require(bridgeDisplay(mainCanvas.identity).getInt("width")==originalWidth&&bridgeDisplay(mainCanvas.identity).getInt("height")==originalHeight,"External preview changed Main logical dimensions");
            require(revertCanvas(externalPreview.transactionId),"External explicit Revert did not complete");
            JSONObject restoredExternal=bridgeDisplay(externalCanvas.identity);require(restoredExternal.getInt("width")==externalWidth&&restoredExternal.getInt("height")==externalHeight,"External Revert did not restore the prior logical dimensions");
            require(bridgeLogicalSizes().equals(beforeSizes),"External preview/Revert changed another display's logical canvas");

            UiState.DisplayCanvas staleCanvas=new UiState.DisplayCanvas();staleCanvas.id=mainCanvas.id;staleCanvas.identity=mainCanvas.identity+"-disconnected";
            UiState.ResolutionPreview stale=previewCanvas(profile,staleCanvas,alternative.id);
            require(!stale.ok,"A preview with a stale display identity was accepted");require(Objects.equals(savedBefore,profiles.canvas(profile,mainCanvas.identity)),"A rejected/stale preview changed the saved logical canvas");
            require(bridgeLogicalSizes().equals(beforeSizes),"A rejected/stale preview changed a display's logical size");

            UiState.ResolutionPreview preview=previewCanvas(profile,mainCanvas,alternative.id);require(preview.ok&&preview.transactionId!=null&&!preview.transactionId.isEmpty(),"Logical-canvas preview did not start");
            JSONObject changed=bridgeDisplay(mainCanvas.identity);require(changed.getInt("width")==preview.width&&changed.getInt("height")==preview.height,"Preview response does not match the applied logical dimensions");
            for(Map.Entry<String,String> screen:beforeSizes.entrySet())if(!screen.getKey().equals(mainCanvas.identity))require(screen.getValue().equals(bridgeLogicalSizes().get(screen.getKey())),"Preview changed another display's logical canvas");
            require(!confirmCanvas("output:stale-canvas-profile",preview.transactionId),"Keep accepted a transaction for a profile that is no longer active");
            require(Objects.equals(savedBefore,profiles.canvas(profile,mainCanvas.identity)),"Rejected stale-profile Keep persisted the preview choice");
            require(revertCanvas(preview.transactionId),"Explicit Revert did not complete");require(bridgeLogicalSizes().equals(beforeSizes),"Explicit Revert did not restore every previous logical canvas");
            require(Objects.equals(savedBefore,profiles.canvas(profile,mainCanvas.identity)),"Explicit Revert persisted an unconfirmed choice");

            UiState.ResolutionPreview timed=previewCanvas(profile,mainCanvas,alternative.id);require(timed.ok,"Timed logical-canvas preview did not start");
            long rollbackDeadline=SystemClock.elapsedRealtime()+20_000L;Map<String,String> afterTimeout=bridgeLogicalSizes();
            while(!afterTimeout.equals(beforeSizes)&&SystemClock.elapsedRealtime()<rollbackDeadline){Thread.sleep(250L);afterTimeout=bridgeLogicalSizes();}
            UiState.ResolutionPreview timeoutPending=controller.pendingResolution();
            require(afterTimeout.equals(beforeSizes),"Expired preview did not restore the previous logical canvas values within 5s: before="+beforeSizes+" after="+afterTimeout+" pending="+(timeoutPending==null?"none":timeoutPending.transactionId));
            require(Objects.equals(savedBefore,profiles.canvas(profile,mainCanvas.identity)),"Expired preview persisted an unconfirmed choice");
            UiState.ResolutionPreview pending=controller.pendingResolution();if(pending!=null)revertCanvas(pending.transactionId);

            int[] baselineSize=logicalOptionSize(mainCanvas,baseline);UiState.ResolutionPreview keep=previewCanvas(profile,mainCanvas,alternative.id);require(keep.ok,"Keep preview did not start");
            require(confirmCanvas(profile,keep.transactionId),"Keep did not confirm the logical-canvas choice");
            require(alternative.id.equals(profiles.canvas(profile,mainCanvas.identity)),"Keep did not persist the selected choice for the stable display identity");
            UiState.ResolutionPreview restore=previewCanvas(profile,mainCanvas,baseline.id);require(restore.ok,"Baseline restore preview did not start");require(confirmCanvas(profile,restore.transactionId),"Baseline restore Keep did not complete");
            JSONObject restored=bridgeDisplay(mainCanvas.identity);require(restored.getInt("width")==originalWidth&&restored.getInt("height")==originalHeight,"Confirmed restore did not return to the pre-test logical size "+baselineSize[0]+"x"+baselineSize[1]);
        }finally{
            try{
                UiState.ResolutionPreview pending=controller.pendingResolution();if(pending!=null)revertCanvas(pending.transactionId);
                JSONObject current=bridgeDisplay(mainCanvas.identity);
                if(current.getInt("width")!=originalWidth||current.getInt("height")!=originalHeight){UiState.ResolutionPreview restore=previewCanvas(profile,mainCanvas,baseline.id);if(restore.ok)confirmCanvas(profile,restore.transactionId);}
            }catch(Exception ignored){}
            restorePreferenceKeys(prefs,originalPrefs,canvasPrefs);
        }
    }
    private void checkDisplayBars(){
        Map<String,?> saved=new HashMap<>(getTargetContext().getSharedPreferences("display_profiles",0).getAll());
        ui(()->{try{
            DashboardActions actions=(DashboardActions)java.lang.reflect.Proxy.newProxyInstance(DashboardActions.class.getClassLoader(),new Class<?>[]{DashboardActions.class},(proxy,method,args)->method.getReturnType()==boolean.class?false:null);
            DashboardView view=new DashboardView(getTargetContext(),actions);
            UiState value=new UiState();value.controlOrder=ControlLogic.DEFAULT_ORDER;value.hiddenControls="";value.bridgeReady=true;
            String[] bars={"both","upper","main","audio_upper","audio_main","volume"};
            @SuppressWarnings("unchecked") Map<String,View> faders=(Map<String,View>)field(view,"faders");
            for(boolean horizontal:new boolean[]{true,false}){
                value.horizontalSliders=horizontal;
                for(boolean connected:new boolean[]{true,false,true}){
                    value.externalConnected=connected;view.render(value);
                    view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));view.layout(0,0,1280,960);
                    for(String id:bars)require((faders.get(id).getVisibility()==View.VISIBLE)==ControlLogic.visibleOnDisplay(id,connected),"Wrong visibility for "+id+" connected="+connected+" horizontal="+horizontal);
                    LinearLayout brightness=(LinearLayout)field(view,"brightnessBank"),audio=(LinearLayout)field(view,"audioBank");
                    require(brightness.getChildCount()==(connected?5:1)&&audio.getChildCount()==(connected?5:1),"Hidden bars left empty rows or separators");
                    require(value.hiddenControls.isEmpty()&&value.controlOrder.equals(ControlLogic.DEFAULT_ORDER),"Connection changed the saved arrangement");
                }
            }
            view.beginEditing();value.externalConnected=false;view.render(value);
            require(!Boolean.TRUE.equals(field(view,"editing")),"Disconnect left stale editing targets");
            value.hiddenControls="audio_upper";value.externalConnected=true;view.render(value);
            require(faders.get("audio_upper").getVisibility()==View.GONE,"Reconnect undid an explicit removal");
            require(faders.get("both").getVisibility()==View.VISIBLE&&faders.get("audio_main").getVisibility()==View.VISIBLE,"Reconnect failed to restore retained bars");
        }catch(Exception e){throw new RuntimeException(e);}});
        require(saved.equals(getTargetContext().getSharedPreferences("display_profiles",0).getAll()),"Rendering changed profile preferences");
    }
    private void checkControlShortcuts(UiAutomation automation)throws Exception{
        Context context=getTargetContext();require(SetupReadiness.hasCompletedSetup(context),"Complete DuoDash setup before testing shortcuts");
        rebindEnabledAccessibilityService(automation);OnDeviceBridge.reconnectAndStart(context);
        context.startForegroundService(new Intent(context,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");waitFor(()->DeckAccessibilityService.connected!=null,"DuoDash accessibility controls unavailable");
        SharedPreferences uiPrefs=context.getSharedPreferences("ui",Context.MODE_PRIVATE),profiles=context.getSharedPreferences("display_profiles",Context.MODE_PRIVATE);
        Map<String,?> originalUi=new HashMap<>(uiPrefs.getAll()),originalProfiles=new HashMap<>(profiles.getAll());AudioManager audio=context.getSystemService(AudioManager.class);
        require(audio!=null,"Android audio service unavailable");int originalStream=audio.getStreamVolume(AudioManager.STREAM_MUSIC);boolean originalMute=audio.isStreamMute(AudioManager.STREAM_MUSIC);
        boolean wasPanel=(Boolean)field(service,"panelShowing"),wasSettings=service.settingsOpen;String oldCategory=settingsPage()==null?"output":settingsPage().category();boolean passed=false;
        try{
            ui(()->{uiPrefs.edit().putString("shortcuts","139").putString(ShortcutBindings.PREF,"").commit();DeckAccessibilityService.connected.reloadShortcuts();controller.configureShortcuts();service.performControl("shortcuts");});
            waitFor(this::shortcutSettingsLaidOut,"Shortcuts settings page did not open with measured bounds");
            ShortcutSettingsView page=shortcutSettings();
            ui(()->saveShortcutSettingsFrame(page,"control-shortcuts-page.png"));
            InlineShortcutEditor dashboard=shortcutEditor(page,ShortcutBindings.TOGGLE);java.util.concurrent.atomic.AtomicReference<View> protectedRemoveRef=new java.util.concurrent.atomic.AtomicReference<>();
            ui(()->protectedRemoveRef.set(described(dashboard,"At least one shortcut is required")));View protectedRemove=protectedRemoveRef.get();
            require(protectedRemove!=null&&!protectedRemove.isEnabled(),"The last DuoDash shortcut can be removed");ui(protectedRemove::performClick);require("139".equals(uiPrefs.getString("shortcuts","")),"Removing the final DuoDash binding changed the saved shortcut");
            ui(()->textView(page,"Add a control shortcut").performClick());ui(()->textView(page,"Mute master audio").performClick());
            InlineShortcutEditor mute=shortcutEditor(page,"mute_master");ui(mute::addBinding);sendChord(mute,139);
            ui(()->textView(mute,"Save shortcut").performClick());require(!new ShortcutBindings("139",uiPrefs.getString(ShortcutBindings.PREF,"")).controls().containsKey("mute_master"),"A control binding conflicting with DuoDash was saved");
            ui(()->require(textContaining(mute,"Already used")!=null,"The conflicting shortcut was not explained"));
            ui(()->textView(mute,"Try again").performClick());sendChord(mute,104,105,106);ui(()->textView(mute,"Save shortcut").performClick());
            require("104,105,106".equals(new ShortcutBindings("139",uiPrefs.getString(ShortcutBindings.PREF,"")).shortcuts("mute_master")),"Three-button control binding did not save");
            String token=new String(java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),"bridge-token").toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
            Intent dispatch=new Intent(context,GamepadShortcutReceiver.class).setAction("dev.nova.deck.GAMEPAD_EVENT").putExtra("token",token).putExtra("shortcut_action","mute_master");
            boolean muteBefore=audio.isStreamMute(AudioManager.STREAM_MUSIC);new GamepadShortcutReceiver().onReceive(context,dispatch);
            waitFor(()->audio.isStreamMute(AudioManager.STREAM_MUSIC)!=muteBefore,"Authenticated controller shortcut did not run the saved mute action");
            require(audio.isStreamMute(AudioManager.STREAM_MUSIC)==!muteBefore,"Saved mute shortcut produced the wrong action state");
            new GamepadShortcutReceiver().onReceive(context,dispatch);waitFor(()->audio.isStreamMute(AudioManager.STREAM_MUSIC)==muteBefore,"Second saved mute shortcut did not restore the original audio state");
            InlineShortcutEditor updated=shortcutEditor(page,"mute_master");java.util.concurrent.atomic.AtomicReference<View> replaceRef=new java.util.concurrent.atomic.AtomicReference<>();ui(()->replaceRef.set(described(updated,"Replace ")));View replace=replaceRef.get();require(replace!=null,"Saved binding cannot be tapped to replace");ui(replace::performClick);sendChord(updated,102,103);ui(()->textView(updated,"Save shortcut").performClick());
            require("102,103".equals(new ShortcutBindings("139",uiPrefs.getString(ShortcutBindings.PREF,"")).shortcuts("mute_master")),"Replacing a control shortcut left the old chord");
            InlineShortcutEditor replaced=shortcutEditor(page,"mute_master");java.util.concurrent.atomic.AtomicReference<View> removeRef=new java.util.concurrent.atomic.AtomicReference<>();ui(()->removeRef.set(described(replaced,"Remove ")));View remove=removeRef.get();require(remove!=null,"Control binding removal is unavailable");ui(remove::performClick);
            require(!new ShortcutBindings("139",uiPrefs.getString(ShortcutBindings.PREF,"")).controls().containsKey("mute_master"),"Removing the control binding did not clear its section");
            require(originalStream==audio.getStreamVolume(AudioManager.STREAM_MUSIC),"Shortcut action changed the media volume level");passed=true;
        }finally{
            if(service!=null&&shortcutSettings()!=null)ui(()->shortcutSettings().cancelPending());
            if(audio.isStreamMute(AudioManager.STREAM_MUSIC)!=originalMute)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,originalMute?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
            if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=originalStream)audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalStream,0);
            restoreAllPreferences(uiPrefs,originalUi);restoreAllPreferences(profiles,originalProfiles);
            if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();if(controller!=null)controller.configureShortcuts();
            ui(()->{if(service!=null&&settingsPage()!=null){if(wasSettings)settingsPage().selectCategory(oldCategory);else service.exitSettingsPage();}});
            if(!wasPanel&&service!=null)service.closePanel();
            require(originalUi.equals(uiPrefs.getAll()),"Shortcuts test did not restore all UI preferences");require(originalProfiles.equals(profiles.getAll()),"Shortcuts test did not restore all display profile preferences");
            require(originalMute==audio.isStreamMute(AudioManager.STREAM_MUSIC)&&originalStream==audio.getStreamVolume(AudioManager.STREAM_MUSIC),"Shortcuts test did not restore Android media state");
        }
        require(passed,"Shortcut UI test did not complete");
    }
    private void checkOverlapShortcuts(UiAutomation automation)throws Exception{
        Context context=getTargetContext();require(SetupReadiness.hasCompletedSetup(context),"Complete DuoDash setup before testing shortcuts");
        rebindEnabledAccessibilityService(automation);OnDeviceBridge.reconnectAndStart(context);context.startForegroundService(new Intent(context,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady&&DeckAccessibilityService.connected!=null,"System controls unavailable");
        SharedPreferences uiPrefs=context.getSharedPreferences("ui",0),profiles=context.getSharedPreferences("display_profiles",0);Map<String,?> oldUi=new HashMap<>(uiPrefs.getAll()),oldProfiles=new HashMap<>(profiles.getAll());
        AudioManager audio=context.getSystemService(AudioManager.class);int volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);boolean muted=audio.isStreamMute(AudioManager.STREAM_MUSIC);boolean wasPanel=(Boolean)field(service,"panelShowing"),wasSettings=service.settingsOpen;String oldCategory=settingsPage()==null?"output":settingsPage().category();
        try{
            ui(()->{uiPrefs.edit().putString("shortcuts","98").putString(ShortcutBindings.PREF,"").commit();DeckAccessibilityService.connected.reloadShortcuts();controller.configureShortcuts();service.performControl("shortcuts");});
            waitFor(this::shortcutSettingsLaidOut,"Shortcuts settings did not open");ShortcutSettingsView page=shortcutSettings();
            ui(()->textView(page,"Add a control shortcut").performClick());ui(()->textView(page,"Mute master audio").performClick());InlineShortcutEditor editor=shortcutEditor(page,"mute_master");sendChord(editor,98,101);ui(()->textView(editor,"Save shortcut").performClick());
            require("98,101".equals(new ShortcutBindings("98",uiPrefs.getString(ShortcutBindings.PREF,"")).shortcuts("mute_master")),"Overlapping C and C+Z bindings were not saved");
            ui(()->require(textContaining(page,"on release")!=null,"Deferred shortcut did not show its release hint"));
            ui(()->service.exitSettingsPage());waitFor(()->settingsPage()==null,"Settings page did not close");ui(()->service.closePanel());waitFor(()->panel()==null,"Dashboard did not close");Thread.sleep(500);
            DeckAccessibilityService access=DeckAccessibilityService.connected;access.reloadShortcuts();
            accessibilityKey(KeyEvent.ACTION_DOWN,98);require(panel()==null,"DuoDash C toggled before the longer C+Z chord completed");accessibilityKey(KeyEvent.ACTION_UP,98);waitFor(()->panel()!=null,"DuoDash C did not toggle after release without Z");
            ui(()->service.closePanel());waitFor(()->panel()==null,"Dashboard did not close after C shortcut");Thread.sleep(500);
            accessibilityKey(KeyEvent.ACTION_DOWN,98);accessibilityKey(KeyEvent.ACTION_DOWN,101);waitFor(()->audio.isStreamMute(AudioManager.STREAM_MUSIC)!=muted,"C+Z did not dispatch the control shortcut");
            accessibilityKey(KeyEvent.ACTION_UP,98);accessibilityKey(KeyEvent.ACTION_UP,101);Thread.sleep(500);require(panel()==null,"Releasing the completed C+Z chord also toggled DuoDash");
            accessibilityKey(KeyEvent.ACTION_DOWN,98);accessibilityKey(KeyEvent.ACTION_DOWN,101);waitFor(()->audio.isStreamMute(AudioManager.STREAM_MUSIC)==muted,"Second C+Z action did not restore the original mute state");
            accessibilityKey(KeyEvent.ACTION_UP,101);accessibilityKey(KeyEvent.ACTION_UP,98);Thread.sleep(500);require(panel()==null,"Reverse release order of C+Z toggled DuoDash");
            require(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume,"Overlap shortcut changed master volume level");
        }finally{
            if(service!=null&&shortcutSettings()!=null)ui(()->shortcutSettings().cancelPending());
            if(audio.isStreamMute(AudioManager.STREAM_MUSIC)!=muted)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
            if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=volume)audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);
            restoreAllPreferences(uiPrefs,oldUi);restoreAllPreferences(profiles,oldProfiles);if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();if(controller!=null)controller.configureShortcuts();
            ui(()->{if(service!=null&&settingsPage()!=null){if(wasSettings)settingsPage().selectCategory(oldCategory);else service.exitSettingsPage();}if(service!=null&&panel()!=null)service.closePanel();});
            if(wasPanel)context.startForegroundService(new Intent(context,OverlayService.class).setAction(OverlayService.SHOW));
            require(oldUi.equals(uiPrefs.getAll()),"Overlap test did not restore UI preferences");require(oldProfiles.equals(profiles.getAll()),"Overlap test did not restore display profiles");require(audio.isStreamMute(AudioManager.STREAM_MUSIC)==muted&&audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume,"Overlap test did not restore audio state");
        }
    }
    private void accessibilityKey(int action,int code){ui(()->{DeckAccessibilityService access=DeckAccessibilityService.connected;require(access!=null,"DuoDash accessibility service disconnected");access.onKeyEvent(new KeyEvent(action,code));});}
    private ShortcutSettingsView shortcutSettings(){return (ShortcutSettingsView)fieldUnchecked(service,"shortcutSettings");}
    private boolean shortcutSettingsLaidOut(){java.util.concurrent.atomic.AtomicReference<Boolean> ready=new java.util.concurrent.atomic.AtomicReference<>(false);ui(()->{ShortcutSettingsView view=shortcutSettings();ready.set(view!=null&&view.isAttachedToWindow()&&view.isLaidOut()&&view.getWidth()>0&&view.getHeight()>0);});return ready.get();}
    private SettingsPageView settingsPage(){return (SettingsPageView)fieldUnchecked(service,"settingsPage");}
    private InlineShortcutEditor shortcutEditor(ShortcutSettingsView page,String action)throws Exception{
        AtomicReference<InlineShortcutEditor> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();ui(()->{try{for(InlineShortcutEditor editor:page.editors())if(action.equals(field(editor,"actionId"))){result.set(editor);return;}throw new AssertionError("Shortcut editor missing for "+action);}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new RuntimeException(failure.get());return result.get();
    }
    private void sendChord(InlineShortcutEditor editor,int...keys){
        ui(()->{DeckAccessibilityService connected=DeckAccessibilityService.connected;require(connected!=null&&connected.recorder!=null,"Native shortcut recorder did not start");DeckAccessibilityService.Recorder recorder=connected.recorder;for(int key:keys)recorder.event(new KeyEvent(KeyEvent.ACTION_DOWN,key));for(int i=keys.length-1;i>=0;i--)recorder.event(new KeyEvent(KeyEvent.ACTION_UP,keys[i]));});
    }
    private void saveShortcutSettingsFrame(View view,String filename){
        require(view.getWidth()>0&&view.getHeight()>0,"Shortcut settings page has no measured frame");Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getFilesDir(),filename))){view.draw(new Canvas(bitmap));require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Could not save shortcut settings frame");}catch(IOException error){throw new RuntimeException(error);}finally{bitmap.recycle();}
    }
    private void restoreAllPreferences(SharedPreferences prefs,Map<String,?> values){
        SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String key=entry.getKey();if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else if(v instanceof Long)edit.putLong(key,(Long)v);else if(v instanceof Float)edit.putFloat(key,(Float)v);else if(v instanceof String)edit.putString(key,(String)v);else if(v instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)v));}edit.commit();
    }

    private void checkDebuggingKeepAlive(UiAutomation automation) throws Exception {
        Context context=getTargetContext();
        int originalWireless=android.provider.Settings.Global.getInt(context.getContentResolver(),"adb_wifi_enabled",0);
        require(originalWireless==1,"Enable Wireless debugging before the device check");
        SharedPreferences profiles=context.getSharedPreferences("display_profiles",0);
        Map<String,?> beforeProfiles=new HashMap<>(profiles.getAll());
        boolean wifiDisabled=false;
        JSONObject proof=new JSONObject();
        try {
            require(context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED,"Restore permission missing");
            require(android.provider.Settings.Global.putInt(context.getContentResolver(),android.provider.Settings.Global.ADB_ENABLED,0),"Could not establish USB-off fixture");
            require(!UsbDebugging.isEnabled(context),"USB-off fixture did not apply");
            Thread.sleep(2000);
            OnDeviceBridge.reconnectAndStart(context);
            require(UsbDebugging.isEnabled(context),"Restore did not automatically enable USB debugging");
            BridgeClient bridge=new BridgeClient(context);
            require(bridge.isHealthy(),"Restored helper is unavailable");
            proof.put("usb_auto_enabled",true);
            Thread.sleep(12000);
            String pid=shellOutput(automation,"pidof nova_deck").trim();
            require(pid.matches("[0-9]+"),"Expected one authenticated helper process");
            shell(automation,"kill "+pid);
            Thread.sleep(400);
            require(android.provider.Settings.Global.putInt(context.getContentResolver(),android.provider.Settings.Global.ADB_ENABLED,0),"Could not establish cold-start USB-off fixture");
            Thread.sleep(2000);
            OnDeviceBridge.reconnectAndStart(context);
            require(UsbDebugging.isEnabled(context)&&bridge.isHealthy(),"Cold startup did not enable USB and start the helper");
            proof.put("cold_start_usb_enabled",true);
            Thread.sleep(12000);
            proof.put("usb_after_wait",UsbDebugging.isEnabled(context));
            proof.put("adbd_before_disable",shellOutput(automation,"pidof adbd").trim());
            require(bridge.isHealthy(),"Helper died before disabling Wireless debugging");
            android.provider.Settings.Global.putInt(context.getContentResolver(),"adb_wifi_enabled",0);
            Thread.sleep(1500);
            require(bridge.isHealthy(),"Helper died when Wireless debugging was disabled with USB on");
            proof.put("wireless_debugging_off_helper_healthy",true);
            wifiDisabled=true;
            shell(automation,"svc wifi disable");
            Thread.sleep(2000);
            require("0".equals(shellOutput(automation,"settings get global wifi_on").trim()),"Wi-Fi did not turn off");
            require(UsbDebugging.isEnabled(context)&&bridge.isHealthy(),"Helper died when Wi-Fi was turned off");
            proof.put("wifi_off_helper_healthy",true);
            int main=bridge.request("status",null).getInt("main");
            int probe=main<=85?main+10:main-10;
            bridge.request("main_preview",new JSONObject().put("percent",main));
            bridge.request("main_preview_clear",null);
            Thread.sleep(400);
            int backlightBefore=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());
            try {
                bridge.request("main_preview",new JSONObject().put("percent",probe));
                Thread.sleep(400);
                int changed=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());
                require(changed!=backlightBefore,"Offline control request did not change the physical backlight");
                proof.put("offline_backlight_changed",true);
            } finally {
                bridge.request("main_preview",new JSONObject().put("percent",main));
                bridge.request("main_preview_clear",null);
            }
            Thread.sleep(400);
            int backlightRestored=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());
            int maximum=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/max_brightness").trim());
            proof.put("backlight_before",backlightBefore).put("backlight_restored",backlightRestored);
            require(Math.abs(backlightRestored-backlightBefore)<=Math.max(1,maximum/50),"Offline preview did not restore original backlight within hardware rounding tolerance");
            require(beforeProfiles.equals(profiles.getAll()),"Keepalive test changed saved profiles");
            proof.put("profiles_preserved",true).put("passed",true);
        } catch(Exception|AssertionError failure) {
            proof.put("passed",false).put("failure",failure.toString()).put("usb_at_failure",UsbDebugging.isEnabled(context));
            throw failure;
        } finally {
            UsbDebugging.ensureEnabled(context);
            if(wifiDisabled)shell(automation,"svc wifi enable");
            android.net.ConnectivityManager connectivity=context.getSystemService(android.net.ConnectivityManager.class);
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(SystemClock.elapsedRealtime()<deadline){
                boolean connected=false;
                for(android.net.Network network:connectivity.getAllNetworks()){
                    android.net.NetworkCapabilities capabilities=connectivity.getNetworkCapabilities(network);
                    if(capabilities!=null&&capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)){connected=true;break;}
                }
                if(connected)break;
                Thread.sleep(250);
            }
            android.provider.Settings.Global.putInt(context.getContentResolver(),"adb_wifi_enabled",originalWireless);
            try(FileOutputStream output=context.openFileOutput("beta2-debugging-proof.json",Context.MODE_PRIVATE)){
                output.write(proof.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private void checkQuickPanel(UiAutomation automation) throws Exception {
        rebindEnabledAccessibilityService(automation);
        Context context=getTargetContext();
        context.startForegroundService(new Intent(context,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"Helper unavailable");
        waitFor(()->panel()!=null&&panel().isAttachedToWindow(),"Dashboard missing");
        waitForStableProfile();
        Map<String,?> originalProfiles=new HashMap<>(context.getSharedPreferences("display_profiles",0).getAll());
        require("Settings".equals(ControlCatalog.find("quick_settings").label),"Default Settings control changed");
        require(!ControlCatalog.find("quick_panel").defaultShown,"Quick settings must remain optional");
        try {
            ui(()->service.performControl("quick_panel"));
            waitFor(()->{try{return dev.nova.deck.bridge.FocusedShadeParser.isNotificationShadeFocused(shellOutput(automation,"dumpsys window displays"),0);}catch(Exception e){return false;}},"Quick settings did not take focus");
            require(!Boolean.TRUE.equals(field(service,"panelShowing")),"Dashboard blocked the quick-settings panel");
            Thread.sleep(900);
            Bitmap screenshot=automation.takeScreenshot();
            require(screenshot!=null,"Could not capture Quick settings");
            try(FileOutputStream output=context.openFileOutput("beta2-quick-panel.png",Context.MODE_PRIVATE)){screenshot.compress(Bitmap.CompressFormat.PNG,100,output);}finally{screenshot.recycle();}
        } finally {
            DeckAccessibilityService accessibility=DeckAccessibilityService.connected;
            if(accessibility!=null){accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(200);accessibility.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);}
            context.startForegroundService(new Intent(context,OverlayService.class).setAction(OverlayService.SHOW));
        }
        require(originalProfiles.equals(context.getSharedPreferences("display_profiles",0).getAll()),"Opening Quick settings changed profiles");
    }

    @Override public void onStart(){
        Bundle result=new Bundle();boolean pass=false;
        try{
            if("componentrender".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkComponentRender();result.putString("fixtures","componentrender-handheld-horizontal.png, componentrender-handheld-vertical.png, componentrender-external-horizontal.png, componentrender-external-vertical.png, componentrender-edit.png");result.putString("checks","Standalone 1280×960 dashboard renders for handheld/external and horizontal/vertical/edit states; slider keyboard and accessibility changes called injected callbacks; horizontal and vertical drag preview/cancel restored values without service, helper, or profile persistence");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("displaybars".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkDisplayBars();result.putString("checks","Native horizontal and vertical layouts: 6 to 2 to 6 bars, no empty separators, edit-mode disconnect, manual removal retained, stored profiles unchanged");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("swaptest".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                Context target=getTargetContext();BridgeClient client=new BridgeClient(target);JSONObject before=client.request("status",null);
                require(BridgeClient.isCurrentHelper(before.optInt("bridgeBuild",-1),before.optString("swapStrategy")),"Swap test is connected to a stale helper");
                JSONArray displays=before.getJSONArray("displays");int external=-1;String identity=null;
                for(int i=0;i<displays.length();i++){JSONObject display=displays.getJSONObject(i);if(display.optInt("type")==2){external=display.optInt("id");identity=display.optString("identity");break;}}
                require(external>0&&identity!=null&&!identity.isEmpty(),"No external display is available for the swap test");
                JSONObject beforeTasks=placementSummary(before.getJSONArray("tasks"));
                JSONObject reply=client.request("swap",new JSONObject().put("display",external).put("identity",identity));Thread.sleep(900);
                JSONObject after=client.request("status",null);JSONObject afterTasks=placementSummary(after.getJSONArray("tasks"));
                JSONObject proof=new JSONObject().put("bridge_build",before.getInt("bridgeBuild"))
                    .put("swap_strategy",before.getString("swapStrategy")).put("external_display",external)
                    .put("message",reply.optString("message")).put("before",beforeTasks).put("after",afterTasks);
                try(FileOutputStream output=target.openFileOutput("swap-build24-check.json",Context.MODE_PRIVATE)){
                    output.write(proof.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                result.putInt("bridge_build",before.getInt("bridgeBuild"));result.putString("swap_strategy",before.getString("swapStrategy"));
                result.putInt("external_display",external);result.putString("before",beforeTasks.toString());result.putString("after",afterTasks.toString());
                result.putString("checks","One swap executed through the authenticated DuoDash helper; task IDs and display placement recorded without ROM paths");
                pass=true;finish(Activity.RESULT_OK,result);return;
            }
            UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            if("debuggingkeepalive".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkDebuggingKeepAlive(automation);result.putString("checks","USB-off restore and cold startup enabled USB debugging; helper and physical brightness control survived Wireless debugging and Wi-Fi off; profiles and backlight restored");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("controlshortcuts".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkControlShortcuts(automation);result.putString("capture","control-shortcuts-page.png");result.putString("checks","DuoDash final binding remained protected; conflicting control combo was rejected; three-key combo saved, authenticated mute action ran, replacement and removal restored clean bindings; UI and profile preferences were preserved");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("overlapshortcuts".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkOverlapShortcuts(automation);result.putString("checks","C waited for release, C+Z dispatched master mute without toggling the dashboard in either release order; UI, profiles and audio were restored");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("quickpanel".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkQuickPanel(automation);result.putString("checks","Optional Quick settings opened the focused Android shade after closing DuoDash; default Settings unchanged; profiles preserved");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("pairingpanel".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                String geometry=checkPairingPanel();result.putString("panel_geometry",geometry);
                result.putString("capture","pairing-panel-beta3.png");
                result.putString("checks","Compact in-memory keypad attached as a non-focusable main-display accessibility overlay; dummy digit and clear worked; saved pairing was unchanged and the panel closed");
                pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("setupgate".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkSetupGate(automation);result.putString("checks","The setup completion stamp was temporarily removed; service SHOW routed to the three-step wizard with no dashboard windows, live grants/pairing/helper passed, Finish recorded completion, and Hide/reopen respected the existing floating-handle preference");result.putString("captures","setup-beta-step1.png, setup-beta-step2.png, setup-beta-step3.png, setup-beta-dashboard.png");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("sliders".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                Bundle diagnostics=checkHardwareSliders(automation);result.putString("checks","Main Fader temporary brightness changed the physical backlight, release persisted it, original profile restored, and active main audio track was checked/restored");result.putString("profile",diagnostics.getString("profile"));result.putInt("display_before",diagnostics.getInt("display_before"));result.putInt("profile_before",diagnostics.getInt("profile_before"));result.putInt("display_probe",diagnostics.getInt("display_probe"));result.putInt("backlight_before",diagnostics.getInt("backlight_before"));result.putInt("backlight_preview",diagnostics.getInt("backlight_preview"));result.putInt("backlight_committed",diagnostics.getInt("backlight_committed"));result.putInt("backlight_restored",diagnostics.getInt("backlight_restored"));result.putFloat("audio_db_before",diagnostics.getFloat("audio_db_before"));result.putFloat("audio_db_half",diagnostics.getFloat("audio_db_half"));result.putFloat("audio_db_restored",diagnostics.getFloat("audio_db_restored"));result.putBoolean("temporary_hw_applied",diagnostics.getBoolean("temporary_hw_applied"));result.putBoolean("fader_to_display0",diagnostics.getBoolean("fader_to_display0"));result.putBoolean("fader_to_backlight",diagnostics.getBoolean("fader_to_backlight"));result.putString("audio",diagnostics.getString("audio"));pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("fadergesture".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkFaderGesture(automation);result.putString("checks","Long hold plus horizontal drag on the Master track adjusted volume without entering edit; in Edit mode, long hold and drop on the Main fader label rearranged the brightness controls; profile values, media volume, and layout were restored");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("fadermute".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkFaderMute(automation);result.putString("checks","Fader rows share 54dp height and track geometry; mute/eye controls retain 40×44dp targets and 16dp glyphs; master mute/unmute restored the actual media stream level after a controller poll; Main/External app mute restored exact per-profile gains and isolation");result.putBoolean("main_audio_route_verified",mainMuteRouteVerified);result.putBoolean("external_audio_route_verified",externalMuteRouteVerified);pass=true;finish(Activity.RESULT_OK,result);return;}
            if("bluetoothwide".equals(launchArguments==null?null:launchArguments.getString("mode"))){JSONObject width=checkBluetoothWidth(automation);result.putString("profile_key",width.getString("profile_key"));result.putString("wide_before",width.getString("wide_before"));result.putString("wide_after",width.getString("wide_after"));result.putBoolean("bluetooth_was_wide",width.getBoolean("bluetooth_was_wide"));result.putBoolean("bluetooth_visible",width.getBoolean("bluetooth_visible"));result.putInt("bluetooth_span",width.getInt("bluetooth_span"));result.putBoolean("other_wide_preserved",width.getBoolean("other_wide_preserved"));result.putInt("action_columns",width.getInt("action_columns"));result.putString("checks","Read the active profile's wide-control flags; removed only Bluetooth if it was marked wide; verified Bluetooth spans one column and all other profile layout state is unchanged");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("bridge".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                int localPort=OnDeviceBridge.localTlsPort();require(localPort>0,"Android did not expose an active local Wireless debugging port");
                OnDeviceAdb localAdb=OnDeviceAdb.get(getTargetContext());localAdb.setTimeout(5,java.util.concurrent.TimeUnit.SECONDS);
                try{require(localAdb.connect("127.0.0.1",localPort),"Saved DuoDash identity could not authenticate to Android's local Wireless debugging port");}
                finally{try{localAdb.disconnect();}catch(Exception ignored){}}
                JSONObject status=new BridgeClient(getTargetContext()).request("status",null);
                require(status.has("displays")&&status.getJSONArray("displays").length()>0,"App-owned bridge returned no display status");
                int originalMain=status.getInt("main");require(originalMain>=5&&originalMain<=100,"Bridge reported an invalid main brightness");
                int probe=originalMain<100?originalMain+1:originalMain-1;
                try{
                    JSONObject changed=new BridgeClient(getTargetContext()).request("main",new JSONObject().put("percent",probe));
                    require(changed.getInt("main")==probe,"Bridge did not apply a main-brightness update");
                    require(new BridgeClient(getTargetContext()).request("status",null).getInt("main")==probe,"Bridge status did not reflect the brightness update");
                }finally{new BridgeClient(getTargetContext()).request("main",new JSONObject().put("percent",originalMain));}
                require(new BridgeClient(getTargetContext()).request("status",null).getInt("main")==originalMain,"Original main brightness was not restored");
                String pid=shellOutput(automation,"pidof nova_deck").trim();
                require(!pid.isEmpty(),"App-owned bridge socket responded without its helper process");
                result.putString("checks","Saved ADB identity authenticated to Android's local TLS port; private bridge returned display status; main brightness changed and was restored");
                result.putString("helper_pid",pid);pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("helperbuild".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                JSONObject status=new BridgeClient(getTargetContext()).request("status",null);
                int build=status.optInt("bridgeBuild",-1);String strategy=status.optString("swapStrategy","");
                require(BridgeClient.isCurrentHelper(build,strategy),"Connected helper is stale: build="+build+" strategy="+strategy);
                String pid=shellOutput(automation,"pidof nova_deck").trim();require(!pid.isEmpty(),"Helper status replied without the DuoDash process");
                result.putInt("bridge_build",build);result.putString("swap_strategy",strategy);result.putString("helper_pid",pid);
                try(FileOutputStream proof=getTargetContext().openFileOutput("bridge-build-check.json",Context.MODE_PRIVATE)){
                    proof.write(new JSONObject().put("bridge_build",build).put("swap_strategy",strategy).put("helper_pid",pid).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                result.putString("checks","Authenticated system-control socket reports the installed coordinated swap helper build and strategy");pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("bootrecovery".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                require(OnDeviceBridge.isPaired(getTargetContext()),"Saved DuoDash pairing state is missing");
                new BridgeBootReceiver().onReceive(getTargetContext(),new Intent(Intent.ACTION_BOOT_COMPLETED));
                long deadline=SystemClock.elapsedRealtime()+90_000L;boolean connected=false;
                while(SystemClock.elapsedRealtime()<deadline){try{JSONObject status=new BridgeClient(getTargetContext()).request("status",null);connected=status.has("displays");if(connected)break;}catch(Exception ignored){}Thread.sleep(250);}
                require(connected,"Boot receiver did not reconnect the saved identity to the helper");
                String pid=shellOutput(automation,"pidof nova_deck").trim();require(!pid.isEmpty(),"Boot recovery did not start a fresh helper process");
                result.putString("helper_pid",pid);result.putString("checks","Simulated BOOT_COMPLETED reused the existing paired key and reconnected system controls without reprompting for pairing");pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("securewrite".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                require(OnDeviceBridge.isPaired(getTargetContext()),"Saved DuoDash pairing state is missing");
                BridgeActivationService.connect(getTargetContext());long deadline=SystemClock.elapsedRealtime()+45_000L;boolean ready=false;
                while(SystemClock.elapsedRealtime()<deadline){if(getTargetContext().checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)==android.content.pm.PackageManager.PERMISSION_GRANTED&&getTargetContext().getSharedPreferences(OnDeviceBridge.PREFS,0).getBoolean("secure_settings_write_verified",false)){try{ready=new BridgeClient(getTargetContext()).request("status",null).has("displays");}catch(Exception ignored){}if(ready)break;}Thread.sleep(200);}
                require(ready,"App-owned paired ADB service did not grant and verify WRITE_SECURE_SETTINGS from the app UID");
                require(android.provider.Settings.Global.getInt(getTargetContext().getContentResolver(),"adb_wifi_enabled",0)==1,"Wireless debugging setting was not left enabled");
                result.putString("checks","Paired ADB service granted WRITE_SECURE_SETTINGS to DuoDash and verified an app-UID write of adb_wifi_enabled=1 without disabling Wireless debugging");pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("headertelemetry".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                checkHeaderTelemetry();result.putString("checks","Telemetry stayed pinned with no quick actions; optional Hz/Wi-Fi/clock settings persisted and restored; clock followed Android time format; refresh-rate action was added; Settings targets remained accessible; main-display fullscreen bounds and immersive system-UI flags were correct");pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("logicalcanvas".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                checkLogicalCanvas();result.putString("checks","Per-profile Main/External logical-canvas keys stayed independent; only the selected display changed; stale identity and Revert/15-second expiry left saved choices unchanged; Keep persisted by stable identity and restored the original Main logical size");pass=true;finish(Activity.RESULT_OK,result);return;
            }
            if("batteryui".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkBatteryHeaderUi();result.putString("checks","Battery percent/charging state in icon, default-on time estimate toggle, top-right Settings gear, modal close X, and retained dashboard passed");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("dashboard".equals(launchArguments==null?null:launchArguments.getString("mode"))||"restore".equals(launchArguments==null?null:launchArguments.getString("mode"))){boolean verifyDrag="dashboard".equals(launchArguments.getString("mode"));checkFocusedDashboardUi(automation,verifyDrag);result.putString("checks",verifyDrag?"Header, battery icon, per-display FPS persistence, drag order, remove/Add persistence, tap-to-exit, and close-only Settings passed":"Header, battery icon, per-display FPS persistence, action/metric/fader remove and Add restoration, tap-to-exit, and close-only Settings passed");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("pairnotify".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkPairingNotificationOnly(automation);result.putString("checks","Inline pairing notification posted; RemoteInput action is mutable and Android Settings remained foreground");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("pair".equals(launchArguments==null?null:launchArguments.getString("mode"))){checkAppOwnedPairing(automation);result.putString("checks","DuoDash own-key wireless ADB pairing and helper start passed");pass=true;finish(Activity.RESULT_OK,result);return;}
            if("snapshotui".equals(launchArguments==null?null:launchArguments.getString("mode"))){
                getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
                waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;} ,"Overlay service missing");
                waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0;},"Dashboard window did not attach with measured bounds");
                final String[] firstFrameGeometry={""};ui(()->{try{firstFrameGeometry[0]=verifyMainPanelBounds();}catch(Exception e){throw new RuntimeException(e);}});
                waitForStableProfile();
                final String[] geometry={""};ui(()->{try{geometry[0]=verifyMainPanelBounds();}catch(Exception e){throw new RuntimeException(e);}});
                snapshot("dashboard-0.9.3-current.png");ui(()->service.showSetup());waitFor(()->{try{return activeDialog()!=null;}catch(Exception e){return false;}},"Settings modal did not open");popupSnapshot("dashboard-0.9.3-settings.png");
                AlertDialog settings=activeDialog();if(settings!=null)ui(settings::dismiss);waitFor(()->{try{return activeDialog()==null;}catch(Exception e){return false;}},"Settings did not close after snapshot");
                View fps=described(panel(),"FPS, "+(controller.state.fpsScreen.equals("main")?"Main":"External")+" screen:");require(fps!=null,"FPS metric missing from dashboard");ui(fps::performLongClick);waitFor(()->{try{return dashboardEditing();}catch(Exception e){return false;}},"Edit mode did not start for snapshot");snapshot("dashboard-0.9.3-edit.png");
                View done=described(panel(),"Done editing");if(done!=null)ui(done::performClick);
                ui(()->{try{Context ctx=(Context)field(service,"uiContext");android.util.DisplayMetrics resource=ctx.getResources().getDisplayMetrics(),real=new android.util.DisplayMetrics();android.view.Display bound=ctx.getDisplay();bound.getRealMetrics(real);DashboardView live=panel();result.putString("display_metrics","ctxDisplay="+(bound==null?"null":bound.getDisplayId())+", resources="+resource.widthPixels+"x"+resource.heightPixels+", real="+real.widthPixels+"x"+real.heightPixels+", panelDisplay="+(live==null||live.getDisplay()==null?"null":live.getDisplay().getDisplayId())+", panel="+(live==null?"null":live.getWidth()+"x"+live.getHeight()));}catch(Exception e){result.putString("display_metrics","diagnostic error: "+e);}});
                result.putString("checks","First-frame and settled main-display MATCH_PARENT bounds, dashboard, Settings modal, and edit-mode screenshots verified");result.putString("first_frame_geometry",firstFrameGeometry[0]);result.putString("settled_panel_geometry",geometry[0]);pass=true;finish(Activity.RESULT_OK,result);return;
            }
            String requestedMode=launchArguments==null?null:launchArguments.getString("mode");
            if(requestedMode!=null)require(Arrays.asList("componentrender","sliders","fadergesture","fadermute","bluetoothwide","bridge","helperbuild","bootrecovery","securewrite","headertelemetry","logicalcanvas","batteryui","dashboard","restore","pairnotify","pair","snapshotui","setupgate","pairingpanel","controlshortcuts","overlapshortcuts").contains(requestedMode),"Unknown DeviceChecks mode; refusing to fall through to the full native suite: "+requestedMode);
            getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
            waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;},"Service missing");
            waitFor(()->controller.state.bridgeReady,"System helper unavailable");
            verifyAdbIdentityPersistence();
            ui(()->{originalMain=controller.state.main;originalUpper=controller.state.upper;originalOrder=controller.state.controlOrder;originalHidden=controller.state.hiddenControls;originalWide=controller.state.wideControls;originalLayout=controller.layoutKey();originalActionColumns=controller.state.actionColumns;originalHorizontal=controller.state.horizontalSliders;originalCompact=controller.state.compactControls;});
            checkSettingsAndDashboardEditing(automation);
            rebindEnabledAccessibilityService(automation);
            checkDeveloperOptionsHandoff(automation);
            originalShortcuts=getTargetContext().getSharedPreferences("ui",0).getString("shortcuts","");
            originalsCaptured=true;
            ui(()->{controller.updateLayout(controller.layoutKey(),ControlLogic.DEFAULT_ORDER,ControlCatalog.defaultHidden());controller.style(controller.layoutKey(),true,true);});
            waitFor(()->{DashboardView view=panel();return view!=null&&view.isAttachedToWindow()&&view.getWidth()>0&&view.getHeight()>0;},"Dashboard did not reattach with measured bounds after the accessibility window change");
            snapshot("dashboard-compact-horizontal.png");
            DashboardView retained=panel();
            ui(()->service.performControl("fan"));Thread.sleep(250);
            ui(()->require(panel()==retained&&retained.isAttachedToWindow(),"Native popup detached fullscreen backdrop"));popupSnapshot("dashboard-popup-fan.png");
            ui(()->{try{((AlertDialog)field(service,"activeDialog")).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
            ui(()->require(panel()==retained,"Popup dismissal rebuilt dashboard"));
            ui(()->service.performControl("black_main"));Thread.sleep(200);
            ui(()->{try{View blank=(View)field(service,"mainBlank");require(blank!=null&&blank.isAttachedToWindow(),"Main blackout missing");Bitmap image=Bitmap.createBitmap(blank.getWidth(),blank.getHeight(),Bitmap.Config.ARGB_8888);blank.draw(new Canvas(image));require(image.getPixel(0,0)==Color.BLACK&&image.getPixel(image.getWidth()-1,image.getHeight()-1)==Color.BLACK,"Main blackout is not opaque black");image.recycle();long now=SystemClock.uptimeMillis();MotionEvent tap=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,100,100,0);blank.dispatchTouchEvent(tap);tap.recycle();require(field(service,"mainBlank")==null&&!controller.state.mainBlack,"Main blackout did not restore on touch");}catch(Exception e){throw new RuntimeException(e);}});
            int selectedUpper=controller.state.upper;ui(()->controller.control("black_upper"));Thread.sleep(500);
            JSONObject offStatus=new BridgeClient(getTargetContext()).request("status",null);JSONArray offScreens=offStatus.getJSONArray("displays");boolean upperBlack=false;for(int i=0;i<offScreens.length();i++)if(offScreens.getJSONObject(i).getInt("id")==controller.externalDisplay())upperBlack=offScreens.getJSONObject(i).getInt("dimPercent")==0;
            require(upperBlack&&controller.state.upper==selectedUpper,"Upper blackout did not preserve selected brightness");ui(()->controller.control("black_upper"));Thread.sleep(300);
            ui(()->{try{require(((ViewGroup)field(panel(),"actionGrid")).getChildCount()==9,"Default dashboard must contain nine buttons");}catch(Exception error){throw new RuntimeException(error);}});
            int originalHz=controller.state.controls.get("refresh_mode").value;
            ui(()->{View hz=described(panel(),"Display refresh rate:");require(hz!=null,"Refresh readout missing");hz.performClick();});
            waitFor(()->controller.state.controls.get("refresh_mode").value!=originalHz,"Tapping Hz did not change the selected refresh rate");
            ui(()->described(panel(),"Display refresh rate:").performClick());
            waitFor(()->controller.state.controls.get("refresh_mode").value==originalHz,"Tapping Hz again did not restore selected refresh rate");
            ui(()->controller.setBoth(60));Thread.sleep(500);
            require(controller.state.main==60&&controller.state.upper==60,"Both did not set both values");
            require(new BridgeClient(getTargetContext()).request("status",null).getInt("main")==60,"Both did not change real main brightness");
            ui(()->controller.style(controller.layoutKey(),false,true));Thread.sleep(250);snapshot("dashboard-compact-vertical.png");
            ui(()->controller.style(controller.layoutKey(),true,true));
            ui(()->controller.updateLayout(controller.layoutKey(),ControlLogic.DEFAULT_ORDER,ControlLogic.DEFAULT_ORDER));Thread.sleep(250);
            ui(()->{View settings=described(panel(),"Settings");require(settings!=null,"Settings unavailable with all controls hidden");Rect bounds=new Rect();require(settings.getGlobalVisibleRect(bounds),"Settings is outside visible window");ArrayList<View> removed=new ArrayList<>();panel().findViewsWithText(removed,"Add controls",View.FIND_VIEWS_WITH_TEXT);require(removed.isEmpty(),"Editor is still on dashboard");});
            ui(()->controller.updateLayout(controller.layoutKey(),ControlLogic.DEFAULT_ORDER,ControlCatalog.defaultHidden()));
            // Instrumentation force-stops the target package, so explicitly rebind its service.
            String component="dev.nova.deck/dev.nova.deck.DeckAccessibilityService";
            String enabled=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),"enabled_accessibility_services");
            ArrayList<String> others=new ArrayList<>();if(enabled!=null)for(String item:enabled.split(":"))if(!item.equals(component)&&!item.equals("dev.nova.deck/.DeckAccessibilityService"))others.add(item);
            shell(automation,"settings put secure enabled_accessibility_services "+String.join(":",others));Thread.sleep(200);others.add(component);
            shell(automation,"settings put secure enabled_accessibility_services "+String.join(":",others));
            waitFor(()->DeckAccessibilityService.connected!=null,"Shortcut service unavailable");
            if(panel()==null)getTargetContext().startForegroundService(new Intent(getTargetContext(),OverlayService.class).setAction(OverlayService.SHOW));
            waitFor(()->panel()!=null,"Panel missing after key-listener rebind");
            ui(()->{getTargetContext().getSharedPreferences("ui",0).edit().putString("shortcuts","139").commit();DeckAccessibilityService.connected.reloadShortcuts();});
            ui(()->{require(DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,139)),"Shortcut completion was not consumed");DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,139));});
            waitFor(()->panel()==null,"Shortcut did not hide panel");
            Thread.sleep(300);
            ui(()->{DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,139));DeckAccessibilityService.connected.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,139));});
            waitFor(()->panel()!=null,"Same shortcut did not show panel");
            result.putString("checks","Native dashboard/profile editing, drag, replacement, popup, brightness, shortcut return after Developer options handoff, and private ADB identity persistence checks passed");pass=true;
        }catch(Throwable error){result.putString("failure",error.toString());}
        finally{
            if(controller!=null&&originalsCaptured)ui(()->{if(controller.state.mainBlack)service.performControl("black_main");controller.setMain(originalMain);controller.setUpper(originalUpper);controller.updateLayout(originalLayout,originalOrder,originalHidden);controller.wide(originalLayout,originalWide);controller.actionColumns(originalLayout,originalActionColumns);controller.style(originalLayout,originalHorizontal,originalCompact);});
            if(originalsCaptured)ui(()->{getTargetContext().getSharedPreferences("ui",0).edit().putString("shortcuts",originalShortcuts).commit();if(DeckAccessibilityService.connected!=null)DeckAccessibilityService.connected.reloadShortcuts();});
            if(originalsCaptured)try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            result.putBoolean("passed",pass);finish(pass?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }
    }

    private JSONObject placementSummary(JSONArray tasks)throws Exception{
        JSONObject result=new JSONObject();
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);String pkg=task.optString("package","");
            if(pkg.equals("com.retroarch.aarch64")||pkg.equals("org.smarttube.stable")||pkg.equals("org.yuzu.yuzu_emu"))
                result.put(task.optString("id"),new JSONObject().put("display",task.optInt("display")).put("package",pkg).put("visible",task.optBoolean("visible")));
        }
        return result;
    }

    private Bundle checkHardwareSliders(UiAutomation automation)throws Exception{
        rebindEnabledAccessibilityService(automation);
        Context target=getTargetContext();target.startForegroundService(new Intent(target,OverlayService.class).setAction(OverlayService.SHOW));
        waitFor(()->{ui(()->{service=OverlayService.current;if(service!=null)try{controller=(DeckController)field(service,"controller");}catch(Exception e){throw new RuntimeException(e);}});return controller!=null;} ,"Overlay service missing");
        waitFor(()->controller.state.bridgeReady,"System helper unavailable");waitForStableProfile();
        String profile=controller.layoutKey();int savedProfileMain=controller.state.main,savedProfileGain=controller.state.mainAudio;
        JSONObject before=new BridgeClient(target).request("status",null);int originalDisplay=before.getInt("main");JSONObject beforeAudio=before.optJSONObject("audio");String mainPackage="";JSONArray taskInfo=before.optJSONArray("tasks");if(taskInfo!=null){List<ControlLogic.Task> tasks=new ArrayList<>();for(int i=0;i<taskInfo.length();i++){JSONObject item=taskInfo.getJSONObject(i);tasks.add(new ControlLogic.Task(item.getInt("id"),item.getInt("display"),item.optString("package"),item.optBoolean("visible"),item.optBoolean("standard")));}ControlLogic.Task current=ControlLogic.foreground(tasks,0);if(current!=null)mainPackage=current.pkg;}
        String mainPid=mainPackage.matches("[A-Za-z0-9._]+")?shellOutput(automation,"pidof "+mainPackage).trim():"";float audioDbBefore=mainPid.isEmpty()?Float.NaN:activeAudioTrackGainDb(shellOutput(automation,"dumpsys media.audio_flinger"),mainPid),audioDbAtHalf=Float.NaN,audioDbRestored=Float.NaN;
        int probe=savedProfileMain<=85?Math.min(100,savedProfileMain+10):Math.max(5,savedProfileMain-10);boolean brightnessVerified=false,tempApplied=false,backlightVerified=false;int backlightPreview=-1,backlightCommitted=-1;String audioDiagnostic="unavailable";
        try{
            String rawBacklight=shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim();int backlightBefore=Integer.parseInt(rawBacklight);
            @SuppressWarnings("unchecked") Map<String,View> faders=(Map<String,View>)field(panel(),"faders");View main=faders.get("main");require(main!=null,"Main brightness Fader is missing");
            java.lang.reflect.Method change=main.getClass().getDeclaredMethod("changeValue",int.class,boolean.class);change.setAccessible(true);ui(()->{try{change.invoke(main,probe,true);}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(500);backlightPreview=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());tempApplied=backlightPreview!=backlightBefore;
            require(tempApplied,"Main brightness preview did not change the physical backlight node");
            ui(()->{try{change.invoke(main,probe,false);}catch(Exception e){throw new RuntimeException(e);}});
            waitFor(()->{try{return new BridgeClient(target).request("status",null).getInt("main")==probe;}catch(Exception e){return false;}},"Dashboard main-brightness slider did not change display 0 through its normal Fader callback");
            backlightCommitted=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());brightnessVerified=true;backlightVerified=backlightCommitted==backlightPreview;
            require(backlightVerified,"Persisting brightness on release changed the physical backlight away from the preview value");
            if(beforeAudio!=null){boolean supported=beforeAudio.optBoolean("supported"),available=beforeAudio.optBoolean("mainAvailable");String reason=beforeAudio.optString("mainReason");audioDiagnostic="supported="+supported+", mainAvailable="+available+", reason="+reason;
                if(supported&&available&&"App audio".equals(reason)){
                    int gainProbe=savedProfileGain>1?Math.max(1,savedProfileGain/2):50;ui(()->controller.setMainAudio(gainProbe));
                    waitFor(()->{try{JSONObject a=new BridgeClient(target).request("status",null).optJSONObject("audio");return a!=null&&a.optInt("mainGain",-1)==gainProbe;}catch(Exception e){return false;}},"Main app-audio slider callback did not reach the bridge");
                    Thread.sleep(350);audioDbAtHalf=activeAudioTrackGainDb(shellOutput(automation,"dumpsys media.audio_flinger"),mainPid);
                    require(Float.isFinite(audioDbBefore)&&Float.isFinite(audioDbAtHalf)&&audioDbAtHalf<=audioDbBefore-4f,"Main app-audio gain did not lower the active AudioFlinger track by at least 4 dB (package="+mainPackage+", pid="+mainPid+", saved="+savedProfileGain+", probe="+gainProbe+", before="+audioDbBefore+", probeDb="+audioDbAtHalf+")");
                    ui(()->controller.setMainAudio(savedProfileGain));waitFor(()->{try{JSONObject a=new BridgeClient(target).request("status",null).optJSONObject("audio");return a!=null&&a.optInt("mainGain",-1)==savedProfileGain;}catch(Exception e){return false;}} ,"Original main app-audio gain was not restored");
                    Thread.sleep(300);audioDbRestored=activeAudioTrackGainDb(shellOutput(automation,"dumpsys media.audio_flinger"),mainPid);require(Float.isFinite(audioDbRestored)&&Math.abs(audioDbRestored-audioDbBefore)<1.5f,"Original active-track AudioFlinger gain was not restored");
                    JSONObject afterAudio=new BridgeClient(target).request("status",null).optJSONObject("audio");audioDiagnostic="supported="+afterAudio.optBoolean("supported")+", mainAvailable="+afterAudio.optBoolean("mainAvailable")+", reason="+afterAudio.optString("mainReason")+", activeTrackDb="+audioDbBefore+"→"+audioDbAtHalf+"→"+audioDbRestored;
                }
            }
            ui(()->{controller.setMain(savedProfileMain);controller.setMainAudio(savedProfileGain);});
            waitFor(()->{try{JSONObject s=new BridgeClient(target).request("status",null);JSONObject a=s.optJSONObject("audio");return s.getInt("main")==savedProfileMain&&(a==null||a.optInt("mainGain",savedProfileGain)==savedProfileGain);}catch(Exception e){return false;}} ,"Original main brightness/audio profile was not restored");
            int backlightRestored=Integer.parseInt(shellOutput(automation,"cat /sys/class/backlight/panel0-backlight/brightness").trim());require(backlightRestored==backlightBefore,"Original main backlight level was not restored");
            Bundle output=new Bundle();output.putString("profile",profile);output.putInt("display_before",originalDisplay);output.putInt("profile_before",savedProfileMain);output.putInt("display_probe",probe);output.putInt("backlight_before",backlightBefore);output.putInt("backlight_preview",backlightPreview);output.putInt("backlight_committed",backlightCommitted);output.putInt("backlight_restored",backlightRestored);output.putBoolean("temporary_hw_applied",tempApplied);output.putBoolean("fader_to_display0",brightnessVerified);output.putBoolean("fader_to_backlight",backlightVerified);output.putFloat("audio_db_before",audioDbBefore);output.putFloat("audio_db_half",audioDbAtHalf);output.putFloat("audio_db_restored",audioDbRestored);output.putString("audio",audioDiagnostic);return output;
        }finally{
            try{new BridgeClient(target).request("main_preview_clear",null);}catch(Exception ignored){}
            ui(()->{controller.setMain(savedProfileMain);controller.setMainAudio(savedProfileGain);});
            waitFor(()->{try{JSONObject s=new BridgeClient(target).request("status",null);JSONObject a=s.optJSONObject("audio");return s.getInt("main")==savedProfileMain&&(a==null||a.optInt("mainGain",savedProfileGain)==savedProfileGain);}catch(Exception e){return false;}},"Original main brightness/audio profile was not restored");
        }
    }
    private float activeAudioTrackGainDb(String dump,String pid){
        if(dump==null||pid==null||pid.isEmpty())return Float.NaN;
        for(String line:dump.split("\\n")){String[] cells=line.trim().split("\\s+");if(cells.length>16&&cells[2].equals("yes")&&cells[3].equals(pid))try{return (Float.parseFloat(cells[15])+Float.parseFloat(cells[16]))/2f;}catch(NumberFormatException ignored){}}
        return Float.NaN;
    }
}
