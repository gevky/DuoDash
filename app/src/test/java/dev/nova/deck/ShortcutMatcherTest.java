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
        assertTrue(ShortcutMatcher.canAdd("108,109",new int[]{108}));
        assertTrue(ShortcutMatcher.canAdd("108",new int[]{108,109}));
        assertFalse(ShortcutMatcher.canAdd("108,109",new int[]{108,109}));
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

    @Test public void multiActionMatcherReturnsActionAndDashboardWinsMalformedConflict(){
        ShortcutMatcher m=new ShortcutMatcher("109","wifi=109;bluetooth=110");
        ShortcutMatcher.Result dashboard=m.event(109,true,false);
        assertEquals(ShortcutBindings.TOGGLE,dashboard.action);
        assertTrue(dashboard.toggle);
        assertTrue(m.event(109,false,false).consume);
        ShortcutMatcher.Result bluetooth=m.event(110,true,false);
        assertEquals("bluetooth",bluetooth.action);
        assertFalse(bluetooth.toggle);
    }

    @Test public void matcherSupportsMultipleActionsAndRetainsOldConstructor(){
        ShortcutMatcher m=new ShortcutMatcher("","wifi=108;bluetooth=109");
        assertEquals("wifi",m.event(108,true,false).action);
        assertTrue(m.event(108,false,false).consume);
        assertEquals("bluetooth",m.event(109,true,false).action);
        assertEquals(ShortcutBindings.TOGGLE,new ShortcutMatcher("109").event(109,true,false).action);
        assertTrue(new ShortcutMatcher("109").event(109,true,false).toggle);
    }

    @Test public void threeButtonChordTriggersOnlyOnItsFinalButton(){
        ShortcutMatcher matcher=new ShortcutMatcher("","wifi=108,109,110");
        assertNull(matcher.event(108,true,false).action);
        assertNull(matcher.event(109,true,false).action);
        ShortcutMatcher.Result result=matcher.event(110,true,false);
        assertEquals("wifi",result.action);
        assertTrue(result.consume);
    }

    @Test public void resetDuringCaptureClearsPartialAndConsumedKeyState(){
        ShortcutMatcher matcher=new ShortcutMatcher("","wifi=108,109");
        assertNull(matcher.event(108,true,false).action);
        matcher.reset();
        assertNull(matcher.event(109,true,false).action);
        assertFalse(matcher.event(108,false,false).consume);
        matcher.reset();
        assertNull(matcher.event(108,true,false).action);
    }

    @Test public void nestedDashboardAndControlChordsChooseLongestAndSuppressShorterOnRelease(){
        ShortcutMatcher matcher=new ShortcutMatcher("96","wifi=96,97;bluetooth=96,97,99");
        assertNull(matcher.event(96,true,false).action);
        assertNull(matcher.event(97,true,false).action);
        assertEquals("bluetooth",matcher.event(99,true,false).action);
        assertNull(matcher.event(96,false,false).action);
        assertNull(matcher.event(97,false,false).action);
        assertNull(matcher.event(99,false,false).action);
    }

    @Test public void dashboardWinsOnlyAnExactChordTie(){
        ShortcutMatcher exact=new ShortcutMatcher("96,97","wifi=96,97");
        exact.event(96,true,false);
        assertEquals("dashboard",exact.event(97,true,false).action);

        ShortcutMatcher distinct=new ShortcutMatcher("96,97","wifi=96,98");
        distinct.event(97,true,false);
        distinct.event(98,true,false);
        assertEquals("wifi",distinct.event(96,true,false).action);
    }

    @Test public void nestedChordCanBePressedInEitherOrderAndEmitsOnPrefixRelease(){
        ShortcutMatcher cThenZ=new ShortcutMatcher("96","wifi=96,97;bluetooth=96,97,99");
        assertNull(cThenZ.event(96,true,false).action);
        assertNull(cThenZ.event(97,true,false).action);
        assertEquals("wifi",cThenZ.event(97,false,false).action);
        assertNull(cThenZ.event(96,false,false).action);

        ShortcutMatcher zThenC=new ShortcutMatcher("96","wifi=96,97;bluetooth=96,97,99");
        assertNull(zThenC.event(97,true,false).action);
        assertNull(zThenC.event(96,true,false).action);
        assertEquals("wifi",zThenC.event(96,false,false).action);
        assertNull(zThenC.event(97,false,false).action);
    }

    @Test public void deferredPrefixDoesNotLoseIndependentShortcutAndLongerChordCanRepeat(){
        ShortcutMatcher matcher=new ShortcutMatcher("96","wifi=96,97;bluetooth=98");
        assertNull(matcher.event(96,true,false).action);
        assertEquals("bluetooth",matcher.event(98,true,false).action);
        assertTrue(matcher.event(98,false,false).consume);
        assertEquals("dashboard",matcher.event(96,false,false).action);

        matcher=new ShortcutMatcher("96","wifi=96,97;bluetooth=96,97,99");
        matcher.event(96,true,false);
        assertNull(matcher.event(97,true,false).action);
        assertEquals("wifi",matcher.event(97,false,false).action);
        assertNull(matcher.event(97,true,false).action);
        assertEquals("wifi",matcher.event(97,false,false).action);
        assertNull(matcher.event(96,false,false).action);
    }

    @Test public void everyNestedThreeKeyPressAndReleaseOrderFiresOnlyTheLongestBinding(){
        int[][] orders={{96,97,99},{96,99,97},{97,96,99},{97,99,96},{99,96,97},{99,97,96}};
        for(int[] pressOrder:orders)for(int[] releaseOrder:orders){
            ShortcutMatcher matcher=new ShortcutMatcher("96","wifi=96,97;bluetooth=96,97,99");
            Map<Integer,Boolean> consumedDown=new HashMap<>();
            List<String> actions=new ArrayList<>();
            for(int key:pressOrder){
                ShortcutMatcher.Result result=matcher.event(key,true,false);
                consumedDown.put(key,result.consume);
                if(result.action!=null)actions.add(result.action);
            }
            assertEquals(Arrays.asList("bluetooth"),actions);
            for(int key:releaseOrder){
                ShortcutMatcher.Result result=matcher.event(key,false,false);
                assertEquals("key-up balances its matching key-down",consumedDown.get(key),result.consume);
                assertNull("shorter binding must not fire after the longer chord",result.action);
            }
        }
    }
}
