package app.movy.shell;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Android face of the local site shell (TV): answers main-frame requests of shell routes (home and the top sections of a
 * source) from the newest stored HTML snapshot while the live site is checked in the background. See {@link ShellEngine}.
 */
public final class ShellInterceptor {
    /** Told (on a background thread) when a new build of a site is stored and its assets are cached. */
    public interface Listener {
        void onShellUpdateReady(String sourceId, String url);
    }

    private static final String DIR = "movy-shell";
    private static final String TAG = "MovyShell";
    private static final long BOOT_WINDOW_MS = 30_000;

    private final SourceRepository repo;
    private final AssetCacheInterceptor assets;
    private final ShellStore store;
    private final ShellEngine engine;
    private final boolean debug;
    private final Map<String, Object[]> specs = new HashMap<>(); // source id -> {profile json, ShellSpec}
    private volatile ShellSnapshot lastServed;
    private volatile long lastServedAt;
    private volatile String userAgent;

    private ShellInterceptor(Context ctx, SourceRepository repo, AssetCacheInterceptor assets, boolean debug, Listener listener) {
        this.repo = repo;
        this.assets = assets;
        this.debug = debug;
        this.store = new ShellStore(new File(ctx.getCacheDir(), DIR));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "movy-shell");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        ExecutorService exec = pool;
        this.engine = new ShellEngine(store, new ShellFetcher.Http(), System::currentTimeMillis, exec, new ShellEngine.Hooks() {
            @Override public void onPins(Set<String> urls) {
                if (ShellInterceptor.this.assets != null) ShellInterceptor.this.assets.setPinned(urls);
            }

            @Override public void prefetch(List<String> urls, Map<String, String> requestHeaders, Runnable done) {
                if (ShellInterceptor.this.assets == null) {
                    done.run();
                    return;
                }
                ShellInterceptor.this.assets.prefetch(urls, requestHeaders, done);
            }

            @Override public void onUpdateReady(String source, String url) {
                // Only a page that was served from an older snapshot needs the new build; a page loaded live already has it.
                ShellSnapshot shown = lastServed;
                if (listener != null && shown != null && shown.source.equals(source)) listener.onShellUpdateReady(source, url);
            }

            @Override public void onSetCookies(String url, List<String> setCookies) {
                try {
                    CookieManager cm = CookieManager.getInstance();
                    for (String c : setCookies) cm.setCookie(url, c);
                } catch (RuntimeException ignored) {
                    // cookies are best effort here: the page sets its own as soon as it runs
                }
            }

            @Override public void log(String line) {
                if (ShellInterceptor.this.debug) Log.i(TAG, line);
            }
        });
        exec.execute(() -> {
            if (assets != null) assets.setPinned(store.pinnedUrls()); // pins of the versions kept on disk, before anything is evicted
        });
    }

    /** Null unless the asset cache exists (the snapshots' assets live there). */
    public static ShellInterceptor create(Context ctx, SourceRepository repo, AssetCacheInterceptor assets, boolean debug, Listener l) {
        return assets == null ? null : new ShellInterceptor(ctx, repo, assets, debug, l);
    }

    private ShellSpec specFor(Source s) {
        String json = repo.config().tvProfileFor(s.id);
        synchronized (specs) {
            Object[] hit = specs.get(s.id);
            if (hit != null && hit[0].equals(json)) return (ShellSpec) hit[1];
            ShellSpec spec = ShellSpec.parse(json);
            specs.put(s.id, new Object[] {json, spec});
            return spec;
        }
    }

    private Map<String, String> requestHeaders(String url, Map<String, String> fromRequest) {
        Map<String, String> h = new LinkedHashMap<>();
        if (fromRequest != null) h.putAll(fromRequest);
        String ua = CachePolicy.header(h, "User-Agent");
        if (ua != null) userAgent = ua;
        else if (userAgent != null) h.put("User-Agent", userAgent);
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        } catch (RuntimeException ignored) {
            // no cookie manager yet: the conditional fetch simply goes without
        }
        return h;
    }

    /** A snapshot response for a shell route, or null (the WebView loads the page itself). Called on a WebView IO thread. */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (!request.isForMainFrame() || !"GET".equalsIgnoreCase(request.getMethod())) return null;
        String url = request.getUrl().toString();
        Source source = repo.config().sourceForUrl(url);
        if (source == null) return null;
        ShellSpec spec = specFor(source);
        if (spec == null || !ShellPolicy.isShellRoute(spec.routes, url)) return null;
        ShellEngine.Answer a = engine.handle(source.id, spec, url, requestHeaders(url, request.getRequestHeaders()));
        if (a == null) {
            lastServed = null; // the live page is loading
            return null;
        }
        lastServed = a.snapshot;
        lastServedAt = System.currentTimeMillis();
        String type = a.snapshot.contentType == null ? "text/html" : a.snapshot.contentType;
        String mime = type.indexOf(';') >= 0 ? type.substring(0, type.indexOf(';')).trim() : type;
        Map<String, String> h = new HashMap<>();
        if (a.snapshot.csp != null) h.put("Content-Security-Policy", a.snapshot.csp);
        return new WebResourceResponse(mime, "utf-8", 200, "OK", h, new ByteArrayInputStream(a.body));
    }

    /** Background refresh of every shell route of a source (other sources while the preloader warms them). */
    public void refreshSource(Source s) {
        ShellSpec spec = specFor(s);
        if (spec == null) return;
        String origin = originOf(s.url);
        if (origin == null) return;
        engine.refreshSource(s.id, spec, origin, requestHeaders(origin + "/", null));
    }

    private static String originOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The page's own boot check (tvshell.js): a snapshot that was just served and did not render is never served again. */
    public boolean onBoot(boolean ok) {
        ShellSnapshot s = lastServed;
        if (ok || s == null || System.currentTimeMillis() - lastServedAt > BOOT_WINDOW_MS) return false;
        lastServed = null;
        engine.markBad(s);
        return true;
    }

    /** True when {@code url} is one of the shell routes of its source. */
    public boolean isShellUrl(String url) {
        Source source = url == null ? null : repo.config().sourceForUrl(url);
        ShellSpec spec = source == null ? null : specFor(source);
        return spec != null && ShellPolicy.isShellRoute(spec.routes, url);
    }

    public void releaseMemory() {
        store.releaseMemory();
    }

    /** Settings, Clear Cache. */
    public void clear() {
        store.clear();
        if (assets != null) assets.setPinned(java.util.Collections.emptySet());
    }
}
