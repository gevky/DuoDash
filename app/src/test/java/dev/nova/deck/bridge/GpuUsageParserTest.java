package dev.nova.deck.bridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class GpuUsageParserTest {
    @Test public void parsesQualcommBusyPercentageSysfsShape() {
        GpuUsageParser.Reading reading=GpuUsageParser.parsePercentage(" 37 %\n");
        assertTrue(reading.available());
        assertEquals(37.0,reading.percent(),0.0001);
    }

    @Test public void parsesKgslBusyAndTotalCounterShape() {
        GpuUsageParser.Reading reading=GpuUsageParser.parseBusyTime("      2500       10000\n");
        assertTrue(reading.available());
        assertEquals(25.0,reading.percent(),0.0001);
    }

    @Test public void zeroBusyIsValidButZeroTotalIsNotAUsageReading() {
        assertEquals(0.0,GpuUsageParser.parseBusyTime("0 100\n").percent(),0.0001);
        GpuUsageParser.Reading warming=GpuUsageParser.parseBusyTime("0 0\n");
        assertFalse(warming.available());
    }

    @Test public void malformedAndOutOfRangeCountersRemainUnavailable() {
        assertFalse(GpuUsageParser.parsePercentage("not-a-number\n").available());
        assertFalse(GpuUsageParser.parsePercentage("101 %\n").available());
        assertFalse(GpuUsageParser.parseBusyTime("100 20\n").available());
        assertFalse(GpuUsageParser.parseBusyTime("-1 20\n").available());
    }
}
