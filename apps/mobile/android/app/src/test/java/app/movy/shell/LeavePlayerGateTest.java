package app.movy.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeavePlayerGateTest {
    @Test public void aSecondBackInsideTheWindowIsSwallowed() {
        LeavePlayerGate g = new LeavePlayerGate();
        g.arm("p", 1000);
        assertTrue(g.swallowsBack(1100));
        assertTrue(g.swallowsBack(1000 + LeavePlayerGate.WINDOW_MS - 1));
    }

    @Test public void backWorksAgainAfterTheWindowAndBeforeAnyArm() {
        LeavePlayerGate g = new LeavePlayerGate();
        assertFalse("never armed", g.swallowsBack(0));
        g.arm("p", 1000);
        assertFalse(g.swallowsBack(1000 + LeavePlayerGate.WINDOW_MS));
    }

    @Test public void theDelayedLeaveSkipsWhenThePageChanged() {
        LeavePlayerGate g = new LeavePlayerGate();
        g.arm("p1", 0);
        assertTrue(g.mayRun("p1"));
        assertFalse(g.mayRun("p2"));
        assertFalse(g.mayRun(null));
    }

    @Test public void notArmedNeverRuns() {
        assertFalse(new LeavePlayerGate().mayRun("p"));
    }
}
