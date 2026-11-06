package dev.nova.deck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScreenshotHoldPolicyTest {
    @Test public void shortTapCyclesTarget() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.update(250L));
        assertEquals(ScreenshotHoldPolicy.Outcome.CYCLE, policy.release(280L));
        assertFalse(policy.isActive());
    }

    @Test public void aSlowerTapStillCyclesWithoutRequiringFastInput() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(ScreenshotHoldPolicy.Outcome.CYCLE, policy.release(600L));
    }

    @Test public void partialHoldReleaseCancels() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.update(900L));
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.release(1200L));
        assertFalse(policy.isActive());
    }

    @Test public void ringWaitsHalfSecondThenFillsForOneSecond() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(0f, policy.progress(600L), 0.0001f);
        assertEquals(0f, policy.progress(601L), 0.001f);
        assertEquals(0.5f, policy.progress(1100L), 0.001f);
        assertEquals(1f, policy.progress(1600L), 0.001f);
        assertEquals(ScreenshotHoldPolicy.Outcome.CAPTURE, policy.update(1600L));
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.update(1700L));
    }

    @Test public void completionCapturesExactlyOnce() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(ScreenshotHoldPolicy.Outcome.CAPTURE, policy.update(1600L));
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.update(1900L));
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.release(2000L));
        assertFalse(policy.isActive());
    }

    @Test public void releaseAtThresholdCapturesWhenFrameWasDelayed() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        assertEquals(ScreenshotHoldPolicy.Outcome.CAPTURE, policy.release(1600L));
    }

    @Test public void cancelResetsAndCannotCaptureOrCycle() {
        ScreenshotHoldPolicy policy = new ScreenshotHoldPolicy();
        policy.begin(100L);
        policy.cancel();
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.update(1200L));
        assertEquals(ScreenshotHoldPolicy.Outcome.NONE, policy.release(1200L));
        assertFalse(policy.isActive());
        policy.begin(1300L);
        assertEquals(ScreenshotHoldPolicy.Outcome.CYCLE, policy.release(1400L));
    }

    @Test public void onlyScreenshotOutsideEditCanBegin() {
        assertTrue(ScreenshotHoldPolicy.canBegin(true, false));
        assertFalse(ScreenshotHoldPolicy.canBegin(true, true));
        assertFalse(ScreenshotHoldPolicy.canBegin(false, false));
    }
}
