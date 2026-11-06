package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingReplyParserTest {
    @Test public void keepsCodeOnlyReplyOnAutomaticMdnsPath(){
        PairingReplyParser.Reply reply=PairingReplyParser.parse("123456");
        assertTrue(reply.valid);assertEquals(0,reply.port);assertEquals("123456",new String(reply.code));
    }

    @Test public void acceptsOnlyCodeAndOptionalBoundedPort(){
        PairingReplyParser.Reply low=PairingReplyParser.parse(" 123456   1 ");
        PairingReplyParser.Reply high=PairingReplyParser.parse("123456 65535");
        assertTrue(low.valid);assertEquals(1,low.port);assertTrue(high.valid);assertEquals(65535,high.port);
        assertEquals("123456",new String(high.code));
    }

    @Test public void rejectsHostsCommandsExtraFieldsAndInvalidPorts(){
        assertFalse(PairingReplyParser.parse("12345").valid);
        assertFalse(PairingReplyParser.parse("123456 0").valid);
        assertFalse(PairingReplyParser.parse("123456 65536").valid);
        assertFalse(PairingReplyParser.parse("123456 1.2.3.4:1234").valid);
        assertFalse(PairingReplyParser.parse("123456;reboot").valid);
        assertFalse(PairingReplyParser.parse("123456 1000 extra").valid);
        assertFalse(PairingReplyParser.parse(null).valid);
    }
}
