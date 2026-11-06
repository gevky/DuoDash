package dev.nova.deck;

import org.junit.Test;
import static org.junit.Assert.*;

public class MetricModePolicyTest {
    @Test public void unknownOrMissingPreferenceDefaultsToClock(){
        assertEquals(MetricModePolicy.CLOCK,MetricModePolicy.normalize(null));
        assertEquals(MetricModePolicy.CLOCK,MetricModePolicy.normalize(""));
        assertEquals(MetricModePolicy.CLOCK,MetricModePolicy.normalize("percent"));
        assertEquals(MetricModePolicy.USAGE,MetricModePolicy.normalize("USAGE "));
    }

    @Test public void onlyClockAndUsageAreSelectable(){
        assertEquals(MetricModePolicy.USAGE,MetricModePolicy.normalize("usage"));
        assertEquals(MetricModePolicy.USAGE,MetricModePolicy.toggle(MetricModePolicy.CLOCK));
        assertEquals(MetricModePolicy.CLOCK,MetricModePolicy.toggle(MetricModePolicy.USAGE));
    }

    @Test public void metricModesUseGlobalStablePreferenceKeys(){
        assertEquals("cpu_metric_mode",MetricModePolicy.CPU_PREF);
        assertEquals("gpu_metric_mode",MetricModePolicy.GPU_PREF);
    }

    @Test public void utilizationNeverFabricatesOutOfRangeOrNonFinitePercentages(){
        assertTrue(MetricModePolicy.isValidUsage(0));
        assertTrue(MetricModePolicy.isValidUsage(42.5f));
        assertTrue(MetricModePolicy.isValidUsage(100));
        assertFalse(MetricModePolicy.isValidUsage(-0.1f));
        assertFalse(MetricModePolicy.isValidUsage(100.1f));
        assertFalse(MetricModePolicy.isValidUsage(Float.NaN));
        assertFalse(MetricModePolicy.isValidUsage(Float.POSITIVE_INFINITY));
    }
}
