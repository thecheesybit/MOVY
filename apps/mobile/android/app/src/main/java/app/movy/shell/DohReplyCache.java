package app.movy.shell;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small in-memory cache for rescued API GET responses. Chromium never caches a response that came from shouldInterceptRequest,
 * so without this every page visit repeated the same metadata calls. Only 200 responses that allow caching are kept, for at
 * most their max-age (capped), in a byte-bounded LRU. Nothing is written to disk.
 */
final class DohReplyCache {
    static final long MAX_TTL_MS = 30L * 60 * 1000;
    static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final class Item {
        final DohProxy.Reply reply;
        final long expiresAt;

        Item(DohProxy.Reply reply, long expiresAt) {
            this.reply = reply;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Item> map = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    synchronized DohProxy.Reply get(String key, long now) {
        Item i = map.get(key);
        if (i == null) return null;
        if (i.expiresAt <= now) {
            remove(key);
            return null;
        }
        return i.reply;
    }

    synchronized void put(String key, DohProxy.Reply reply, long now) {
        long ttl = ttlMs(reply);
        if (ttl <= 0 || reply.body.length > MAX_BYTES / 4) return;
        remove(key);
        map.put(key, new Item(reply, now + ttl));
        bytes += reply.body.length;
        Iterator<Map.Entry<String, Item>> it = map.entrySet().iterator();
        while (bytes > MAX_BYTES && it.hasNext()) {
            Item eldest = it.next().getValue();
            bytes -= eldest.reply.body.length;
            it.remove();
        }
    }

    synchronized void clear() {
        map.clear();
        bytes = 0;
    }

    synchronized int size() {
        return map.size();
    }

    private void remove(String key) {
        Item old = map.remove(key);
        if (old != null) bytes -= old.reply.body.length;
    }

    /** Milliseconds the response may be reused: 0 unless it is a 200 with a positive max-age and no no-store/private/no-cache. */
    static long ttlMs(DohProxy.Reply r) {
        if (r.status != 200) return 0;
        String cc = null, vary = null;
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase("cache-control")) cc = h.getValue();
            else if (h.getKey().equalsIgnoreCase("vary")) vary = h.getValue();
        }
        if (cc == null || (vary != null && vary.trim().equals("*"))) return 0;
        String lc = cc.toLowerCase(Locale.ROOT);
        if (lc.contains("no-store") || lc.contains("private") || lc.contains("no-cache")) return 0;
        long s = CachePolicy.maxAge(lc.contains("s-maxage=") ? lc.replace("s-maxage=", "max-age=") : lc);
        return Math.min(s * 1000L, MAX_TTL_MS);
    }
}
