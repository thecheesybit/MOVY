package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

public class DohSlotTest {
    @Test public void aFullHostAnswersBusyInsteadOfWaitingForever() throws Exception {
        DohProxy p = new DohProxy();
        for (int i = 0; i < DohProxy.MAX_CONNECTIONS; i++) p.acquireSlot("h.example", 10);
        long t0 = System.nanoTime();
        try {
            p.acquireSlot("h.example", 50);
            fail("expected busy");
        } catch (IOException e) {
            assertEquals("busy", e.getMessage());
        }
        assertTrue("waited about the asked time", (System.nanoTime() - t0) / 1_000_000 < 1000);
    }

    @Test public void aReleasedSlotIsTakenAgainAndHostsAreIndependent() throws Exception {
        DohProxy p = new DohProxy();
        for (int i = 0; i < DohProxy.MAX_CONNECTIONS; i++) p.acquireSlot("h.example", 10);
        p.acquireSlot("other.example", 10); // another host has its own slots
        p.releaseSlot("h.example");
        p.acquireSlot("h.example", 10);
    }

    @Test public void theWaitBeforeFallingBackIsShort() {
        assertTrue(DohProxy.SLOT_WAIT_MS >= 5_000 && DohProxy.SLOT_WAIT_MS <= 8_000);
    }
}
