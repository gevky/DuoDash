package dev.nova.deck;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ShortcutMatcherTest {
    @Test public void singleButtonTogglesOnceAndConsumesItsMatchingRelease(){
        ShortcutMatcher m=new ShortcutMatcher("109");
        assertTrue(m.event(109,true,false).toggle);assertTrue(m.event(109,true,true).consume);
        assertFalse(m.event(109,true,true).toggle);assertTrue(m.event(109,false,false).consume);
        assertFalse(m.event(96,true,false).consume);
    }
    @Test public void chordLeavesPrefixDownAndReleaseBalancedForTheGame(){
        ShortcutMatcher m=new ShortcutMatcher("108,109");
        assertFalse(m.event(108,true,false).consume);
        ShortcutMatcher.Result trigger=m.event(109,true,false);assertTrue(trigger.toggle);assertTrue(trigger.consume);
        assertFalse(m.event(108,false,false).consume);assertTrue(m.event(109,false,false).consume);
        assertFalse(m.event(109,true,false).toggle);assertTrue(m.event(108,true,false).toggle);
    }
    @Test public void supportsSeveralBindingsAndRejectsInvalidOrOverlappingChords(){
        assertEquals("108,109;110",ShortcutMatcher.normalize("109,108;110;0;3;26;108,109"));
        assertFalse(ShortcutMatcher.canAdd("108,109",new int[]{108}));
        assertTrue(ShortcutMatcher.canAdd("108,109",new int[]{110}));
        assertEquals("",ShortcutMatcher.normalize("0;3;26;1,2,4,5"));
    }
    @Test public void configuredToggleRequiresAtLeastOneValidNonreservedBinding(){
        assertFalse(ShortcutMatcher.hasConfiguredToggle(null));
        assertFalse(ShortcutMatcher.hasConfiguredToggle(""));
        assertFalse(ShortcutMatcher.hasConfiguredToggle("0;3;26;401;1,2,4,5;garbage"));
        assertTrue(ShortcutMatcher.hasConfiguredToggle("109"));
        assertTrue(ShortcutMatcher.hasConfiguredToggle("invalid;108,109"));
    }
}
