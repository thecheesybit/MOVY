package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ShellEngineTest {
    private static final String URL = "https://site.test/";
    private static final String SRC = "alpha";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] now = {1_000_000_000L};
    private ShellStore store;
    private FakeFetcher net;
    private Recorder hooks;
    private ExecutorService exec;
    private ShellEngine engine;
    private ShellSpec spec;

    @Before public void setUp() throws IOException {
        store = new ShellStore(tmp.newFolder("shell"));
        net = new FakeFetcher();
        hooks = new Recorder();
        exec = Executors.newFixedThreadPool(2);
        engine = new ShellEngine(store, net, () -> now[0], exec, hooks);
        spec = new ShellSpec(Collections.singletonList("/"), Collections.emptyList(), Collections.singletonList("#root"), 300, 12, 3);
    }

    @After public void tearDown() {
        exec.shutdownNow();
    }

    private static String page(String build, String body) {
        StringBuilder sb = new StringBuilder("<html><body><div id=\"root\">" + body + "</div><script src=\"/assets/index-" + build + ".js\"></script>"
                + "<link rel=\"stylesheet\" href=\"/assets/app-" + build + ".css\">");
        while (sb.length() < ShellPolicy.MIN_BYTES + 100) sb.append("<!-- padding padding padding padding padding -->");
        return sb.append("</body></html>").toString();
    }

    private void waitIdle() throws InterruptedException {
        for (int i = 0; i < 200 && !engine.idle(); i++) Thread.sleep(10);
        Thread.sleep(30);
    }

    private ShellEngine.Answer handle() {
        return engine.handle(SRC, spec, URL, new HashMap<>());
    }

    @Test public void firstVisitLoadsLiveAndCapturesASnapshot() throws Exception {
        net.html = page("AAAA1111", "home v1");
        assertNull("no local copy yet", handle());
        waitIdle();
        ShellSnapshot s = store.newestGood(SRC, "root");
        assertNotNull(s);
        assertEquals(1, s.tag);
        assertEquals("AAAA1111", s.buildId);
        assertTrue(s.assets.contains("https://site.test/assets/index-AAAA1111.js"));
        assertEquals("pins cover the stored version's assets", 2, hooks.lastPins.size());
    }

    @Test public void secondVisitIsServedLocallyAndLiveUnchangedKeepsIt() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.notModified = true;
        now[0] += 60_000;
        ShellEngine.Answer a = handle();
        assertNotNull(a);
        assertEquals(1, a.snapshot.tag);
        assertEquals(ShellPolicy.Live.UNCHANGED, a.live);
        assertTrue(new String(a.body, StandardCharsets.UTF_8).contains("home v1"));
        waitIdle();
        assertEquals("verified: fetchedAt moved", now[0], store.newestGood(SRC, "root").fetchedAt);
        assertEquals(1, store.list(SRC, "root").size());
    }

    @Test public void aSlowLiveSiteDoesNotDelayTheLocalCopy() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        CountDownLatch gate = new CountDownLatch(1);
        net.gate = gate;
        long t0 = System.nanoTime();
        ShellEngine.Answer a = handle();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull(a);
        assertEquals(ShellPolicy.Live.PENDING, a.live);
        assertTrue("answered within the race window, took " + ms, ms < 1500);
        gate.countDown();
        waitIdle();
    }

    @Test public void aLiveChangeIsStoredNewestFirstAndTheWebViewLoadsLive() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.html = page("AAAA1111", "home v2 changed data");
        now[0] += 60_000;
        assertNull("live changed: the WebView loads the live page", handle());
        waitIdle();
        List<ShellSnapshot> l = store.list(SRC, "root");
        assertEquals(2, l.size());
        assertEquals(2, l.get(0).tag);
        assertEquals("same build: no apply needed", 0, hooks.updates.size());
    }

    @Test public void aNewBuildIdIsAppliedOnlyAfterItsAssetsAreCached() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.html = page("BBBB2222", "home v1");
        now[0] += 60_000;
        handle();
        waitIdle();
        assertEquals("update announced once the prefetch finished", 1, hooks.updates.size());
        assertEquals(SRC, hooks.updates.get(0));
        assertTrue(hooks.prefetched.contains("https://site.test/assets/index-BBBB2222.js"));
        assertEquals("BBBB2222", store.newestGood(SRC, "root").buildId);
    }

    @Test public void theRingKeepsThreeVersionsAndReleasesTheOldestPins() throws Exception {
        for (int i = 1; i <= 4; i++) {
            net.html = page("BUILD00" + i, "home v" + i);
            now[0] += 60_000;
            handle();
            waitIdle();
        }
        List<ShellSnapshot> l = store.list(SRC, "root");
        assertEquals(3, l.size());
        assertEquals(4, l.get(0).tag);
        assertEquals(2, l.get(2).tag);
        assertFalse("assets of the dropped version are no longer pinned", hooks.lastPins.contains("https://site.test/assets/index-BUILD001.js"));
        assertTrue(hooks.lastPins.contains("https://site.test/assets/index-BUILD002.js"));
    }

    @Test public void offlineServesTheNewestGoodSnapshotEvenWhenStale() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.offline = true;
        now[0] += 30L * 60 * 60 * 1000; // 30 h: far past max age
        ShellEngine.Answer a = handle();
        assertNotNull("better than an error page", a);
        assertEquals(ShellPolicy.Live.FAILED, a.live);
    }

    @Test public void aStaleSnapshotWithASilentSiteLoadsLive() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.gate = new CountDownLatch(1);
        now[0] += 13L * 60 * 60 * 1000;
        assertNull("stale and no answer within the wait: the normal loading UI", handle());
        net.gate.countDown();
        waitIdle();
    }

    @Test public void aSnapshotThatFailedItsBootCheckIsNeverServedAgainAndThePreviousOneBacksLiveUp() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        net.html = page("BBBB2222", "home v2");
        now[0] += 60_000;
        handle();
        waitIdle();
        ShellSnapshot v2 = store.newestGood(SRC, "root");
        assertEquals(2, v2.tag);
        engine.markBad(v2);
        assertEquals("v1 is the newest good one now", 1, store.newestGood(SRC, "root").tag);
        net.offline = true; // the site is down too
        ShellEngine.Answer a = handle();
        assertNotNull(a);
        assertEquals("boot the previous version", 1, a.snapshot.tag);
        net.offline = false;
        net.notModified = false;
        assertEquals("bad snapshot stays on disk, flagged", 2, store.newest(SRC, "root").tag);
        assertTrue(store.newest(SRC, "root").bad);
    }

    @Test public void challengePagesAndRedirectsAreNeverStored() throws Exception {
        net.html = page("AAAA1111", "Just a moment...");
        assertNull(handle());
        waitIdle();
        assertNull(store.newest(SRC, "root"));
        net.html = page("AAAA1111", "ok");
        net.status = 302;
        handle();
        waitIdle();
        assertNull(store.newest(SRC, "root"));
    }

    @Test public void cookiesOfTheLiveDocumentAreHandedToTheHost() throws Exception {
        net.html = page("AAAA1111", "home");
        net.cookies = Collections.singletonList("visitor=1; Path=/");
        handle();
        waitIdle();
        assertEquals(Collections.singletonList("visitor=1; Path=/"), hooks.cookies);
    }

    @Test public void clearRemovesEveryVersion() throws Exception {
        net.html = page("AAAA1111", "home");
        handle();
        waitIdle();
        store.clear();
        assertNull(store.newest(SRC, "root"));
        assertTrue(store.pinnedUrls().isEmpty());
    }

    @Test public void snapshotsSurviveARestart() throws Exception {
        net.html = page("AAAA1111", "home v1");
        handle();
        waitIdle();
        ShellStore reopened = new ShellStore(new File(tmp.getRoot(), "shell"));
        ShellSnapshot s = reopened.newestGood(SRC, "root");
        assertNotNull(s);
        assertEquals("AAAA1111", s.buildId);
        assertTrue(new String(reopened.readBody(s), StandardCharsets.UTF_8).contains("home v1"));
        assertEquals(s.assets, new ArrayList<>(reopened.pinnedUrls()));
    }

    // ---------- fakes ----------

    static final class Recorder implements ShellEngine.Hooks {
        volatile Set<String> lastPins = Collections.emptySet();
        final List<String> updates = Collections.synchronizedList(new ArrayList<>());
        final List<String> prefetched = Collections.synchronizedList(new ArrayList<>());
        volatile List<String> cookies = Collections.emptyList();

        @Override public void onPins(Set<String> urls) {
            lastPins = urls;
        }

        @Override public void prefetch(List<String> urls, Map<String, String> h, Runnable done) {
            prefetched.addAll(urls);
            done.run();
        }

        @Override public void onUpdateReady(String source, String url) {
            updates.add(source);
        }

        @Override public void onSetCookies(String url, List<String> setCookies) {
            cookies = setCookies;
        }

        @Override public void log(String line) {
            // not asserted
        }
    }

    static final class FakeFetcher implements ShellFetcher {
        volatile String html = "";
        volatile int status = 200;
        volatile boolean offline;
        volatile boolean notModified;
        volatile CountDownLatch gate;
        volatile List<String> cookies = Collections.emptyList();

        @Override
        public Result fetch(String url, Map<String, String> req, String inm, String ims) throws IOException {
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            if (offline) throw new IOException("offline");
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "text/html; charset=utf-8");
            h.put("ETag", "\"" + Integer.toHexString(html.hashCode()) + "\"");
            if (notModified) return new Result(304, h, new byte[0], cookies);
            return new Result(status, h, html.getBytes(StandardCharsets.UTF_8), cookies);
        }
    }
}
