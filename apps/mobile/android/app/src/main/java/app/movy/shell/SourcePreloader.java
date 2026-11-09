package app.movy.shell;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import app.movy.adblock.AdblockEngine;
import app.movy.adblock.AdblockSwitch;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Warms the other sources' home pages in the background, one at a time: a detached WebView loads each start URL through
 * the same ad filter and asset cache as the visible page, so its images, fonts, scripts and styles land in the durable
 * asset cache and a later source switch is nearly instant. It never touches the visible WebView, stays on the source's own
 * hosts, never autoplays media, and is torn down after each source (or a hard timeout) so only one hidden page exists.
 * It pauses while a player is up (bandwidth) and resumes after the user has been idle on a browse page; a source warmed
 * recently (and still cached) is skipped.
 */
final class SourcePreloader {
    /** Tells the launch flow when the first-run warm-up has run through. */
    interface Listener {
        void onWarmupDone();
    }

    private static final String TAG = "MovySourcePreload";
    private static final long SETTLE_AFTER_FINISH_MS = 3000;
    private static final long HARD_TIMEOUT_MS = 20000;
    private static final int LAYOUT_W = 1280;
    private static final int LAYOUT_H = 720;
    /** Scrolls to the bottom in three steps so lazy rows request their posters. Never clicks anything. */
    private static final String SCROLL_JS = "(function(){var i=0,t=setInterval(function(){i++;try{var h=document.documentElement,"
            + "b=document.body,H=Math.max(h.scrollHeight,b?b.scrollHeight:0);window.scrollTo(0,H*i/3)}catch(e){}"
            + "if(i>=3)clearInterval(t)},300)})()";

    private final Activity activity;
    private final SourceRepository repo;
    private final Prefs prefs;
    private final AssetCacheInterceptor cache;
    private final ShellInterceptor shell;
    private final String userAgent;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Source> queue = new ArrayDeque<>();
    private final Runnable advance = this::next;
    private final Runnable resume = this::resumeNow;
    private WebView hidden;
    private Source current;
    private long currentStartedAt;
    private long fillsAtStart;
    private boolean currentWarmed;
    private boolean started;
    private boolean queueBuilt;
    private boolean paused;
    private boolean doneReported;

    SourcePreloader(Activity activity, SourceRepository repo, Prefs prefs, AssetCacheInterceptor cache, ShellInterceptor shell,
                    String userAgent, Listener listener) {
        this.activity = activity;
        this.repo = repo;
        this.prefs = prefs;
        this.cache = cache;
        this.shell = shell;
        this.userAgent = userAgent;
        this.listener = listener;
    }

    /** Call when the visible source finished loading a page; only the first call per process starts the warm-up. */
    void onActivePageFinished(boolean lowEnd) {
        if (started) return;
        started = true;
        main.postDelayed(this::begin, PreloadPolicy.startDelayMs(lowEnd));
    }

    /** First run: starts right away, in parallel with the active source. */
    void startNow() {
        if (started) return;
        started = true;
        main.postDelayed(this::begin, PreloadPolicy.FIRST_RUN_START_MS);
    }

    /** A player is starting: frees the bandwidth and the hidden page, keeping the queue (the source in flight goes first). */
    void pause() {
        paused = true;
        main.removeCallbacks(advance);
        main.removeCallbacks(resume);
        if (current != null) {
            queue.addFirst(current);
            current = null;
        }
        destroyHidden();
    }

    /** The player closed: warm-up continues once the user has been idle on a browse page for a while. */
    void resumeWhenIdle() {
        main.removeCallbacks(resume);
        main.postDelayed(resume, PreloadPolicy.RESUME_IDLE_MS);
    }

    private void resumeNow() {
        if (!paused) return;
        paused = false;
        if (!started) return;
        if (!queueBuilt) begin(); else next();
    }

    private void begin() {
        if (paused || queueBuilt) return;
        queueBuilt = true;
        Source active = repo.active();
        long now = System.currentTimeMillis();
        int cached = cache == null ? 0 : cache.entryCount();
        for (Source s : repo.config().enabledSources()) {
            if (active != null && s.id.equals(active.id)) continue;
            if (PreloadPolicy.shouldWarm(now, prefs.warmedAt(s.id), cached)) queue.add(s);
        }
        next();
    }

    private void next() {
        main.removeCallbacks(advance);
        destroyHidden();
        current = null;
        if (paused || activity.isFinishing() || activity.isDestroyed()) return;
        Source s = queue.poll();
        if (s == null) {
            reportDone();
            return;
        }
        try {
            current = s;
            currentStartedAt = SystemClock.elapsedRealtime();
            currentWarmed = false;
            fillsAtStart = cache == null ? 0 : cache.fillCount();
            hidden = create(s);
            Log.i(TAG, "warming " + s.id);
            hidden.loadUrl(s.url);
            main.postDelayed(advance, HARD_TIMEOUT_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "preload failed for " + s.id + ": " + e);
            hidden = null;
            current = null;
            main.postDelayed(advance, 500);
        }
    }

    private void reportDone() {
        if (doneReported) return;
        doneReported = true;
        if (listener != null) listener.onWarmupDone();
    }

    private WebView create(Source s) {
        WebView wv = new WebView(activity);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(true); // never autoplay a trailer nobody is watching
        ws.setAllowFileAccess(false);
        if (userAgent != null) ws.setUserAgentString(userAgent);
        wv.setWillNotDraw(true);
        // A detached WebView has no size, so lazy rows (IntersectionObserver) would never load: give it a TV-sized layout.
        wv.measure(View.MeasureSpec.makeMeasureSpec(LAYOUT_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(LAYOUT_H, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, LAYOUT_W, LAYOUT_H);
        wv.setWebViewClient(new Client(s));
        return wv;
    }

    private void destroyHidden() {
        WebView wv = hidden;
        hidden = null;
        if (wv == null) return;
        try {
            wv.stopLoading();
            wv.setWebViewClient(new WebViewClient());
            wv.loadUrl("about:blank");
            wv.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "destroy failed: " + e);
        }
    }

    private void onSourceWarmed(Source s) {
        long ms = SystemClock.elapsedRealtime() - currentStartedAt;
        long fills = cache == null ? 0 : cache.fillCount() - fillsAtStart;
        prefs.setWarmedAt(s.id, System.currentTimeMillis());
        if (shell != null) shell.refreshSource(s); // its shell routes are local-first from now on
        Log.i(TAG, "warmed " + s.id + " in " + ms + " ms, fills=" + fills);
    }

    private final class Client extends WebViewClient {
        private final Source source;
        private String topUrl;

        Client(Source source) {
            this.source = source;
            this.topUrl = source.url;
        }

        private boolean ownHost(Uri u) {
            String h = u.getHost();
            if (h == null) return false;
            List<String> hosts = source.allowedHosts;
            for (String a : hosts) {
                if (a != null && !a.isEmpty() && (h.equals(a) || h.endsWith("." + a))) return true;
            }
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !ownHost(request.getUrl()); // stay on the source; ignore redirects to ad or login hosts
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (request.isForMainFrame()) {
                topUrl = u.toString();
                return null;
            }
            String scheme = u.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            if (AdblockSwitch.isOn() && !AdblockEngine.check(u.toString(), topUrl, typeOf(u)).isAllowed()) {
                return new WebResourceResponse("text/plain", "utf-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
            }
            // Same durable cache as the visible page: hits are served from disk, a miss starts a background fill.
            return cache == null ? null : cache.intercept(request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != hidden || current == null) return;
            view.evaluateJavascript(SCROLL_JS, null);
            if (!currentWarmed) {
                currentWarmed = true;
                onSourceWarmed(current);
            }
            main.removeCallbacks(advance);
            main.postDelayed(advance, SETTLE_AFTER_FINISH_MS);
        }
    }

    private static String typeOf(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath().toLowerCase();
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "script";
        if (p.endsWith(".css")) return "stylesheet";
        if (p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".svg")) return "image";
        if (p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf")) return "font";
        return "xmlhttprequest";
    }
}
