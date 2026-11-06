package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PairingCodeEntryTest {
    @Test public void codeRequiresSixDigitsAndKeepsPortOptional() {
        PairingCodeEntry entry = new PairingCodeEntry();
        for (char digit : "12345".toCharArray()) assertTrue(entry.appendDigit(digit));
        assertFalse(entry.canSubmit());
        assertTrue(entry.appendDigit('6'));
        assertTrue(entry.canSubmit());
        assertEquals("123456", entry.reply());
        assertFalse(entry.appendDigit('7'));
    }

    @Test public void optionalPortUsesTheExistingStrictParserAndClearsFromMemory() {
        PairingCodeEntry entry = new PairingCodeEntry();
        for (char digit : "123456".toCharArray()) entry.appendDigit(digit);
        entry.setEditingPort(true);
        for (char digit : "37007".toCharArray()) entry.appendDigit(digit);
        assertTrue(entry.canSubmit());
        assertEquals("123456 37007", entry.reply());
        assertFalse(entry.appendDigit('1'));
        entry.clearAll();
        assertEquals("", entry.codeText());
        assertEquals("", entry.portText());
        assertFalse(entry.canSubmit());
    }

    @Test public void backspaceAndFieldClearAffectOnlySelectedInput() {
        PairingCodeEntry entry = new PairingCodeEntry();
        for (char digit : "123456".toCharArray()) entry.appendDigit(digit);
        entry.setEditingPort(true);
        for (char digit : "3700".toCharArray()) entry.appendDigit(digit);
        entry.backspace();
        entry.clearCurrent();
        assertEquals("123456", entry.codeText());
        assertEquals("", entry.portText());
    }
}
