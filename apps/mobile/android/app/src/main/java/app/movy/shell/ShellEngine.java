package app.movy.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Local-first loading of shell routes. {@link #handle} answers a main-frame request from the newest good snapshot while a
 * conditional request asks the live site in the background: unchanged or too slow means the snapshot is served, a change
 * means the WebView loads the live page itself and the new version is stored (assets prefetched and pinned). The caller
 * never blocks longer than the race window (longer only when the snapshot is stale or just failed its boot check).
 * Android-free: the fetcher, clock, executor and hooks are injected.
 */
final class ShellEngine {
    /** Side effects the engine asks its host for. */
    interface Hooks {
        /** Asset URLs that must survive cache eviction (assets of every retained good version). */
        void onPins(Set<String> urls);

        /** Fetch these assets into the asset cache, then run {@code done} (also when some failed). */
        void prefetch(List<String> urls, Map<String, String> requestHeaders, Runnable done);

        /** A new build of the site is stored and its assets are cached. */
        void onUpdateReady(String source, String url);

        /** The live site set cookies on its document: the WebView must have them (the snapshot never replays them itself). */
        void onSetCookies(String url, List<String> setCookies);

        void log(String line);
    }

    /** A snapshot to serve. */
    static final class Answer {
        final ShellSnapshot snapshot;
        final byte[] body;
        final ShellPolicy.Live live;

        Answer(ShellSnapshot snapshot, byte[] body, ShellPolicy.Live live) {
            this.snapshot = snapshot;
            this.body = body;
            this.live = live;
        }
    }

    private static final long BYPASS_MS = 60_000;

    private final ShellStore store;
    private final ShellFetcher fetcher;
    private final LongSupplier clock;
    private final ExecutorService exec;
    private final Hooks hooks;
    private final Map<String, Future<ShellPolicy.Live>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> bypassUntil = new ConcurrentHashMap<>();

    ShellEngine(ShellStore store, ShellFetcher fetcher, LongSupplier clock, ExecutorService exec, Hooks hooks) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
        this.exec = exec;
        this.hooks = hooks;
    }

    /** The snapshot to answer with, or null to let the WebView load the page itself. */
    Answer handle(String source, ShellSpec spec, String url, Map<String, String> req) {
        String path = ShellPolicy.pathOf(url);
        if (path == null) return null;
        String key = ShellPolicy.routeKey(path);
        long now = clock.getAsLong();
        ShellSnapshot local = store.newestGood(source, key);
        boolean stale = local != null && ShellPolicy.isStale(local.fetchedAt, now, spec.maxAgeMs);
        boolean bypass = isBypassed(source, key, now);
        Future<ShellPolicy.Live> f = refreshAsync(source, spec, key, url, req);
        if (local == null) return null; // nothing local yet: the live page loads normally and is captured meanwhile
        ShellPolicy.Live live = await(f, ShellPolicy.waitMs(stale, bypass, spec.raceMs));
        if (ShellPolicy.decide(true, stale, bypass, live) == ShellPolicy.Action.PASS_THROUGH) {
            hooks.log("pass-through " + source + " v" + local.tag + " live=" + live);
            return null;
        }
        try {
            byte[] body = store.readBody(local);
            hooks.log("served " + source + " v" + local.tag + " age=" + (now - local.fetchedAt) / 60000 + "min race=" + live
                    + " build=" + local.buildId);
            return new Answer(local, body, live);
        } catch (IOException e) {
            markBad(local); // body missing or unreadable
            return null;
        }
    }

    /** Background refresh of every route of a source (preloader, first launch). */
    void refreshSource(String source, ShellSpec spec, String origin, Map<String, String> req) {
        for (String route : spec.routes) {
            String url = origin + route;
            refreshAsync(source, spec, ShellPolicy.routeKey(ShellPolicy.pathOf(url)), url, req);
        }
    }

    /** The boot check of a served snapshot failed: never serve it again; the next load prefers live. */
    void markBad(ShellSnapshot s) {
        try {
            store.update(s.markedBad());
        } catch (IOException e) {
            hooks.log("cannot mark bad: " + e.getMessage());
        }
        bypassUntil.put(s.source + "/" + s.routeKey, clock.getAsLong() + BYPASS_MS);
        hooks.onPins(store.pinnedUrls());
        hooks.log("bad " + s.source + " v" + s.tag);
    }

    private boolean isBypassed(String source, String key, long now) {
        Long until = bypassUntil.get(source + "/" + key);
        return until != null && until > now;
    }

    private Future<ShellPolicy.Live> refreshAsync(String source, ShellSpec spec, String key, String url, Map<String, String> req) {
        String flightKey = source + "/" + key;
        Future<ShellPolicy.Live> existing = inFlight.get(flightKey);
        if (existing != null && !existing.isDone()) return existing;
        Future<ShellPolicy.Live> f = exec.submit(() -> {
            try {
                return fetchAndConsider(source, spec, key, url, req);
            } finally {
                inFlight.remove(flightKey);
            }
        });
        inFlight.put(flightKey, f);
        return f;
    }

    private static ShellPolicy.Live await(Future<ShellPolicy.Live> f, long waitMs) {
        try {
            return f.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ShellPolicy.Live.PENDING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ShellPolicy.Live.FAILED;
        } catch (ExecutionException e) {
            return ShellPolicy.Live.FAILED;
        }
    }

    /** Asks the live site and stores what it says. Runs on the executor, never on a WebView thread. */
    private ShellPolicy.Live fetchAndConsider(String source, ShellSpec spec, String key, String url, Map<String, String> req) {
        ShellSnapshot cur = store.newestGood(source, key);
        long now = clock.getAsLong();
        ShellFetcher.Result r;
        try {
            r = fetcher.fetch(url, req, cur == null ? null : cur.etag, cur == null ? null : cur.lastModified);
        } catch (IOException | RuntimeException e) {
            return ShellPolicy.Live.FAILED;
        }
        if (!r.setCookies.isEmpty() && (r.status == 200 || r.status == 304)) hooks.onSetCookies(url, r.setCookies);
        try {
            if (r.status == 304 && cur != null) {
                store.update(cur.verified(now, null, null));
                return ShellPolicy.Live.UNCHANGED;
            }
            String html = new String(r.body, StandardCharsets.UTF_8);
            ShellPolicy.Verdict v = ShellPolicy.judge(r.status, r.header("content-type"), r.body, html, spec.roots);
            if (v != ShellPolicy.Verdict.OK) {
                hooks.log("not snapshotted " + source + ": " + v);
                return ShellPolicy.Live.FAILED;
            }
            String h = ShellPolicy.hash(ShellPolicy.normalise(html, spec.volatilePatterns));
            if (cur != null && cur.hash.equals(h)) {
                store.update(cur.verified(now, r.header("etag"), r.header("last-modified")));
                return ShellPolicy.Live.UNCHANGED;
            }
            List<String> assets = ShellPolicy.extractAssets(html, url);
            String build = ShellPolicy.buildId(html, assets);
            ShellSnapshot newest = store.newest(source, key);
            int tag = newest == null ? 1 : newest.tag + 1;
            ShellSnapshot snap = new ShellSnapshot(source, key, tag, h, build, now, url, r.header("etag"), r.header("last-modified"), 200,
                    r.header("content-type"), r.header("content-security-policy"), false, assets);
            store.push(snap, r.body, spec.keep);
            hooks.onPins(store.pinnedUrls());
            final boolean deploy = cur != null && !cur.buildId.equals(build);
            hooks.log("stored " + source + " v" + tag + " changed=y build=" + (deploy ? "new" : "same") + " assets=" + assets.size());
            hooks.prefetch(assets, req, () -> {
                if (deploy) hooks.onUpdateReady(source, url);
            });
            return ShellPolicy.Live.CHANGED;
        } catch (IOException | RuntimeException e) {
            hooks.log("store failed: " + e.getMessage());
            return ShellPolicy.Live.FAILED;
        }
    }

    /** Test hook: waits for background refreshes of a source's route to finish. */
    boolean idle() {
        for (Future<ShellPolicy.Live> f : inFlight.values()) if (!f.isDone()) return false;
        return true;
    }

    Collection<String> inFlightKeys() {
        return inFlight.keySet();
    }
}
