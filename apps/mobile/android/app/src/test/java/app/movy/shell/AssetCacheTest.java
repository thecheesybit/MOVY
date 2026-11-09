package app.movy.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AssetCacheTest {
    private static final long DAY = CachePolicy.DAY_MS;
    private static final String IMG = "https://cdn.example.com/p/a.jpg";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] now = {1_000_000L};
    private FakeFetcher net;
    private ManualExecutor fills;
    private File dir;

    @Before public void setUp() throws IOException {
        net = new FakeFetcher();
        fills = new ManualExecutor();
        dir = tmp.newFolder("assets");
    }

    private AssetCache cache(long maxBytes) {
        return new AssetCache(dir, net, () -> now[0], maxBytes, 30 * DAY, fills);
    }

    private static Map<String, String> req() {
        Map<String, String> m = new HashMap<>();
        m.put("User-Agent", "test");
        return m;
    }

    private static byte[] read(AssetCache.Entry e) throws IOException {
        try (InputStream in = e.open()) {
            return in.readAllBytes();
        }
    }

    /** serve(); on a miss lets the background fill run, then serves again (the second call is the one a later page load makes). */
    private AssetCache.Entry get(AssetCache c, String url, Map<String, String> headers) {
        AssetCache.Entry e = c.serve("GET", url, headers);
        if (e != null) return e;
        fills.runAll();
        return c.serve("GET", url, headers);
    }

    private AssetCache.Entry get(AssetCache c, String url) {
        return get(c, url, req());
    }

    @Test public void missReturnsNullAtOnceAndSchedulesExactlyOneFill() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        assertNull("a miss never blocks on the network", c.serve("GET", IMG, req()));
        assertEquals(0, net.calls.size());
        assertEquals(1, fills.pending());
        assertNull(c.serve("GET", IMG, req()));
        assertEquals("the same resource in flight is not queued twice", 1, fills.pending());
        assertEquals(2, c.missCount());
    }

    @Test public void fillStoresAndTheNextRequestIsServedFromDisk() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        c.serve("GET", IMG, req());
        fills.runAll();
        assertEquals(1, net.calls.size());
        assertEquals(1, c.fillCount());
        AssetCache.Entry hit = c.serve("GET", IMG, req());
        assertNotNull(hit);
        assertEquals("image/jpeg", hit.mime);
        assertArrayEquals("AAAA".getBytes(), read(hit));
        assertEquals("a hit does not touch the network", 1, net.calls.size());
        assertEquals(0, fills.pending());
        assertEquals(1, c.hitCount());
        assertEquals(4, c.sizeBytes());
    }

    @Test public void hitsComeFromTheIndexNotFromTheMetaFile() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG);
        assertTrue(new File(dir, AssetCache.keyOf(IMG) + ".m").delete());
        AssetCache.Entry e = c.serve("GET", IMG, req());
        assertNotNull("meta is held in memory", e);
        assertEquals("image/jpeg", e.mime);
        assertArrayEquals("AAAA".getBytes(), read(e));
    }

    @Test public void nonCacheableRequestsAreNotHandled() {
        AssetCache c = cache(10_000_000);
        assertNull(c.serve("POST", IMG, req()));
        assertNull(c.serve("GET", "https://a.example.com/api/list.json", req()));
        assertEquals(0, net.calls.size());
        assertEquals(0, fills.pending());
    }

    @Test public void mediaIsNeverFetchedOrStored() {
        AssetCache c = cache(10_000_000);
        net.put("https://a.example.com/seg/9.m4s", 200, "SEG", "video/mp4", null);
        net.put("https://a.example.com/seg/9.ts", 200, "SEG", "video/mp2t", null);
        assertNull(c.serve("GET", "https://a.example.com/seg/9.m4s", req()));
        assertNull(c.serve("GET", "https://a.example.com/seg/9.ts", req()));
        assertEquals(0, fills.pending());
        // an image-looking URL that answers with a media type is dropped after the fetch
        String odd = "https://a.example.com/p/frame.jpg";
        net.put(odd, 200, "SEG", "video/mp2t", null);
        c.serve("GET", odd, req());
        fills.runAll();
        assertEquals(0, c.entryCount());
        assertEquals(0, dir.list().length);
    }

    @Test public void noStoreResponseIsNeverKeptAndNotRetriedSoon() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        net.headers.put("Cache-Control", "no-store");
        assertNull(c.serve("GET", IMG, req()));
        fills.runAll();
        assertEquals(0, c.sizeBytes());
        assertEquals(0, dir.list().length);
        assertNull(c.serve("GET", IMG, req()));
        assertEquals("remembered as uncacheable: no second fetch", 0, fills.pending());
        assertEquals(1, net.calls.size());
        now[0] += 2 * 60 * 60 * 1000L;
        c.serve("GET", IMG, req());
        assertEquals("tried again after the retry window", 1, fills.pending());
    }

    @Test public void failedOrMissingResourcesFallBackToTheWebView() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 404, "", "text/plain", null);
        assertNull(get(c, IMG));
        net.fail = true;
        assertNull(get(c, "https://cdn.example.com/p/b.jpg"));
        assertEquals(0, c.entryCount());
    }

    @Test public void setCookieIsNeverReEmitted() {
        AssetCache c = cache(10_000_000);
        String js = "https://a.example.com/assets/index-XsMk3Q9a.js";
        net.put(js, 200, "var a=1;", "application/javascript", null);
        net.headers.put("Set-Cookie", "cf=1");
        net.headers.put("Cache-Control", "public, max-age=31536000, immutable");
        AssetCache.Entry e = get(c, js);
        assertNotNull(e);
        assertNull(CachePolicy.header(e.headers, "set-cookie"));
        assertNull(CachePolicy.header(c.serve("GET", js, req()).headers, "set-cookie"));
    }

    @Test public void corsHeadersSurviveAndAreAddedForCorsRequests() {
        AssetCache c = cache(10_000_000);
        String font = "https://cdn.example.com/f/inter.woff2";
        net.put(font, 200, "FONT", "font/woff2", null);
        net.headers.put("Access-Control-Allow-Origin", "https://a.example.com");
        AssetCache.Entry e = get(c, font);
        assertEquals("https://a.example.com", CachePolicy.header(e.headers, "access-control-allow-origin"));
        // an image first stored by a no-CORS request, later requested in CORS mode
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG);
        Map<String, String> cors = req();
        cors.put("Origin", "https://a.example.com");
        assertEquals("https://a.example.com", CachePolicy.header(c.serve("GET", IMG, cors).headers, "access-control-allow-origin"));
    }

    @Test public void expiredEntryIsDroppedAndRefilled() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "OLD", "image/png", null);
        get(c, IMG);
        now[0] += 30 * DAY;
        net.put(IMG, 200, "NEW!", "image/png", null);
        assertNull("expired: not served", c.serve("GET", IMG, req()));
        assertEquals("and refilled in the background", 1, fills.pending());
        fills.runAll();
        assertArrayEquals("NEW!".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals(2, net.calls.size());
    }

    @Test public void sweepRemovesExpiredEntries() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "OLD", "image/png", null);
        get(c, IMG);
        assertEquals(1, c.entryCount());
        now[0] += 31 * DAY;
        c.sweep();
        assertEquals(0, c.entryCount());
        assertEquals(0, dir.list().length);
    }

    @Test public void revalidation304KeepsTheEntryAndRefreshesTheClock() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        get(c, IMG);
        now[0] += 2 * DAY;
        net.status = 304;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals("\"v1\"", net.calls.get(net.calls.size() - 1).ifNoneMatch);
        net.calls.clear();
        assertArrayEquals("AAAA".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals("fresh again, no new revalidation", 0, net.calls.size());
    }

    @Test public void revalidation200ReplacesTheBodyKeepingTheFirstStoreTime() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        get(c, IMG);
        now[0] += 2 * DAY;
        net.put(IMG, 200, "BBBBBB", "image/jpeg", "\"v2\"");
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertArrayEquals("BBBBBB".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals(6, c.sizeBytes());
        now[0] += 29 * DAY; // 31 days after the first store
        net.put(IMG, 200, "CC", "image/jpeg", null);
        assertArrayEquals("CC".getBytes(), read(get(c, IMG)));
    }

    @Test public void revalidation404EvictsTheEntry() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        get(c, IMG);
        now[0] += 2 * DAY;
        net.status = 404;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals(0, c.entryCount());
    }

    @Test public void revalidationServerErrorKeepsTheEntry() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        get(c, IMG);
        now[0] += 2 * DAY;
        net.status = 503;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals(1, c.entryCount());
        assertArrayEquals("AAAA".getBytes(), read(c.serve("GET", IMG, req())));
    }

    @Test public void lruEvictsTheLeastRecentlyUsedAboveTheCap() {
        AssetCache c = cache(10);
        String a = "https://cdn.example.com/a.jpg", b = "https://cdn.example.com/b.jpg", d = "https://cdn.example.com/d.jpg";
        net.put(a, 200, "AAAA", "image/jpeg", null);
        net.put(b, 200, "BBBB", "image/jpeg", null);
        net.put(d, 200, "DDDD", "image/jpeg", null);
        get(c, a);
        get(c, b);
        c.serve("GET", a, req()); // a is now more recent than b
        get(c, d); // 12 bytes > 10: evicts b
        assertEquals(2, c.entryCount());
        assertEquals(8, c.sizeBytes());
        net.calls.clear();
        assertNotNull(c.serve("GET", a, req()));
        assertEquals("a survived", 0, net.calls.size());
        assertNull(c.serve("GET", b, req()));
        assertEquals("b was evicted and is refilled", 1, fills.pending());
    }

    @Test public void corruptEntryIsDroppedAndRefetched() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG);
        File body = new File(dir, AssetCache.keyOf(IMG) + ".b");
        java.nio.file.Files.write(body.toPath(), new byte[] {1, 2});
        net.calls.clear();
        assertArrayEquals("AAAA".getBytes(), read(get(c, IMG)));
        assertEquals(1, net.calls.size());
    }

    @Test public void scanRebuildsTheIndexAndCleansLeftovers() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG);
        new File(dir, "deadbeef.b").createNewFile(); // orphan body
        new File(dir, "cafe.t123").createNewFile(); // half-written temp file
        AssetCache again = cache(10_000_000);
        again.scan();
        assertEquals(1, again.entryCount());
        assertEquals(4, again.sizeBytes());
        assertFalse(new File(dir, "deadbeef.b").exists());
        assertFalse(new File(dir, "cafe.t123").exists());
        assertEquals("a rebuilt index serves with its meta", "image/jpeg", again.serve("GET", IMG, req()).mime);
    }

    @Test public void clearRemovesEverything() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG);
        c.clear();
        assertEquals(0, c.sizeBytes());
        assertEquals(0, dir.list().length);
    }

    @Test public void pinnedEntriesSurviveExpiryAndLruEviction() {
        AssetCache c = cache(10);
        String a = "https://cdn.example.com/a.jpg", b = "https://cdn.example.com/b.jpg", d = "https://cdn.example.com/d.jpg";
        net.put(a, 200, "AAAA", "image/jpeg", null);
        net.put(b, 200, "BBBB", "image/jpeg", null);
        net.put(d, 200, "DDDD", "image/jpeg", null);
        get(c, a);
        get(c, b);
        c.setPinned(java.util.Arrays.asList(a));
        get(c, d); // 12 bytes > 10: the least recently used would be a, but it is pinned, so b goes
        assertNotNull(c.serve("GET", a, req()));
        assertNull(c.serve("GET", b, req()));
        now[0] += 31 * DAY;
        c.sweep();
        assertNotNull("pinned: not swept after its 30 days", c.serve("GET", a, req()));
        assertNull("unpinned neighbour expired", c.serve("GET", d, req()));
        c.setPinned(java.util.Collections.emptyList());
        c.sweep();
        assertNull("released pin: expired like any other entry", c.serve("GET", a, req()));
    }

    @Test public void pinnedEntryIsKeptWhenTheSiteRemovesIt() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        get(c, IMG);
        c.setPinned(java.util.Collections.singletonList(IMG));
        now[0] += 2 * DAY;
        net.status = 404;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals("an old snapshot still needs it", 1, c.entryCount());
    }

    @Test public void prefetchFillsTheMissingCacheableAssetsThenReportsDone() {
        AssetCache c = cache(10_000_000);
        String js = "https://a.example.com/assets/index-XsMk3Q9a.js", css = "https://a.example.com/assets/app-XsMk3Q9a.css";
        net.put(js, 200, "var a=1;", "application/javascript", null);
        net.put(css, 200, "a{}", "text/css", null);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        get(c, IMG); // already cached: skipped
        net.calls.clear();
        int[] done = {0};
        c.prefetch(java.util.Arrays.asList(js, css, IMG, "https://a.example.com/api/list.json"), req(), () -> done[0]++);
        assertEquals("not before the fetches ran", 0, done[0]);
        fills.runAll();
        assertEquals(1, done[0]);
        assertEquals(2, net.calls.size());
        assertNotNull(c.serve("GET", js, req()));
        assertNotNull(c.serve("GET", css, req()));
        int[] again = {0};
        c.prefetch(java.util.Arrays.asList(js, css), req(), () -> again[0]++);
        assertEquals("nothing to fetch: done at once", 1, again[0]);
    }

    // ---------- manual fill executor ----------

    static final class ManualExecutor implements java.util.concurrent.Executor {
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override public void execute(Runnable r) {
            tasks.add(r);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            Runnable r;
            while ((r = tasks.poll()) != null) r.run();
        }
    }

    // ---------- fake network ----------

    static final class Call {
        final String url;
        final String ifNoneMatch;

        Call(String url, String ifNoneMatch) {
            this.url = url;
            this.ifNoneMatch = ifNoneMatch;
        }
    }

    static final class FakeFetcher implements Fetcher {
        final List<Call> calls = new ArrayList<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String[]> bodies = new HashMap<>();
        int status = 200;
        boolean fail;

        void put(String url, int st, String body, String type, String etag) {
            bodies.put(url, new String[] {String.valueOf(st), body, type, etag});
            headers.clear();
            status = 200;
        }

        @Override
        public Result fetch(String url, Map<String, String> req, String inm, String ims) throws IOException {
            calls.add(new Call(url, inm));
            if (fail) throw new IOException("offline");
            String[] b = bodies.get(url);
            int st = inm != null ? status : Integer.parseInt(b[0]);
            if (inm != null && status == 200) st = Integer.parseInt(b[0]);
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", b[2]);
            if (b[3] != null) h.put("ETag", b[3]);
            InputStream body = st == 200 ? new ByteArrayInputStream(b[1].getBytes()) : null;
            return new Result(st, Collections.unmodifiableMap(h), body, null);
        }
    }
}
