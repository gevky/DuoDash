package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BridgeBootstrapCommandTest {
    @Test public void quotesApkPathAndKeepsBootstrapCommandFixed(){
        String command=BridgeBootstrapCommand.build("/data/app/a'b/base.apk",10123,"a".repeat(64));
        assertTrue(command.contains("CLASSPATH='/data/app/a'\\''b/base.apk'"));
        assertTrue(command.contains("dev.nova.deck.bridge.BridgeMain 10123 '"+"a".repeat(64)+"'"));
        assertTrue(command.contains("/data/local/tmp/nova-deck-bridge.log"));
        assertFalse(command.contains("sh -c"));
    }
    @Test public void rejectsInputsThatCouldChangeTheTargetOrCommand(){
        assertThrows(IllegalArgumentException.class,()->BridgeBootstrapCommand.build("/sdcard/other.apk",10123,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->BridgeBootstrapCommand.build("/data/app/app.apk",2000,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->BridgeBootstrapCommand.build("/data/app/app.apk",10123,"a; reboot"));
    }
}
