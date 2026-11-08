package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShortcutBindingsTest {
    @Test public void storesOneBindingPerControlAndIgnoresMalformedOrUnknownActions(){
        ShortcutBindings bindings=new ShortcutBindings("109","wifi=108;wifi=109;made_up=110;bluetooth=0");
        assertEquals("108",bindings.shortcuts("wifi"));
        assertEquals("wifi=108",ShortcutBindings.normalizeControls("wifi=108;wifi=109;made_up=110;bluetooth=0"));
    }

    @Test public void onlyExactCrossActionChordsConflict(){
        ShortcutBindings bindings=new ShortcutBindings("108,109","wifi=110");
        assertEquals("",bindings.conflict("wifi",new int[]{108},null));
        assertEquals(ShortcutBindings.TOGGLE,bindings.conflict("wifi",new int[]{108,109},null));
        assertEquals("",bindings.conflict("wifi",new int[]{111},null));
        assertEquals("",bindings.conflict("bluetooth",new int[]{110,112},null));
        assertEquals("wifi",bindings.conflict("bluetooth",new int[]{110},null));
    }

    @Test public void dashboardConflictCheckExcludesOnlyTheChordBeingReplaced(){
        ShortcutBindings bindings=new ShortcutBindings("108;109","wifi=110");
        assertEquals("",bindings.conflict(ShortcutBindings.TOGGLE,new int[]{108},"108"));
        assertEquals("dashboard",bindings.conflict(ShortcutBindings.TOGGLE,new int[]{109},"108"));
        assertEquals("wifi",bindings.conflict(ShortcutBindings.TOGGLE,new int[]{110},"108"));
    }

    @Test public void waitsForReleaseOnlyWhenChordIsProperSubset(){
        ShortcutBindings bindings=new ShortcutBindings("96","wifi=96,97;bluetooth=96,97,99");
        assertTrue(bindings.waitsForRelease("96"));
        assertTrue(bindings.waitsForRelease("96,97"));
        assertFalse(bindings.waitsForRelease("96,97,99"));
        assertFalse(bindings.waitsForRelease("98"));
        assertFalse(bindings.waitsForRelease("96,96,98"));
    }

    @Test public void putReplaceAndRemoveReturnNormalizedControlSerialization(){
        ShortcutBindings bindings=new ShortcutBindings("109","wifi=108;bluetooth=111");
        String replaced=bindings.put("wifi",new int[]{112},"108");
        assertEquals("wifi=112;bluetooth=111",replaced);
        assertEquals("bluetooth=111",bindings.remove("wifi","108"));
        assertEquals("wifi=108;bluetooth=111",bindings.remove("wifi","109"));
    }

    @Test public void rejectsInvalidAndConflictingBindings(){
        ShortcutBindings bindings=new ShortcutBindings("109","");
        try{bindings.put("wifi",new int[]{109},null);fail("dashboard conflict should be rejected");}
        catch(IllegalArgumentException expected){}
        try{bindings.put("not-a-control",new int[]{110},null);fail("unknown actions should be rejected");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void controlBindingAcceptsThreeButtonChordAndSecondPutReplacesIt(){
        ShortcutBindings bindings=new ShortcutBindings("","wifi=101,102,103");
        assertEquals("101,102,103",bindings.shortcuts("wifi"));
        assertEquals("wifi=109",bindings.put("wifi",new int[]{109},"101,102,103"));
        assertEquals("wifi=108,109,110",new ShortcutBindings("","").put("wifi",new int[]{110,108,109},null));
    }

    @Test public void acceptsExplicitShortcutActionsAndAppIdsButRejectsMetricsAndUnknowns(){
        ShortcutBindings empty=new ShortcutBindings("","");
        assertEquals("main_brightness_up=108,109",empty.put("main_brightness_up",new int[]{108,109},null));
        assertEquals("app_0123456789abcdef01234567=110",empty.put("app_0123456789abcdef01234567",new int[]{110},null));
        assertEquals("",empty.conflict("cpu",new int[]{111},null));
        try{empty.put("cpu",new int[]{111},null);fail("read-only metrics are not shortcut actions");}
        catch(IllegalArgumentException expected){}
        try{empty.put("arbitrary_shell",new int[]{112},null);fail("unknown actions are not accepted");}
        catch(IllegalArgumentException expected){}
    }

    @Test public void deletingPinnedAppCanRemoveItsShortcutWithoutChangingOtherBindings(){
        String app="app_0123456789abcdef01234567";
        ShortcutBindings bindings=new ShortcutBindings("109","wifi=108;"+app+"=110");
        assertEquals("wifi=108",bindings.remove(app,bindings.shortcuts(app)));
        assertEquals("wifi=108;"+app+"=110",bindings.remove(app,"111"));
    }

    @Test public void controlSerializationDropsMalformedPipeAndKeepsDashboardMultiBindings(){
        assertEquals("",ShortcutBindings.normalizeControls("wifi=108|109;bluetooth=bad|111"));
        assertEquals("wifi=108",ShortcutBindings.normalizeControls("wifi=108;wifi=109"));
        assertEquals("108;109",new ShortcutBindings("108;109","").shortcuts(ShortcutBindings.TOGGLE));
    }
}
