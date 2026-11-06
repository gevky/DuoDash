package dev.nova.deck;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;

/** WMS-managed transparent canvas for compositor mirroring. */
public final class MirrorActivity extends Activity {
    public static final String CLOSE="dev.nova.deck.CLOSE_MIRROR";
    private boolean registered;
    private final BroadcastReceiver close=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){finish();}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(getWindowManager().getDefaultDisplay().getDisplayId()==0){finish();return;}
        // A resumed Activity must supply a focusable window: otherwise controller events
        // targeting this display cause Android's "no focused window" ANR, even with an
        // idle main thread. The helper restores the previously focused game task.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            |View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        View canvas=new View(this);canvas.setBackgroundColor(Color.TRANSPARENT);setContentView(canvas);
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(close,new IntentFilter(CLOSE),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(close,new IntentFilter(CLOSE));
        registered=true;
    }
    @Override public void onBackPressed(){ /* The mirror canvas is not a navigation destination. */ }
    @Override public void onDestroy(){if(registered)unregisterReceiver(close);super.onDestroy();}
}
