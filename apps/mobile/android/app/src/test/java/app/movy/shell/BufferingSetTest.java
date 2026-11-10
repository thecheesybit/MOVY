package app.movy.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BufferingSetTest {
    @Test public void addAndRemove() {
        BufferingSet s = new BufferingSet();
        assertTrue(s.isEmpty(0));
        assertTrue(s.set("a.1", true, 0));
        assertFalse(s.isEmpty(1));
        assertTrue(s.set("a.1", false, 2));
        assertTrue(s.isEmpty(3));
    }

    @Test public void capsTheNumberOfPlayers() {
        BufferingSet s = new BufferingSet();
        for (int i = 0; i < BufferingSet.MAX_TOKENS; i++) assertTrue(s.set("t" + i, true, 0));
        assertFalse(s.set("overflow", true, 0));
        assertTrue("a known token may refresh", s.set("t0", true, 1));
        assertTrue("off is always accepted", s.set("t1", false, 1));
        assertTrue(s.set("overflow", true, 1));
    }

    @Test public void rejectsBadTokens() {
        BufferingSet s = new BufferingSet();
        assertFalse(s.set(null, true, 0));
        assertFalse(s.set("", true, 0));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= BufferingSet.MAX_TOKEN_LENGTH; i++) big.append('x');
        assertFalse(s.set(big.toString(), true, 0));
        assertTrue(s.isEmpty(0));
    }

    @Test public void aStaleEntryExpires() {
        BufferingSet s = new BufferingSet();
        s.set("a.1", true, 0);
        assertFalse(s.isEmpty(BufferingSet.TTL_MS));
        assertTrue(s.isEmpty(BufferingSet.TTL_MS + 1));
    }

    @Test public void clearEmptiesIt() {
        BufferingSet s = new BufferingSet();
        s.set("a.1", true, 0);
        s.clear();
        assertTrue(s.isEmpty(0));
    }
}
