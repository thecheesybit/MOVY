package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreloadPolicyTest {
    private static final long H = 60L * 60 * 1000;

    @Test public void thirtyTwoBitLowRamOrQuadCoreDevicesAreLowEnd() {
        assertTrue(PreloadPolicy.isLowEnd(false, false, 8)); // 32-bit ARMv7 TV
        assertTrue(PreloadPolicy.isLowEnd(true, true, 8));
        assertTrue(PreloadPolicy.isLowEnd(false, true, 4));
        assertFalse(PreloadPolicy.isLowEnd(false, true, 8));
    }

    @Test public void lowEndDevicesStartLaterAndNeverInParallel() {
        assertEquals(PreloadPolicy.LOW_END_START_DELAY_MS, PreloadPolicy.startDelayMs(true));
        assertEquals(PreloadPolicy.START_DELAY_MS, PreloadPolicy.startDelayMs(false));
        assertFalse(PreloadPolicy.warmInParallel(true, true));
        assertTrue(PreloadPolicy.warmInParallel(true, false));
        assertFalse(PreloadPolicy.warmInParallel(false, false));
    }

    @Test public void onlyRealSourcePagesStartTheWarmup() {
        assertFalse(PreloadPolicy.isSourcePage("https://localhost/", false));
        assertFalse(PreloadPolicy.isSourcePage("about:blank", true));
        assertFalse(PreloadPolicy.isSourcePage(null, true));
        assertTrue(PreloadPolicy.isSourcePage("https://example.test/", true));
    }

    @Test public void neverWarmedSourcesAreWarmed() {
        assertTrue(PreloadPolicy.shouldWarm(1_000_000_000L, 0, 5000));
    }

    @Test public void recentlyWarmedSourcesWithAFilledCacheAreSkipped() {
        long now = 1_000_000_000L;
        assertFalse(PreloadPolicy.shouldWarm(now, now - 11 * H, 400));
        assertFalse(PreloadPolicy.shouldWarm(now, now - 1, PreloadPolicy.MIN_CACHE_ENTRIES));
    }

    @Test public void staleWarmupsAreRepeated() {
        long now = 1_000_000_000L;
        assertTrue(PreloadPolicy.shouldWarm(now, now - 12 * H, 400));
        assertTrue(PreloadPolicy.shouldWarm(now, now - 30 * H, 4000));
    }

    @Test public void aClearedCacheInvalidatesARecentWarmup() {
        long now = 1_000_000_000L;
        assertTrue(PreloadPolicy.shouldWarm(now, now - H, PreloadPolicy.MIN_CACHE_ENTRIES - 1));
        assertTrue(PreloadPolicy.shouldWarm(now, now - H, 0));
    }

    @Test public void aClockThatWentBackwardsDoesNotSuppressWarming() {
        assertTrue(PreloadPolicy.shouldWarm(1000, 5000, 400));
    }

    @Test public void timingConstantsMatchThePlan() {
        assertEquals(1500, PreloadPolicy.START_DELAY_MS);
        assertEquals(10_000, PreloadPolicy.RESUME_IDLE_MS);
        assertEquals(12 * H, PreloadPolicy.WARM_TTL_MS);
    }
}
