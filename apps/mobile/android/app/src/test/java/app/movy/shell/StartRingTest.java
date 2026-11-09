package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StartRingTest {
    @Test public void fillsOnceOverTheRingTime() {
        StartRing r = new StartRing(1000);
        assertEquals(0f, r.progress(1000), 0.001f);
        assertEquals(0.5f, r.progress(1000 + StartRing.RING_MS / 2), 0.001f);
        assertEquals(StartRing.Phase.RING, r.phase(1000 + StartRing.RING_MS - 1));
        assertEquals(1f, r.progress(1000 + StartRing.RING_MS), 0.001f);
    }

    @Test public void fullRingWaitsAsSpinnerUntilThePagePaints() {
        StartRing r = new StartRing(0);
        long t = StartRing.RING_MS + 50;
        assertEquals(StartRing.Phase.SPINNER, r.phase(t));
        r.pageReady(t);
        assertEquals(StartRing.Phase.OPEN, r.phase(t));
    }

    @Test public void readyEarlyFastForwardsTheLastPart() {
        StartRing r = new StartRing(0);
        r.pageReady(500); // 1/6 of the ring is drawn
        float p0 = r.progress(500);
        assertEquals(500f / StartRing.RING_MS, p0, 0.001f);
        assertEquals(StartRing.Phase.RING, r.phase(500 + StartRing.FAST_MS - 1));
        assertTrue(r.progress(500 + StartRing.FAST_MS / 2) > p0);
        assertEquals(StartRing.Phase.OPEN, r.phase(500 + StartRing.FAST_MS));
        assertEquals(1f, r.progress(500 + StartRing.FAST_MS), 0.001f);
    }

    @Test public void readyNearTheEndKeepsTheNaturalEnd() {
        StartRing r = new StartRing(0);
        long near = StartRing.RING_MS - 100;
        r.pageReady(near);
        assertEquals(StartRing.Phase.OPEN, r.phase(StartRing.RING_MS));
        assertEquals(StartRing.Phase.RING, r.phase(StartRing.RING_MS - 1));
    }

    @Test public void okSkipsTheRingButStillNeedsAPaint() {
        StartRing r = new StartRing(0);
        r.skip(1000);
        assertEquals(StartRing.Phase.SPINNER, r.phase(1000 + StartRing.FAST_MS));
        r.pageReady(1500);
        assertEquals(StartRing.Phase.OPEN, r.phase(1500));
    }

    @Test public void restartResetsRingAndReadiness() {
        StartRing r = new StartRing(0);
        r.pageReady(100);
        r.restart(5000);
        assertEquals(0f, r.progress(5000), 0.001f);
        assertEquals(false, r.isReady());
        assertEquals(StartRing.Phase.RING, r.phase(5000 + 1000));
    }

    @Test public void progressNeverGoesBackwards() {
        StartRing r = new StartRing(0);
        float last = 0f;
        for (long t = 0; t <= 3500; t += 50) {
            if (t == 700) r.pageReady(t);
            float p = r.progress(t);
            assertTrue("t=" + t, p >= last - 0.0001f);
            last = p;
        }
    }
}
