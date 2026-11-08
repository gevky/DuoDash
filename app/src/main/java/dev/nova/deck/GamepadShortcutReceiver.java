package dev.nova.deck;
import android.content.*;
import android.view.KeyEvent;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shell-only, session-authenticated controller notification. No arbitrary actions are accepted. */
public final class GamepadShortcutReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!"dev.nova.deck.GAMEPAD_EVENT".equals(intent.getAction()))return;
        try{byte[] expected=Files.readAllBytes(new java.io.File(context.getFilesDir(),"bridge-token").toPath());String supplied=intent.getStringExtra("token");if(supplied==null||expected.length!=64||!MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8)))return;}catch(Exception ignored){return;}
        if(intent.getBooleanExtra("toggle",false)){
            OverlayService.dispatchShortcut(context,ShortcutBindings.TOGGLE,"internal-controller");return;
        }
        String shortcutAction=intent.getStringExtra("shortcut_action");
        if(shortcutAction!=null){
            android.content.SharedPreferences prefs=context.getSharedPreferences("ui",Context.MODE_PRIVATE);
            ShortcutBindings configured=new ShortcutBindings(prefs.getString("shortcuts",""),prefs.getString(ShortcutBindings.PREF,""));
            if(configured.shortcuts(shortcutAction).isEmpty())return;
            if(ShortcutBindings.TOGGLE.equals(shortcutAction)){
                OverlayService.dispatchShortcut(context,ShortcutBindings.TOGGLE,"internal-controller");
            }else OverlayService.dispatchShortcut(context,shortcutAction,"internal-controller");
            return;
        }
        DeckAccessibilityService connectedService=DeckAccessibilityService.connected;
        if(connectedService!=null)connectedService.onWizardGamepadEvent(intent.getIntExtra("key",0),intent.getIntExtra("action",0));
        int code=intent.getIntExtra("key",0),action=intent.getIntExtra("action",-1);DeckAccessibilityService service=DeckAccessibilityService.connected;
        if(code>0&&code<=KeyEvent.getMaxKeyCode()&&(action==KeyEvent.ACTION_DOWN||action==KeyEvent.ACTION_UP)&&service!=null&&service.recorder!=null)service.recorder.event(new KeyEvent(action,code));
    }
}
