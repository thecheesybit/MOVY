package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessMathTest {
    @Test public void clampKeepsLevelInRange() {
        assertEquals(BrightnessMath.MIN, BrightnessMath.clamp(-1f), 0f);
        assertEquals(BrightnessMath.MAX, BrightnessMath.clamp(3f), 0f);
        assertEquals(0.5f, BrightnessMath.clamp(0.5f), 0f);
        assertEquals(BrightnessMath.MAX, BrightnessMath.clamp(Float.NaN), 0f);
    }

    @Test public void zoneIsLeftMiddleOnly() {
        assertTrue(BrightnessMath.inZone(100, 500, 2000, 1000));
        assertFalse(BrightnessMath.inZone(900, 500, 2000, 1000)); // right of 40%
        assertFalse(BrightnessMath.inZone(100, 50, 2000, 1000)); // top edge
        assertFalse(BrightnessMath.inZone(100, 950, 2000, 1000)); // bottom edge
        assertFalse(BrightnessMath.inZone(100, 500, 0, 0));
    }

    @Test public void swipeNeedsLengthAndVerticalBias() {
        assertTrue(BrightnessMath.isVerticalSwipe(5, 60, 16));
        assertFalse(BrightnessMath.isVerticalSwipe(5, 20, 16)); // too short
        assertFalse(BrightnessMath.isVerticalSwipe(60, 60, 16)); // diagonal
        assertFalse(BrightnessMath.isVerticalSwipe(80, 40, 16)); // horizontal (a seek)
    }

    @Test public void draggingUpBrightensAndDownDims() {
        assertTrue(BrightnessMath.fromDrag(0.5f, -200, 1000) > 0.5f);
        assertTrue(BrightnessMath.fromDrag(0.5f, 200, 1000) < 0.5f);
        assertEquals(BrightnessMath.MAX, BrightnessMath.fromDrag(0.9f, -1000, 1000), 0f);
        assertEquals(BrightnessMath.MIN, BrightnessMath.fromDrag(0.1f, 1000, 1000), 0f);
        assertEquals(0.6f, BrightnessMath.fromDrag(0.6f, 10, 0), 0f);
    }

    @Test public void fullSpanDragCoversTheRange() {
        assertEquals(BrightnessMath.MAX, BrightnessMath.fromDrag(BrightnessMath.MIN, -700, 1000), 0.001f);
    }

    @Test public void percentRounds() {
        assertEquals(50, BrightnessMath.percent(0.5f));
        assertEquals(100, BrightnessMath.percent(5f));
        assertEquals(2, BrightnessMath.percent(0f));
    }
}
