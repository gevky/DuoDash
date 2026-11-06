package dev.nova.deck;

import android.view.KeyEvent;

/** Routes controller keys only while the handheld dashboard owns the interaction. */
final class DashboardInputPolicy {
    private DashboardInputPolicy(){}
    static boolean route(boolean showing,boolean external,boolean settings,boolean dialog,boolean ime,boolean recording){
        return showing&&!external&&!settings&&!dialog&&!ime&&!recording;
    }
    static boolean navigationKey(int key){
        return key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN
            ||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT
            ||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_BUTTON_A
            ||key==KeyEvent.KEYCODE_BUTTON_B||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_BACK;
    }
}
