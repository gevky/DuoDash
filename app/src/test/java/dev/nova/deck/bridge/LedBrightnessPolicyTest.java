package dev.nova.deck.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class LedBrightnessPolicyTest {
    @Test public void acceptsOnlyFinitePositiveNativePercentagesThroughOne() {
        assertTrue(LedBrightnessPolicy.isUsableBrightness("0.42"));
        assertTrue(LedBrightnessPolicy.isUsableBrightness("1.0"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness(null));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("null"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("0"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("-0.1"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("1.01"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("NaN"));
        assertFalse(LedBrightnessPolicy.isUsableBrightness("Infinity"));
    }

    @Test public void capturesCurrentLevelAndKeepsSavedLevelWhenAlreadyOff() {
        assertEquals("0.37", LedBrightnessPolicy.brightnessToCaptureWhenTurningOff("0.37"));
        // With the live key at zero, the caller must not replace the stored level.
        assertNull(LedBrightnessPolicy.brightnessToCaptureWhenTurningOff("0.0"));
        assertNull(LedBrightnessPolicy.brightnessToCaptureWhenTurningOff("null"));
        assertEquals("0.8", LedBrightnessPolicy.valueToRestore("0.0", "0.8"));
    }

    @Test public void invalidOrMissingSavedValueUsesLegacyFullBrightnessFallback() {
        assertEquals("1.0", LedBrightnessPolicy.valueToRestore("0.0", null));
        assertEquals("1.0", LedBrightnessPolicy.valueToRestore("0.0", "NaN"));
        assertEquals("1.0", LedBrightnessPolicy.valueToRestore("0.0", "1.4"));
    }

    @Test public void enablingDoesNotReplaceAnAlreadyNonzeroNativeLevel() {
        assertEquals("0.28", LedBrightnessPolicy.valueToRestore("0.28", "0.8"));
    }
}
