package app.movy.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FailoverTest {
    @Test public void progressThatMovesForwardRestartsTheStallWindow() {
        assertTrue(Failover.shouldRearm(10, 11, 20_000));
        assertTrue(Failover.shouldRearm(0, 5, 0));
    }

    @Test public void noNewProgressDoesNotRearm() {
        assertFalse(Failover.shouldRearm(40, 40, 5_000));
        assertFalse(Failover.shouldRearm(40, 30, 5_000));
    }

    @Test public void theHardCapEndsEvenASlowlyProgressingLoad() {
        assertFalse(Failover.shouldRearm(50, 60, Failover.HARD_CAP_MS));
        assertTrue(Failover.LOW_END_TIMEOUT_MS > Failover.TIMEOUT_MS);
    }
}
