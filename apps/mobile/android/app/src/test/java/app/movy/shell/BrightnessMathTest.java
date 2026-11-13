package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrightnessMathTest {
    private static final boolean PHONE = false;
    private static final boolean TV = true;

    @Test public void clampKeepsLevelInEachRange() {
        assertEquals(BrightnessMath.MIN_PHONE, BrightnessMath.clamp(-1f, PHONE), 0f);
        assertEquals(BrightnessMath.MIN_TV, BrightnessMath.clamp(0.1f, TV), 0f);
        assertEquals(2f, BrightnessMath.clamp(9f, PHONE), 0f);
        assertEquals(2f, BrightnessMath.clamp(9f, TV), 0f);
        assertEquals(1f, BrightnessMath.clamp(Float.NaN, PHONE), 0f);
    }

    @Test public void phoneUsesScreenBrightnessUpTo100ThenGain() {
        assertEquals(0.5f, BrightnessMath.windowLevel(0.5f, PHONE), 0f);
        assertEquals(1f, BrightnessMath.windowLevel(1.6f, PHONE), 0f);
        assertEquals(1f, BrightnessMath.gain(0.5f, PHONE), 0f);
        assertEquals(1f, BrightnessMath.gain(1f, PHONE), 0f);
        assertEquals(1.6f, BrightnessMath.gain(1.6f, PHONE), 0f);
        assertEquals(2f, BrightnessMath.gain(2f, PHONE), 0f);
    }

    @Test public void tvNeverTouchesScreenBrightnessAndGainsAcrossTheRange() {
        assertEquals(-1f, BrightnessMath.windowLevel(0.7f, TV), 0f);
        assertEquals(0.7f, BrightnessMath.gain(0.7f, TV), 0f);
        assertEquals(1.5f, BrightnessMath.gain(1.5f, TV), 0f);
        assertEquals(BrightnessMath.MIN_TV, BrightnessMath.gain(0.1f, TV), 0f);
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
        assertTrue(BrightnessMath.fromDrag(1f, -200, 1000, PHONE) > 1f);
        assertTrue(BrightnessMath.fromDrag(1f, 200, 1000, PHONE) < 1f);
        assertEquals(2f, BrightnessMath.fromDrag(1.9f, -1000, 1000, PHONE), 0f);
        assertEquals(BrightnessMath.MIN_PHONE, BrightnessMath.fromDrag(0.1f, 1000, 1000, PHONE), 0f);
        assertEquals(0.6f, BrightnessMath.fromDrag(0.6f, 10, 0, PHONE), 0f);
    }

    @Test public void fullSpanDragCoversTheWholeRange() {
        assertEquals(2f, BrightnessMath.fromDrag(BrightnessMath.MIN_PHONE, -700, 1000, PHONE), 0.001f);
        assertEquals(BrightnessMath.MIN_PHONE, BrightnessMath.fromDrag(2f, 700, 1000, PHONE), 0.001f);
    }

    @Test public void remoteStepsMoveBy10PercentAndStopAtTheEnds() {
        assertEquals(1.1f, BrightnessMath.step(1f, 1, TV), 0.0001f);
        assertEquals(0.9f, BrightnessMath.step(1f, -1, TV), 0.0001f);
        assertEquals(0.9f, BrightnessMath.step(0.93f, -1, TV), 0.0001f); // snaps to the grid line below
        assertEquals(2f, BrightnessMath.step(2f, 1, TV), 0f);
        assertEquals(BrightnessMath.MIN_TV, BrightnessMath.step(BrightnessMath.MIN_TV, -1, TV), 0f);
        float v = 1f;
        for (int i = 0; i < 40; i++) v = BrightnessMath.step(v, 1, TV);
        assertEquals(2f, v, 0f);
        for (int i = 0; i < 40; i++) v = BrightnessMath.step(v, -1, TV);
        assertEquals(BrightnessMath.MIN_TV, v, 0f);
    }

    @Test public void fractionAndPercent() {
        assertEquals(0f, BrightnessMath.fraction(BrightnessMath.MIN_TV, TV), 0f);
        assertEquals(1f, BrightnessMath.fraction(2f, TV), 0f);
        assertEquals(50, BrightnessMath.percent(0.5f, PHONE));
        assertEquals(200, BrightnessMath.percent(5f, PHONE));
        assertEquals(40, BrightnessMath.percent(0f, TV));
    }

    @Test public void volumeZoneAndDrag() {
        assertTrue(BrightnessMath.inVolumeZone(900, 500, 1000, 1000));
        assertFalse(BrightnessMath.inVolumeZone(100, 500, 1000, 1000));
        assertFalse(BrightnessMath.inVolumeZone(900, 20, 1000, 1000));
        assertEquals(1f, BrightnessMath.volumeFromDrag(0.9f, -1000, 1000), 1e-6);
        assertEquals(0f, BrightnessMath.volumeFromDrag(0.1f, 1000, 1000), 1e-6);
        assertEquals(0.5f, BrightnessMath.volumeFromDrag(0.5f, 0, 1000), 1e-6);
    }

    @Test public void gainScriptAddsOnlyWhenNotNeutral() {
        String on = BrightnessMath.gainScript(1.5f);
        assertTrue(on.contains("brightness(1.50)"));
        assertTrue(on.contains(":fullscreen"));
        assertTrue(on.contains(":-webkit-full-screen"));
        // Sites ship rules like `html[data-x] :not(video) { filter: none !important }`; ours must outrank them.
        assertTrue(on.contains("':fullscreen:fullscreen:fullscreen'"));
        assertTrue(on.contains(":-webkit-full-screen:-webkit-full-screen:-webkit-full-screen'"));
        assertTrue(on.contains("F+' video'+r")); // same-document video: only the picture is filtered
        String off = BrightnessMath.gainScript(1f);
        assertTrue(off.contains("remove()"));
        assertFalse(off.contains("brightness("));
    }
}
