package app.movy.shell;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 * Warms the other sources' home pages in the background, one at a time, once the active source's first page has
 * finished: a detached WebView loads each start URL so its scripts, styles, images and API responses land in the shared
 * HTTP cache (and the site cookies/DNS/TLS are ready), which makes the later source switch nearly instant. It never
 * touches the visible WebView, stays on the source's own hosts, applies the ad filter, never autoplays media, and is
 * torn down after each source (or after a hard timeout) so only one hidden page exists at a time.
 */
final class SourcePreloader {
    private static final String TAG = "MovySourcePreload";
    private static final long START_DELAY_MS = 3500;
    private static final long SETTLE_AFTER_FINISH_MS = 5000;
    private static final long HARD_TIMEOUT_MS = 20000;

    private final Activity activity;
    private final SourceRepository repo;
    private final String userAgent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Source> queue = new ArrayDeque<>();
    private final Runnable advance = this::next;
    private WebView hidden;
    private boolean started;
    private boolean stopped;

    SourcePreloader(Activity activity, SourceRepository repo, String userAgent) {
        this.activity = activity;
        this.repo = repo;
        this.userAgent = userAgent;
    }

    /** Call when the visible source finished loading a page; only the first call per process starts the warm-up. */
    void onActivePageFinished() {
        if (started || stopped) return;
        started = true;
        main.postDelayed(this::begin, START_DELAY_MS);
    }

    /** Pauses the warm-up while the user is in a player (bandwidth) and frees the hidden page. */
    void stop() {
        stopped = true;
        main.removeCallbacks(advance);
        destroyHidden();
        queue.clear();
    }

    private void begin() {
        if (stopped) return;
        Source active = repo.active();
        for (Source s : repo.config().enabledSources()) {
            if (active != null && s.id.equals(active.id)) continue;
            queue.add(s);
        }
        next();
    }

    private void next() {
        main.removeCallbacks(advance);
        destroyHidden();
        if (stopped || activity.isFinishing() || activity.isDestroyed()) return;
        Source s = queue.poll();
        if (s == null) return;
        try {
            hidden = create(s);
            Log.i(TAG, "warming " + s.id);
            hidden.loadUrl(s.url);
            main.postDelayed(advance, HARD_TIMEOUT_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "preload failed for " + s.id + ": " + e);
            hidden = null;
            main.postDelayed(advance, 500);
        }
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
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (view != hidden) return;
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
