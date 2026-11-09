package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleGateTest {
    private static final int DPAD_DOWN = 20;
    private static final int PLAY_PAUSE = 85;

    @Test public void goesIdleAfterFiveSecondsWithoutKeys() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        assertFalse(g.tick(4999));
        assertFalse(g.isIdle());
        assertTrue(g.tick(5000));
        assertTrue(g.isIdle());
        assertFalse("idle is reported once", g.tick(9000));
    }

    @Test public void everyKeyRestartsTheTimer() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.onKey(true, DPAD_DOWN, 3000, null);
        g.onKey(false, DPAD_DOWN, 3050, null);
        assertFalse(g.tick(7999));
        assertEquals(8000, g.dueAt());
        assertTrue(g.tick(8000));
    }

    @Test public void firstKeyAfterIdleWakesAndActs() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.tick(6000);
        boolean[] woke = new boolean[1];
        assertEquals(IdleGate.Decision.PASS, g.onKey(true, DPAD_DOWN, 6100, woke));
        assertTrue(woke[0]);
        assertFalse(g.isIdle());
        assertEquals(IdleGate.Decision.PASS, g.onKey(true, DPAD_DOWN, 6150, woke)); // held key repeat
        assertFalse(woke[0]);
        assertEquals(IdleGate.Decision.PASS, g.onKey(false, DPAD_DOWN, 6200, woke));
    }

    @Test public void playPauseWakesAndActsImmediately() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.tick(6000);
        boolean[] woke = new boolean[1];
        assertEquals(IdleGate.Decision.PASS, g.onKey(true, PLAY_PAUSE, 6100, woke));
        assertTrue(woke[0]);
        assertFalse(g.isIdle());
    }

    @Test public void wakingRestartsTheCountdown() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.tick(6000);
        g.onKey(true, DPAD_DOWN, 6100, null);
        assertEquals(11100, g.dueAt());
        assertFalse(g.tick(11099));
    }

    @Test public void touchRestartsTheCountdownButNeverWakes() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.touch(4000);
        assertFalse(g.tick(8999));
        g.tick(9000);
        g.touch(9500);
        assertTrue("still idle", g.isIdle());
    }
}
