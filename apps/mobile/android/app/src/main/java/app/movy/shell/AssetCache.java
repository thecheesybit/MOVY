package app.movy.shell;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Disk cache for images, fonts and versioned static files (TV). Hits are served instantly from disk. A miss is never
 * fetched on the caller's (WebView intercept) thread: it returns null so Chromium loads the resource at full speed, and a
 * bounded background pool fetches and stores it for next time. Entries are revalidated in the background at most once per
 * 24 h, deleted 30 days after they were first stored, and evicted LRU above the size cap. Writes are atomic (temp file,
 * then rename); a corrupt entry is dropped. Rules live in {@link CachePolicy}.
 */
final class AssetCache {
    /** A servable response read from disk. */
    static final class Entry {
        final File body;
        final String mime;
        final Map<String, String> headers;
        final long size;

        Entry(File body, String mime, Map<String, String> headers, long size) {
            this.body = body;
            this.mime = mime;
            this.headers = headers;
            this.size = size;
        }

        InputStream open() throws IOException {
            return new FileInputStream(body);
        }
    }

    private static final String[] KEEP = {"Content-Type", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
        "Access-Control-Expose-Headers", "Timing-Allow-Origin", "Cross-Origin-Resource-Policy"};
    private static final long RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000;
    private static final int LOCK_STRIPES = 64;
    private static final int FILL_THREADS = 2;
    private static final int FILL_QUEUE = 512;
    private static final int NEGATIVE_MAX = 2048;

    private final File dir;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final long maxBytes;
    private final long ttlMs;
    private final Executor fillExecutor;
    private final CacheIndex index = new CacheIndex();
    private final Object[] stripes = new Object[LOCK_STRIPES];
    private final Set<String> revalidating = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> filling = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Keys whose last fill failed or was not cacheable: not retried for an hour (bounded, oldest forgotten first). */
    private final Map<String, Long> negative = new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > NEGATIVE_MAX;
        }
    };
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final AtomicLong fillBytes = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "movy-asset-cache");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastSweep;
    private volatile boolean indexDropped;
    /** Keys of assets that a retained shell snapshot needs: exempt from expiry, LRU eviction and revalidation eviction. */
    private volatile Set<String> pinned = Collections.emptySet();

    AssetCache(File dir, Fetcher fetcher, LongSupplier clock, long maxBytes, long ttlMs) {
        this(dir, fetcher, clock, maxBytes, ttlMs, defaultFillExecutor());
    }

    AssetCache(File dir, Fetcher fetcher, LongSupplier clock, long maxBytes, long ttlMs, Executor fillExecutor) {
        this.dir = dir;
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        this.fillExecutor = fillExecutor;
        for (int i = 0; i < LOCK_STRIPES; i++) stripes[i] = new Object();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Two low-priority threads that die when idle, with a bounded queue (a full queue drops new fills). */
    private static Executor defaultFillExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(FILL_THREADS, FILL_THREADS, 20, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(FILL_QUEUE), r -> {
                    Thread t = new Thread(r, "movy-asset-fill");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private Object lockFor(String key) {
        return stripes[(key.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    /** Replaces the pinned set (URLs of the assets of every retained good shell snapshot). */
    void setPinned(java.util.Collection<String> urls) {
        Set<String> keys = new HashSet<>();
        for (String u : urls) keys.add(keyOf(u));
        pinned = Collections.unmodifiableSet(keys);
    }

    int pinnedCount() {
        return pinned.size();
    }

    boolean isPinned(String url) {
        return pinned.contains(keyOf(url));
    }

    boolean has(String url) {
        return index.has(keyOf(url));
    }

    /**
     * Fetches the given assets into the cache in the background (those that are cacheable and not stored yet) and runs
     * {@code done} once every one of them has been tried. Skipped (uncacheable) URLs count as done.
     */
    void prefetch(java.util.Collection<String> urls, Map<String, String> requestHeaders, Runnable done) {
        final Map<String, String> headers = new LinkedHashMap<>();
        for (String k : new String[] {"User-Agent", "Accept-Language", "Referer"}) {
            String v = CachePolicy.header(requestHeaders, k);
            if (v != null) headers.put(k, v);
        }
        headers.put("Accept", "*/*");
        final java.util.List<String[]> todo = new java.util.ArrayList<>();
        for (String u : urls) {
            if (CachePolicy.classifyRequest("GET", u, headers) != CachePolicy.Kind.NONE && !has(u)) todo.add(new String[] {u});
        }
        if (todo.isEmpty()) {
            if (done != null) done.run();
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(todo.size());
        for (String[] t : todo) {
            final String url = t[0];
            final CachePolicy.Kind kind = CachePolicy.classifyRequest("GET", url, headers);
            try {
                fillExecutor.execute(() -> {
                    try {
                        fill(kind, keyOf(url), url, headers);
                    } finally {
                        if (left.decrementAndGet() == 0 && done != null) done.run();
                    }
                });
            } catch (RejectedExecutionException full) {
                if (left.decrementAndGet() == 0 && done != null) done.run();
            }
        }
    }

    /** Low memory: forget the in-memory index; it is rebuilt from disk on the next use. */
    void releaseMemory() {
        index.clear();
        synchronized (negative) {
            negative.clear();
        }
        indexDropped = true;
    }

    long sizeBytes() {
        return index.totalBytes();
    }

    int entryCount() {
        return index.count();
    }

    /** One line for logcat: hits, misses (cacheable requests not on disk), completed fills and bytes, current size. */
    String statsLine() {
        return "hits=" + hits.get() + " misses=" + misses.get() + " fills=" + fills.get() + " fillBytes=" + fillBytes.get()
                + " rejected=" + rejected.get() + " entries=" + index.count() + " bytes=" + index.totalBytes();
    }

    long hitCount() {
        return hits.get();
    }

    long missCount() {
        return misses.get();
    }

    long fillCount() {
        return fills.get();
    }

    // ---------- serving ----------

    /**
     * The cached response, or null (not cacheable, or not on disk yet). A cacheable miss schedules one background fill and
     * returns at once, so the WebView is never slowed down by the cache.
     */
    Entry serve(String method, String url, Map<String, String> reqHeaders) {
        CachePolicy.Kind kind = CachePolicy.classifyRequest(method, url, reqHeaders);
        if (kind == CachePolicy.Kind.NONE) return null;
        if (indexDropped) {
            indexDropped = false;
            scanAsync();
        }
        String key = keyOf(url);
        long now = clock.getAsLong();
        Entry e;
        synchronized (lockFor(key)) {
            e = readEntry(key, now);
            if (e != null) {
                index.touch(key, now);
                if (CachePolicy.needsRevalidate(validatedAt(key), now)) scheduleRevalidate(kind, key, url, reqHeaders);
            }
        }
        if (e == null) {
            misses.incrementAndGet();
            scheduleFill(kind, key, url, reqHeaders, now);
            return null;
        }
        hits.incrementAndGet();
        return withCors(e, reqHeaders);
    }

    /** Fonts and images requested in CORS mode work even when the stored copy came from a no-CORS request. */
    private static Entry withCors(Entry e, Map<String, String> req) {
        String origin = CachePolicy.header(req, "origin");
        if (origin == null || CachePolicy.header(e.headers, "access-control-allow-origin") != null) return e;
        Map<String, String> h = new LinkedHashMap<>(e.headers);
        h.put("Access-Control-Allow-Origin", origin);
        h.put("Vary", "Origin");
        return new Entry(e.body, e.mime, h, e.size);
    }

    /** Index first (no disk read for the meta); the meta file is parsed only when the index does not hold the entry. */
    private Entry readEntry(String key, long now) {
        CacheIndex.Info known = index.get(key);
        File body = bodyFile(key);
        if (known != null && known.hasMeta()) {
            if (body.length() != known.size) { drop(key); return null; }
            if (CachePolicy.isExpired(known.storedAt, now, ttlMs) && !pinned.contains(key)) { drop(key); return null; }
            return new Entry(body, known.mime, known.headers, known.size);
        }
        File meta = metaFile(key);
        if (!meta.isFile() || !body.isFile()) {
            if (meta.exists() || body.exists()) drop(key);
            return null;
        }
        try {
            JSONObject m = new JSONObject(readAll(meta));
            long storedAt = m.getLong("storedAt");
            long size = m.getLong("size");
            if (body.length() != size) { drop(key); return null; }
            if (CachePolicy.isExpired(storedAt, now, ttlMs) && !pinned.contains(key)) { drop(key); return null; }
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject hs = m.optJSONObject("headers");
            if (hs != null) {
                java.util.Iterator<String> it = hs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    headers.put(k, hs.getString(k));
                }
            }
            String mime = mimeOf(headers);
            index.put(key, size, storedAt, m.optLong("validatedAt", 0), now, mime, headers);
            return new Entry(body, mime, index.get(key).headers, size);
        } catch (JSONException | IOException ex) {
            drop(key); // corrupt entry
            return null;
        }
    }

    private long validatedAt(String key) {
        CacheIndex.Info i = index.get(key);
        if (i != null) return i.validatedAt;
        try {
            return new JSONObject(readAll(metaFile(key))).optLong("validatedAt", 0);
        } catch (JSONException | IOException e) {
            return 0;
        }
    }

    // ---------- storing (background fill) ----------

    private void scheduleFill(CachePolicy.Kind kind, String key, String url, Map<String, String> req, long now) {
        if (isNegative(key, now) || !filling.add(key)) return;
        final Map<String, String> headers = new LinkedHashMap<>(req);
        try {
            fillExecutor.execute(() -> {
                try {
                    fill(kind, key, url, headers);
                } finally {
                    filling.remove(key);
                }
            });
        } catch (RejectedExecutionException full) {
            filling.remove(key); // queue full: the next miss tries again
        }
    }

    private boolean isNegative(String key, long now) {
        synchronized (negative) {
            Long until = negative.get(key);
            if (until == null) return false;
            if (now >= until) {
                negative.remove(key);
                return false;
            }
            return true;
        }
    }

    private void markNegative(String key) {
        rejected.incrementAndGet();
        synchronized (negative) {
            negative.put(key, clock.getAsLong() + RETRY_AFTER_FAILURE_MS);
        }
    }

    /** Fetches and stores one resource (runs on the fill pool). A failed or uncacheable response is remembered and skipped. */
    void fill(CachePolicy.Kind kind, String key, String url, Map<String, String> req) {
        synchronized (lockFor(key)) {
            if (index.has(key)) return; // another fill or a scan got there first
        }
        Fetcher.Result r = null;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs(); // Settings, Clear Cache may have removed the directory
        File tmp = new File(dir, key + ".t" + Long.toHexString(System.nanoTime()));
        try {
            r = fetcher.fetch(url, req, null, null);
            if (r.status != 200 || r.body == null) { markNegative(key); return; }
            long size = copyLimited(r.body, tmp);
            if (size < 0 || !CachePolicy.cacheableResponse(kind, r.status, r.headers, size)) { markNegative(key); return; }
            long now = clock.getAsLong();
            synchronized (lockFor(key)) {
                commit(key, url, tmp, size, r.headers, keep(r.headers), now, now);
            }
            fills.incrementAndGet();
            fillBytes.addAndGet(size);
            enforceCap();
        } catch (IOException | RuntimeException e) {
            markNegative(key);
        } finally {
            if (r != null) r.close();
            delete(tmp); // already gone after a successful rename
        }
    }

    /** Moves the temp body into place and writes the meta (meta last: an entry exists only once complete). */
    private void commit(String key, String url, File tmp, long size, Map<String, String> all, Map<String, String> kept,
                        long storedAt, long validatedAt) throws IOException {
        File body = bodyFile(key);
        delete(body);
        if (!tmp.renameTo(body)) throw new IOException("rename failed");
        try {
            JSONObject m = new JSONObject();
            m.put("url", url);
            m.put("size", size);
            m.put("storedAt", storedAt);
            m.put("validatedAt", validatedAt);
            String etag = CachePolicy.header(all, "etag");
            String lm = CachePolicy.header(all, "last-modified");
            if (etag != null) m.put("etag", etag);
            if (lm != null) m.put("lastModified", lm);
            JSONObject hs = new JSONObject();
            for (Map.Entry<String, String> e : kept.entrySet()) hs.put(e.getKey(), e.getValue());
            m.put("headers", hs);
            File mt = new File(dir, key + ".m.tmp");
            writeAll(mt, m.toString());
            File meta = metaFile(key);
            delete(meta);
            if (!mt.renameTo(meta)) throw new IOException("meta rename failed");
        } catch (JSONException e) {
            drop(key);
            throw new IOException(e);
        }
        index.put(key, size, storedAt, validatedAt, clock.getAsLong(), mimeOf(kept), kept);
    }

    private static Map<String, String> keep(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : KEEP) {
            String v = CachePolicy.header(all, k);
            if (v != null) out.put(k, v); // Set-Cookie and everything else is dropped, never re-emitted
        }
        return out;
    }

    private static String mimeOf(Map<String, String> headers) {
        String ct = CachePolicy.header(headers, "content-type");
        if (ct == null) return "application/octet-stream";
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
    }

    /** Copies up to MAX_ENTRY_BYTES; returns the size, or -1 when the body is larger. */
    private static long copyLimited(InputStream in, File to) throws IOException {
        long total = 0;
        byte[] buf = new byte[16 * 1024];
        try (OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > CachePolicy.MAX_ENTRY_BYTES) return -1;
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    // ---------- revalidation ----------

    private void scheduleRevalidate(CachePolicy.Kind kind, String key, String url, Map<String, String> req) {
        if (!revalidating.add(key)) return;
        final Map<String, String> headers = new LinkedHashMap<>(req);
        worker.execute(() -> {
            try {
                revalidate(kind, key, url, headers);
            } finally {
                revalidating.remove(key);
            }
        });
    }

    /** Conditional GET: 304 refreshes the timestamp, 200 replaces the body, 404/410 evicts, anything else retries later. */
    void revalidate(CachePolicy.Kind kind, String key, String url, Map<String, String> req) {
        JSONObject meta;
        try {
            meta = new JSONObject(readAll(metaFile(key)));
        } catch (JSONException | IOException e) {
            return;
        }
        long now = clock.getAsLong();
        Fetcher.Result r = null;
        File tmp = new File(dir, key + ".t" + Long.toHexString(System.nanoTime()));
        try {
            r = fetcher.fetch(url, req, meta.optString("etag", null), meta.optString("lastModified", null));
            switch (CachePolicy.onRevalidated(r.status)) {
                case KEEP_AND_REFRESH:
                    updateValidated(key, meta, now);
                    break;
                case EVICT:
                    if (!pinned.contains(key)) { // an old snapshot still needs it even though the site removed it
                        synchronized (lockFor(key)) { drop(key); }
                    }
                    break;
                case REPLACE: {
                    long size = r.body == null ? -1 : copyLimited(r.body, tmp);
                    if (size > 0 && CachePolicy.cacheableResponse(kind, r.status, r.headers, size)) {
                        synchronized (lockFor(key)) {
                            commit(key, url, tmp, size, r.headers, keep(r.headers), meta.optLong("storedAt", now), now);
                        }
                        enforceCap();
                    } else {
                        synchronized (lockFor(key)) { drop(key); }
                    }
                    break;
                }
                default:
                    updateValidated(key, meta, now - CachePolicy.REVALIDATE_MS + RETRY_AFTER_FAILURE_MS);
                    break;
            }
        } catch (IOException | RuntimeException e) {
            updateValidated(key, meta, now - CachePolicy.REVALIDATE_MS + RETRY_AFTER_FAILURE_MS);
        } finally {
            if (r != null) r.close();
            delete(tmp);
        }
    }

    private void updateValidated(String key, JSONObject meta, long when) {
        try {
            meta.put("validatedAt", when);
            File mt = new File(dir, key + ".m.tmp");
            writeAll(mt, meta.toString());
            File m = metaFile(key);
            delete(m);
            //noinspection ResultOfMethodCallIgnored
            mt.renameTo(m);
            index.setValidated(key, when);
        } catch (JSONException | IOException ignored) {
            // the next serve revalidates again
        }
    }

    // ---------- housekeeping ----------

    /** Rebuilds the index from disk and removes expired, orphaned and half-written files. Call off the main thread. */
    void scan() {
        long now = clock.getAsLong();
        index.clear();
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> metas = new HashSet<>();
        for (File f : files) {
            String n = f.getName();
            if (n.contains(".t") && !n.endsWith(".m") && !n.endsWith(".b")) { delete(f); continue; }
            if (n.endsWith(".m.tmp")) { delete(f); continue; }
            if (n.endsWith(".m")) metas.add(n.substring(0, n.length() - 2));
        }
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".b") && !metas.contains(n.substring(0, n.length() - 2))) delete(f);
        }
        for (String key : metas) {
            synchronized (lockFor(key)) { readEntry(key, now); }
        }
        sweep();
    }

    /** Deletes expired entries, then evicts least recently used ones above the cap. */
    void sweep() {
        long now = clock.getAsLong();
        lastSweep = now;
        for (String k : index.expired(now, ttlMs, pinned)) drop(k);
        enforceCap();
    }

    void sweepIfDue() {
        if (clock.getAsLong() - lastSweep >= CachePolicy.DAY_MS) worker.execute(this::sweep);
    }

    void scanAsync() {
        worker.execute(this::scan);
    }

    private void enforceCap() {
        List<String> victims = index.overflow(maxBytes, pinned);
        for (String k : victims) drop(k);
    }

    /** Removes everything (Settings, Clear Cache). */
    void clear() {
        index.clear();
        synchronized (negative) {
            negative.clear();
        }
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) delete(f);
    }

    private void drop(String key) {
        index.remove(key);
        delete(bodyFile(key));
        delete(metaFile(key));
    }

    // ---------- files ----------

    static String keyOf(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private File bodyFile(String key) {
        return new File(dir, key + ".b");
    }

    private File metaFile(String key) {
        return new File(dir, key + ".m");
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
    }

    private static String readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeAll(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
