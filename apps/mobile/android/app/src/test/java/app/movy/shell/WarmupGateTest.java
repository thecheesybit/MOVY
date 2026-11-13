package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WarmupGateTest {
    /** Plays from {@code t0} for {@code secs} seconds, one status per second; returns the clock after the last one. */
    private static long play(WarmupGate g, long t0, double head0, int secs, double ahead) {
        long now = t0;
        for (int i = 0; i <= secs; i++) {
            now = t0 + i * 1000L;
            g.onStatus(now, false, head0 + i, ahead, true);
        }
        return now;
    }

    @Test public void opensAfterAMinuteOfSmoothFullscreenPlaybackWithBufferAhead() {
        WarmupGate g = new WarmupGate();
        long now = play(g, 100_000, 0, 59, 40);
        assertFalse("59 s is not enough", g.open(now));
        now = play(g, now + 1000, 60, 2, 40);
        assertTrue(g.open(now));
    }

    @Test public void needsBufferAheadAndPlayingInFullscreen() {
        WarmupGate g = new WarmupGate();
        long now = play(g, 100_000, 0, 70, 20);
        assertFalse("20 s buffered is too little", g.open(now));
        g.onStatus(now + 1000, false, 71, 40, true);
        assertTrue(g.open(now + 1000));
        g.onStatus(now + 2000, true, 72, 40, true);
        assertFalse("paused", g.open(now + 2000));
    }

    @Test public void aStallOrSeekRestartsTheCountAndKeepsItClosedForThirtySeconds() {
        WarmupGate g = new WarmupGate();
        long now = play(g, 100_000, 0, 70, 40);
        g.onStall(now);
        assertFalse(g.open(now));
        now = play(g, now + 1000, 71, 70, 40);
        assertTrue(g.open(now));

        WarmupGate s = new WarmupGate();
        now = play(s, 100_000, 0, 70, 40);
        s.onStatus(now + 1000, false, 400, 40, true); // jumped 330 s in one second
        assertFalse("a seek counts as a stall", s.open(now + 1000));
    }

    @Test public void doubleSpeedIsNormalPlaybackAndGapsDoNotCount() {
        WarmupGate g = new WarmupGate();
        long now = 100_000;
        for (int i = 0; i <= 61; i++) g.onStatus(now + i * 1000L, false, i * 2.0, 40, true);
        assertTrue(g.open(now + 61_000));

        WarmupGate gap = new WarmupGate();
        gap.onStatus(0, false, 0, 40, true);
        gap.onStatus(120_000, false, 120, 40, true); // two minutes with no status: not counted
        assertFalse(gap.open(120_000));
    }

    @Test public void leavingFullscreenResetsAndStartsAreSpacedAndCapped() {
        WarmupGate g = new WarmupGate();
        long now = play(g, 100_000, 0, 70, 40);
        g.onStatus(now + 1000, false, 71, 40, false);
        assertFalse("not fullscreen", g.open(now + 1000));
        now = play(g, now + 2000, 72, 30, 40);
        assertFalse("count restarted after leaving fullscreen", g.open(now));

        WarmupGate c = new WarmupGate();
        now = play(c, 100_000, 0, 70, 40);
        for (int k = 0; k < 5; k++) {
            if (c.open(now)) c.started(now);
            assertFalse("spaced", c.open(now + 1000));
            now = play(c, now + 1000, 71 + k * 61, 60, 40);
        }
        assertEquals(WarmupGate.MAX_PER_SESSION, c.startedCount());
        assertFalse(c.open(now));
    }
}
