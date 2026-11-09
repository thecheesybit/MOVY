package app.movy.shell;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-host DNS rescue (config: {@code dohHosts}). Some resolvers poison a name a site depends on (e.g. its metadata
 * API), so the page hangs on skeletons in the WebView, which cannot use DNS-over-HTTPS. Requests to the listed hosts are
 * answered here over a DoH-resolved connection instead. Everything else is untouched.
 */
public final class DohRescue {
    private static final String TAG = "MovyDoh";
    private static final int PREWARM_CONNECTIONS = 4;
    private static volatile List<String> hosts = Collections.emptyList();
    private static final DohProxy PROXY = new DohProxy();
    private static final DohReplyCache REPLIES = new DohReplyCache();

    private DohRescue() {}

    /** Background / low memory: close idle connections and drop the cached replies. */
    public static void releaseMemory() {
        PROXY.closeIdle();
        REPLIES.clear();
    }

    public static void setHosts(List<String> patterns) {
        List<String> lower = new ArrayList<>();
        for (String p : patterns) lower.add(p.toLowerCase(Locale.ROOT));
        hosts = Collections.unmodifiableList(lower);
    }

    /**
     * Resolves the configured exact hosts in the background (daemon thread) so the first page of a site that depends on one
     * does not wait for the lookup, or for its timeouts when DNS-over-HTTPS is unreachable on this network.
     */
    public static void warm() {
        final List<String> exact = new ArrayList<>();
        for (String p : hosts) if (!p.startsWith("*.")) exact.add(p);
        if (exact.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (String h : exact) {
                try {
                    PROXY.resolve(h);
                } catch (IOException e) {
                    Log.i(TAG, "warm-up lookup failed; the WebView resolves this host itself");
                    continue;
                }
                PROXY.prewarm(h, PREWARM_CONNECTIONS); // idle connections for the page's first burst of calls
            }
        }, "movy-doh-warm");
        t.setDaemon(true);
        t.start();
    }

    /** Opens a few connections to each exact rescued host in the background (a source is about to load). */
    public static void prewarm() {
        final List<String> exact = new ArrayList<>();
        for (String p : hosts) if (!p.startsWith("*.")) exact.add(p);
        if (exact.isEmpty()) return;
        Thread t = new Thread(() -> {
            for (String h : exact) PROXY.prewarm(h, PREWARM_CONNECTIONS);
        }, "movy-doh-prewarm");
        t.setDaemon(true);
        t.start();
    }

    /** Exact host, or a {@code *.suffix} pattern. */
    static boolean matches(List<String> patterns, String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p.equals(h)) return true;
            if (p.startsWith("*.") && h.endsWith(p.substring(1))) return true;
        }
        return false;
    }

    /** @return a response, or null when this request is not ours (or the rescue failed: the WebView then tries itself). */
    public static WebResourceResponse intercept(WebResourceRequest r, boolean debug) {
        List<String> patterns = hosts;
        if (patterns.isEmpty()) return null;
        android.net.Uri u = r.getUrl();
        String method = r.getMethod();
        if (!"https".equals(u.getScheme()) || !matches(patterns, u.getHost())) return null;
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) return null;
        String path = u.getEncodedPath() == null || u.getEncodedPath().isEmpty() ? "/" : u.getEncodedPath();
        if (u.getEncodedQuery() != null) path += "?" + u.getEncodedQuery();
        long t0 = System.nanoTime();
        boolean cacheable = "GET".equals(method) && r.getRequestHeaders().get("Authorization") == null;
        String key = u.getHost() + path;
        try {
            DohProxy.Reply reply = cacheable ? REPLIES.get(key, System.currentTimeMillis()) : null;
            if (reply != null) return toResponse(reply, r.getRequestHeaders().get("Origin"));
            reply = PROXY.fetch(method, u.getHost(), path, r.getRequestHeaders());
            if (cacheable) REPLIES.put(key, reply, System.currentTimeMillis());
            if (debug) Log.i(TAG, "rescued in " + (System.nanoTime() - t0) / 1_000_000 + " ms on " + Thread.currentThread().getName());
            if (reply.status < 200 || reply.status > 599 || (reply.status >= 300 && reply.status < 400)) return null;
            return toResponse(reply, r.getRequestHeaders().get("Origin"));
        } catch (IOException e) {
            Log.i(TAG, "rescue failed" + (debug ? ": " + e.getMessage() : ""));
            return null;
        }
    }

    static WebResourceResponse toResponse(DohProxy.Reply reply, String origin) {
        Map<String, String> out = new HashMap<>();
        String contentType = "application/octet-stream";
        for (Map.Entry<String, String> h : reply.headers.entrySet()) {
            String k = h.getKey().toLowerCase(Locale.ROOT);
            if (k.equals("content-type")) contentType = h.getValue();
            if (k.equals("content-length") || k.equals("transfer-encoding") || k.equals("connection")
                    || k.equals("content-encoding") || k.equals("set-cookie")) continue;
            out.put(h.getKey(), h.getValue());
        }
        boolean hasCors = false;
        for (String k : out.keySet()) hasCors |= k.equalsIgnoreCase("access-control-allow-origin");
        if (!hasCors && origin != null) out.put("Access-Control-Allow-Origin", origin);
        String mime = contentType;
        String charset = "utf-8";
        int semi = contentType.indexOf(';');
        if (semi >= 0) {
            mime = contentType.substring(0, semi).trim();
            int cs = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (cs >= 0) charset = contentType.substring(cs + 8).trim();
        }
        return new WebResourceResponse(mime, charset, reply.status, reasonFor(reply.status), out, reply.stream());
    }

    private static String reasonFor(int status) {
        return status >= 200 && status < 300 ? "OK" : "Error";
    }
}
