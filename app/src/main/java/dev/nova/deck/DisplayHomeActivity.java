package dev.nova.deck;

import android.app.Activity;
import android.os.Bundle;

/** Legacy placeholder kept only to remove tasks created by older DuoDash versions. */
public final class DisplayHomeActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        finishAndRemoveTask();
    }
}
