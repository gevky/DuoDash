package dev.nova.deck;

import static org.junit.Assert.*;
import org.junit.Test;

public class UsbDebuggingPolicyTest {
    private static final class Settings implements UsbDebuggingPolicy.Operations {
        int value, writes;
        boolean accepted = true, apply = true;
        public int read() { return value; }
        public boolean enable() { writes++; if (accepted && apply) value = 1; return accepted; }
    }

    @Test public void existingUsbDebuggingNeedsNoWriteOrGrant() throws Exception {
        Settings settings = new Settings(); settings.value = 1;
        UsbDebuggingPolicy.ensureEnabled(false, settings);
        assertEquals(0, settings.writes);
    }

    @Test public void missingGrantNeverChangesDebugging() {
        Settings settings = new Settings();
        assertThrows(IllegalStateException.class, () -> UsbDebuggingPolicy.ensureEnabled(false, settings));
        assertEquals(0, settings.writes);
        assertEquals(0, settings.value);
    }

    @Test public void grantedActivationEnablesAndVerifiesUsb() throws Exception {
        Settings settings = new Settings();
        UsbDebuggingPolicy.ensureEnabled(true, settings);
        assertEquals(1, settings.writes);
        assertEquals(1, settings.value);
    }

    @Test public void rejectedWriteIsNotReportedAsReady() {
        Settings settings = new Settings(); settings.accepted = false;
        assertThrows(IllegalStateException.class, () -> UsbDebuggingPolicy.ensureEnabled(true, settings));
    }

    @Test public void successfulWriteMustActuallyChangeTheSetting() {
        Settings settings = new Settings(); settings.apply = false;
        assertThrows(IllegalStateException.class, () -> UsbDebuggingPolicy.ensureEnabled(true, settings));
    }
}
