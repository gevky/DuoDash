package dev.nova.deck;
import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardInputPolicyTest {
    @Test public void visibleHandheldDashboardReceivesNavigation(){
        assertTrue(DashboardInputPolicy.route(true,false,false,false,false,false));
    }
    @Test public void hiddenDashboardAndOtherInputOwnersKeepTheirKeys(){
        assertFalse(DashboardInputPolicy.route(false,false,false,false,false,false));
        assertFalse(DashboardInputPolicy.route(true,true,false,false,false,false));
        assertFalse(DashboardInputPolicy.route(true,false,true,false,false,false));
        assertFalse(DashboardInputPolicy.route(true,false,false,true,false,false));
        assertFalse(DashboardInputPolicy.route(true,false,false,false,true,false));
        assertFalse(DashboardInputPolicy.route(true,false,false,false,false,true));
    }
    @Test public void navigationDoesNotClaimPowerVolumeOrShortcutButtons(){
        for(int key:new int[]{KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B})
            assertTrue(DashboardInputPolicy.navigationKey(key));
        for(int key:new int[]{KeyEvent.KEYCODE_POWER,KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_BUTTON_THUMBR,KeyEvent.KEYCODE_BUTTON_START})
            assertFalse(DashboardInputPolicy.navigationKey(key));
    }
}
