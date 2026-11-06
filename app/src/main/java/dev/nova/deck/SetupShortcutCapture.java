package dev.nova.deck;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit foreground-wizard recording session for one required toggle shortcut. */
public final class SetupShortcutCapture {
    public interface Listener {
        void onReady();
        void onKey(int keyCode,boolean down);
        void onError(String message);
    }

    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static SetupShortcutCapture active;
    private final MainActivity activity;
    private final Listener listener;
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final DeckAccessibilityService.Recorder recorder=this::onRecordedEvent;

    public SetupShortcutCapture(MainActivity activity,Listener listener){this.activity=activity;this.listener=listener;}

    /** Starts only while this setup Activity is resumed and focused, with its real accessibility service bound. */
    public boolean start(){
        if(activity==null||listener==null||!activity.isSetupRecordingForeground())return false;
        synchronized(SetupShortcutCapture.class){
            if(active!=null&&!active.stopped.get())return false;
            if(!DeckAccessibilityService.beginWizardRecording(activity,recorder))return false;
            active=this;
        }
        new Thread(()->{
            String error=null;
            try{
                JSONObject status=new BridgeClient(activity.getApplicationContext()).request("status",null);
                if(!BridgeClient.isCurrentHelper(status))throw new IllegalStateException("System controls need reconnecting before button recording.");
                new BridgeClient(activity.getApplicationContext()).request("recording",new JSONObject().put("enabled",true));
            }catch(Exception failure){error=failure.getMessage()==null?"Could not start button recording.":failure.getMessage();}
            final String result=error;
            MAIN.post(()->{
                if(stopped.get())return;
                if(result!=null||!activity.isSetupRecordingForeground()){
                    stop();if(result!=null)listener.onError(result);return;
                }
                listener.onReady();
            });
        },"duodash-shortcut-capture").start();
        return true;
    }

    public void stop(){
        if(!stopped.compareAndSet(false,true))return;
        synchronized(SetupShortcutCapture.class){if(active==this)active=null;}
        DeckAccessibilityService.endWizardRecording(activity,recorder);
        new Thread(()->{try{new BridgeClient(activity.getApplicationContext()).request("recording",new JSONObject().put("enabled",false));}catch(Exception ignored){}},"duodash-shortcut-capture-stop").start();
    }

    public static void stopFor(MainActivity activity){
        SetupShortcutCapture current;
        synchronized(SetupShortcutCapture.class){current=active;}
        if(current!=null&&current.activity==activity)current.stop();
    }

    private void onRecordedEvent(KeyEvent event){
        if(stopped.get()||!activity.isSetupRecordingForeground())return;
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_HOME||code==KeyEvent.KEYCODE_POWER||code==KeyEvent.KEYCODE_UNKNOWN)return;
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        MAIN.post(()->{if(!stopped.get()&&activity.isSetupRecordingForeground())listener.onKey(code,down);});
    }
}
