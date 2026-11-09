package app.movy.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory accounting of the asset cache: size, first-store time, last access, revalidation time and the response
 * meta (mime + kept headers) per key, so a hit never has to re-read its meta file from disk. Identical header maps are
 * shared between entries to keep the index small.
 */
final class CacheIndex {
    static final class Info {
        final long size;
        final long storedAt;
        long lastAccess;
        long validatedAt;
        final String mime;
        final Map<String, String> headers;

        Info(long size, long storedAt, long lastAccess, long validatedAt, String mime, Map<String, String> headers) {
            this.size = size;
            this.storedAt = storedAt;
            this.lastAccess = lastAccess;
            this.validatedAt = validatedAt;
            this.mime = mime;
            this.headers = headers;
        }

        /** True when the response meta is held (entries put by size only carry none). */
        boolean hasMeta() {
            return mime != null && headers != null;
        }
    }

    private final Map<String, Info> map = new LinkedHashMap<>();
    private final Map<Map<String, String>, Map<String, String>> sharedHeaders = new HashMap<>();
    private long total;

    /** Size-only entry (validatedAt = storedAt, no response meta). */
    synchronized void put(String key, long size, long storedAt, long now) {
        put(key, size, storedAt, storedAt, now, null, null);
    }

    synchronized void put(String key, long size, long storedAt, long validatedAt, long now, String mime, Map<String, String> headers) {
        Info old = map.remove(key);
        if (old != null) total -= old.size;
        map.put(key, new Info(size, storedAt, now, validatedAt, mime, share(headers)));
        total += size;
    }

    private Map<String, String> share(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> existing = sharedHeaders.get(headers);
        if (existing != null) return existing;
        Map<String, String> copy = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        sharedHeaders.put(copy, copy);
        return copy;
    }

    synchronized Info get(String key) {
        return map.get(key);
    }

    synchronized void touch(String key, long now) {
        Info i = map.remove(key);
        if (i == null) return;
        i.lastAccess = now;
        map.put(key, i); // most recently used goes last
    }

    synchronized void setValidated(String key, long when) {
        Info i = map.get(key);
        if (i != null) i.validatedAt = when;
    }

    synchronized boolean remove(String key) {
        Info old = map.remove(key);
        if (old == null) return false;
        total -= old.size;
        return true;
    }

    synchronized boolean has(String key) {
        return map.containsKey(key);
    }

    synchronized long totalBytes() {
        return total;
    }

    synchronized int count() {
        return map.size();
    }

    synchronized void clear() {
        map.clear();
        sharedHeaders.clear();
        total = 0;
    }

    /** Keys to evict, least recently used first, until the total fits maxBytes. The index itself is untouched. */
    synchronized List<String> overflow(long maxBytes) {
        return overflow(maxBytes, java.util.Collections.emptySet());
    }

    /** As {@link #overflow(long)}, but pinned keys are never chosen (the cache may stay over its cap because of them). */
    synchronized List<String> overflow(long maxBytes, java.util.Set<String> pinned) {
        List<String> out = new ArrayList<>();
        long t = total;
        for (Map.Entry<String, Info> e : map.entrySet()) {
            if (t <= maxBytes) break;
            if (pinned.contains(e.getKey())) continue;
            out.add(e.getKey());
            t -= e.getValue().size;
        }
        return out;
    }

    /** Keys stored ttlMs or longer ago. */
    synchronized List<String> expired(long now, long ttlMs) {
        return expired(now, ttlMs, java.util.Collections.emptySet());
    }

    synchronized List<String> expired(long now, long ttlMs, java.util.Set<String> pinned) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Info> e : map.entrySet()) {
            if (!pinned.contains(e.getKey()) && CachePolicy.isExpired(e.getValue().storedAt, now, ttlMs)) out.add(e.getKey());
        }
        return out;
    }
}
