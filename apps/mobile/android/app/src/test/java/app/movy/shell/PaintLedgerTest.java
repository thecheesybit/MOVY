package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaintLedgerTest {
    private static final Obfuscator OBF = new Obfuscator("AQIDBAUGBwgJCgsMDQ4PEA==");

    private static LiveConfig sample() {
        return LiveConfig.parseDefaults("{\"recommendedSource\":\"a\",\"sources\":[{\"id\":\"a\",\"name\":\"Alpha\",\"urlEnc\":\""
                + OBF.encode("https://a.example.com/") + "\",\"allowedHosts\":[\"a.example.com\"]},{\"id\":\"b\",\"name\":\"Beta\",\"urlEnc\":\""
                + OBF.encode("https://b.example.org/") + "\",\"allowedHosts\":[\"b.example.org\"]}]}", OBF);
    }

    @Test public void keepsAPaintThatArrivedEarly() {
        PaintLedger l = new PaintLedger();
        assertFalse(l.hasPainted("a"));
        l.painted("a");
        assertTrue(l.hasPainted("a"));
        assertFalse(l.hasPainted("b"));
    }

    @Test public void aRealLoadForgetsEveryPaint() {
        PaintLedger l = new PaintLedger();
        l.painted("a");
        l.painted("b");
        l.loadRequested();
        assertFalse(l.hasPainted("a"));
        assertFalse(l.hasPainted("b"));
    }

    @Test public void nullIdsAreIgnored() {
        PaintLedger l = new PaintLedger();
        l.painted(null);
        assertFalse(l.hasPainted(null));
    }

    @Test public void paintCountsOnlyForTheCurrentPagesSource() {
        LiveConfig cfg = sample();
        String a = cfg.enabledSources().get(0).url;
        String b = cfg.enabledSources().get(1).url;
        assertEquals(cfg.enabledSources().get(0).id, PaintLedger.accept(cfg, a, a));
        assertNull("late paint of the previous document", PaintLedger.accept(cfg, a, b));
        assertNull("a frame that is not a source page", PaintLedger.accept(cfg, "https://ads.invalid/x", a));
        assertNull(PaintLedger.accept(cfg, null, a));
        assertNull(PaintLedger.accept(cfg, a, null));
    }
}
