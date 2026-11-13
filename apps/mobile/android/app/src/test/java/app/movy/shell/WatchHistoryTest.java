package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WatchHistoryTest {
    private static WatchHistory.Entry e(String src, String path, String title, double pos, double dur, long at) {
        return new WatchHistory.Entry(src, path, title, pos, dur, at);
    }

    @Test public void recordPutsNewestFirstReplacesTheSameTitleAndCaps() {
        List<WatchHistory.Entry> l = new ArrayList<>();
        l = WatchHistory.record(l, e("a", "/movie/1", "Dune", 100, 9000, 1));
        l = WatchHistory.record(l, e("b", "/tv/2", "Up", 50, 6000, 2));
        l = WatchHistory.record(l, e("a", "/movie/1", "DUNE", 900, 9000, 3));
        assertEquals(2, l.size());
        assertEquals(900, l.get(0).position, 0);
        assertEquals("Up", l.get(1).title);
        // a modal site keeps one path for every title: titles keep them apart
        l = WatchHistory.record(l, e("m", "/", "One", 100, 6000, 4));
        l = WatchHistory.record(l, e("m", "/", "Two", 100, 6000, 5));
        assertEquals(4, l.size());
        for (int i = 0; i < 30; i++) l = WatchHistory.record(l, e("a", "/m/" + i, "T" + i, 100, 6000, 10 + i));
        assertEquals(WatchHistory.MAX, l.size());
        assertEquals("T29", l.get(0).title);
    }

    @Test public void continueWatchingSkipsFinishedAndBarelyStarted() {
        List<WatchHistory.Entry> l = new ArrayList<>();
        l.add(e("a", "/1", "Done", 5800, 6000, 1));
        l.add(e("a", "/2", "Half", 3000, 6000, 1));
        l.add(e("a", "/3", "Peek", 20, 6000, 1));
        List<WatchHistory.Entry> c = WatchHistory.continueWatching(l);
        assertEquals(1, c.size());
        assertEquals("Half", c.get(0).title);
        assertTrue(l.get(0).finished());
        assertEquals(50, c.get(0).percent());
        assertEquals(50, c.get(0).minutesLeft());
    }

    @Test public void savesLongFullscreenVideosEveryFifteenSecondsAndOnPause() {
        assertFalse("trailer", WatchHistory.shouldSave(120, true, false, false, 100_000, 0));
        assertFalse("not fullscreen", WatchHistory.shouldSave(6000, false, false, false, 100_000, 0));
        assertTrue(WatchHistory.shouldSave(6000, true, false, false, 100_000, 80_000));
        assertFalse(WatchHistory.shouldSave(6000, true, false, false, 100_000, 90_000));
        assertTrue("just paused", WatchHistory.shouldSave(6000, true, true, false, 100_000, 99_000));
        assertFalse("still paused", WatchHistory.shouldSave(6000, true, true, true, 200_000, 0));
    }

    @Test public void pathsStayOnTheSiteAndNeverCarryAHost() {
        assertEquals("/movie/1?x=2", WatchHistory.pathOn("https://site.example/movie/1?x=2#t", "https://site.example"));
        assertEquals("/", WatchHistory.pathOn("https://site.example", "https://site.example"));
        assertNull(WatchHistory.pathOn("https://other.example/movie/1", "https://site.example"));
        assertNull(WatchHistory.pathOn("https://site.example//evil.example/x", "https://site.example"));
        assertEquals("https://site.example", WatchHistory.originOf("https://site.example/a/b"));
        assertFalse(WatchHistory.isSitePath("https://x/y"));
        assertFalse(WatchHistory.isSitePath("/a b"));
    }

    @Test public void titlesLoseTheSiteName() {
        assertEquals("Dune: Part Two", WatchHistory.cleanTitle("Watch Dune: Part Two - Movy", "Movy"));
        assertEquals("Dune", WatchHistory.cleanTitle("Movy | Dune", "Movy"));
        assertEquals("", WatchHistory.cleanTitle(" Movy ", "Movy"));
        assertEquals("Movy Tales", WatchHistory.cleanTitle("Movy Tales", "Movy"));
    }

    @Test public void jsonRoundTripDropsBadEntries() {
        List<WatchHistory.Entry> l = new ArrayList<>();
        l.add(e("a", "/movie/1", "Dune", 100.5, 9000, 7));
        String json = WatchHistory.toJson(l);
        List<WatchHistory.Entry> back = WatchHistory.fromJson(json.replace("]", ",{\"s\":\"a\",\"p\":\"https://x/y\",\"t\":\"Bad\"}]"));
        assertEquals(1, back.size());
        assertEquals(100.5, back.get(0).position, 0);
        assertEquals(7, back.get(0).at);
        assertTrue(WatchHistory.fromJson("{oops").isEmpty());
        assertEquals("movy://open?src=a&p=%2Fmovie%2F1", WatchHistory.deepLink(back.get(0)));
    }
}
