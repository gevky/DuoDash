package dev.nova.deck.bridge;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CpuLoadSamplerTest {
    @Test public void firstAggregateSampleWaitsForDeltaThenReportsOverallBusyPercent() {
        CpuLoadSampler sampler=new CpuLoadSampler();
        CpuLoadSampler.Reading first=sampler.sample("cpu  100 10 30 860 0 0 0 0 0 0\ncpu0 50 5 15 430 0 0 0 0\n");
        assertFalse(first.available());
        assertEquals("Waiting for CPU sample",first.reason());

        CpuLoadSampler.Reading next=sampler.sample("cpu  140 10 40 910 0 0 0 0 0 0\ncpu0 70 5 20 455 0 0 0 0\n");
        assertTrue(next.available());
        assertEquals(50.0,next.percent(),0.0001);
    }

    @Test public void idleAndBusyDeltasUseAggregateCapacityAndIgnoreGuestDoubleCount() {
        CpuLoadSampler sampler=new CpuLoadSampler();
        sampler.sample("cpu 100 0 0 900 0 0 0 0 80 4\n");
        CpuLoadSampler.Reading idle=sampler.sample("cpu 100 0 0 1000 0 0 0 0 90 5\n");
        assertEquals(0.0,idle.percent(),0.0001);

        CpuLoadSampler another=new CpuLoadSampler();
        another.sample("cpu 100 0 0 900 0 0 0 0 80 4\n");
        CpuLoadSampler.Reading busy=another.sample("cpu 150 0 0 950 0 0 0 0 130 9\n");
        assertEquals(50.0,busy.percent(),0.0001);
    }

    @Test public void acceptsTabSeparatedAggregateCpuRows() {
        CpuLoadSampler sampler=new CpuLoadSampler();
        sampler.sample("cpu\t100\t0\t0\t900\t0\t0\t0\t0\n");
        CpuLoadSampler.Reading reading=sampler.sample("cpu\t150\t0\t0\t950\t0\t0\t0\t0\n");
        assertTrue(reading.available());
        assertEquals(50.0,reading.percent(),0.0001);
    }

    @Test public void malformedOrMissingInputIsUnavailableAndDoesNotInventZero() {
        CpuLoadSampler sampler=new CpuLoadSampler();
        assertFalse(sampler.sample("not a cpu row\n").available());
        assertFalse(sampler.sample(null).available());
        assertFalse(sampler.sample("cpu 10 0 0 0 0 0 0 0\n").available());
    }

    @Test public void resetOrZeroDeltaRebaselinesWithoutReturningFalseUsage() {
        CpuLoadSampler sampler=new CpuLoadSampler();
        sampler.sample("cpu 100 0 0 900 0 0 0 0\n");
        CpuLoadSampler.Reading reset=sampler.sample("cpu 1 0 0 9 0 0 0 0\n");
        assertFalse(reset.available());
        assertEquals("CPU counters restarted",reset.reason());

        CpuLoadSampler.Reading zeroDelta=sampler.sample("cpu 1 0 0 9 0 0 0 0\n");
        assertFalse(zeroDelta.available());
        assertEquals("Waiting for CPU sample",zeroDelta.reason());
    }
}
