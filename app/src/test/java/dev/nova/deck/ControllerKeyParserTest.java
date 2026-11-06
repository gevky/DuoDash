package dev.nova.deck;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ControllerKeyParserTest {
    @Test public void readsPhysicalGamepadButtonsWithoutAnApplicationKeyDispatcher(){
        Map<Integer,Integer> layout=Map.of(314,109,315,108);ShortcutMatcher matcher=new ShortcutMatcher("108,109");
        ControllerKeyParser.Key start=ControllerKeyParser.parse("[ 1234.123456] 0001 013b 00000001",layout);
        assertNotNull(start);assertEquals(108,start.code);assertTrue(start.down);assertFalse(matcher.event(start.code,start.down,start.repeat).toggle);
        ControllerKeyParser.Key select=ControllerKeyParser.parse("[ 1234.223456] 0001 013a 00000001",layout);
        assertTrue(matcher.event(select.code,select.down,select.repeat).toggle);
        assertFalse(matcher.event(108,true,true).toggle);
    }
    @Test public void ignoresMotionSynchronisationAndUnknownKeys(){
        Map<Integer,Integer> layout=Map.of(315,108);
        assertNull(ControllerKeyParser.parse("[ 2.1] 0003 0000 00001fff",layout));
        assertNull(ControllerKeyParser.parse("[ 2.1] 0000 0000 00000000",layout));
        assertNull(ControllerKeyParser.parse("invalid",layout));
        assertNull(ControllerKeyParser.parse("[ 2.1] 0001 ffff 00000001",layout));
        assertFalse(ControllerKeyParser.parse("[ 2.1] 0001 013b 00000000",layout).down);
    }
}
