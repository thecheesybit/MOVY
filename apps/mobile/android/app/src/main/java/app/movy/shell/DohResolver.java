package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a host over DNS-over-HTTPS (IPv4 answers only) for networks whose resolver poisons or black-holes some
 * names. The WebView always uses the system resolver, so {@link DohProxy} uses this for the few configured hosts.
 */
final class DohResolver {
    private static final String[] PROVIDERS = {"cloudflare-dns.com", "dns.google"};
    private static final long MIN_TTL_MS = 60_000;
    private static final long MAX_TTL_MS = 600_000;
    private static final int TIMEOUT_MS = 2500;
    /** After every provider failed, the host is not tried again for this long: the WebView then uses its own resolver at once. */
    static final long FAILURE_TTL_MS = 5L * 60 * 1000;

    /** One DoH query (replaced by a fake in tests). */
    interface Lookup {
        Parsed query(String provider, String host) throws IOException;
    }

    private static final class Entry {
        final List<InetAddress> addrs;
        final long expiresAt;

        Entry(List<InetAddress> addrs, long expiresAt) {
            this.addrs = addrs;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Entry> cache = new HashMap<>();
    private final Map<String, Long> failedUntil = new HashMap<>();
    private final Map<String, Object> hostLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Lookup lookup;
    private final java.util.function.LongSupplier clock;

    DohResolver() {
        this(DohResolver::queryOverHttps, System::currentTimeMillis);
    }

    DohResolver(Lookup lookup, java.util.function.LongSupplier clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * IPv4 addresses for {@code host}, from cache when fresh; throws when no provider answered. Concurrent callers for one
     * host share a single lookup (the others wait for its result), and a host that no provider could resolve fails at once
     * for a few minutes instead of making every request wait for the timeouts again.
     */
    List<InetAddress> resolve(String host) throws IOException {
        List<InetAddress> hit = cached(host);
        if (hit != null) return hit;
        Object lock = hostLocks.computeIfAbsent(host, h -> new Object());
        synchronized (lock) {
            hit = cached(host); // another thread may have finished while this one waited
            if (hit != null) return hit;
            long now = clock.getAsLong();
            synchronized (cache) {
                Long until = failedUntil.get(host);
                if (until != null && until > now) throw new IOException("doh unavailable (recent failure)");
            }
            IOException last = null;
            for (String provider : PROVIDERS) {
                try {
                    Parsed p = lookup.query(provider, host);
                    if (p.addrs.isEmpty()) throw new IOException("doh no answer");
                    long ttl = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, p.ttlSeconds * 1000L));
                    synchronized (cache) {
                        cache.put(host, new Entry(p.addrs, clock.getAsLong() + ttl));
                        failedUntil.remove(host);
                    }
                    return p.addrs;
                } catch (IOException e) {
                    last = e;
                }
            }
            synchronized (cache) {
                failedUntil.put(host, clock.getAsLong() + FAILURE_TTL_MS);
            }
            throw last != null ? last : new IOException("doh failed");
        }
    }

    private List<InetAddress> cached(String host) {
        synchronized (cache) {
            Entry e = cache.get(host);
            return e != null && e.expiresAt > clock.getAsLong() ? e.addrs : null;
        }
    }

    private static Parsed queryOverHttps(String provider, String host) throws IOException {
        Ipv4FirstHttps.Response r = Ipv4FirstHttps.request("GET", provider, "/dns-query?name=" + host + "&type=A",
                Collections.singletonMap("Accept", "application/dns-json"), null, TIMEOUT_MS, TIMEOUT_MS);
        if (r.status != 200) throw new IOException("doh status " + r.status);
        return parse(r.body);
    }

    static final class Parsed {
        final List<InetAddress> addrs;
        final int ttlSeconds;

        Parsed(List<InetAddress> addrs, int ttlSeconds) {
            this.addrs = addrs;
            this.ttlSeconds = ttlSeconds;
        }
    }

    /** Parses an application/dns-json body; keeps only A records. */
    static Parsed parse(String body) throws IOException {
        try {
            JSONArray answers = new JSONObject(body).optJSONArray("Answer");
            List<InetAddress> out = new ArrayList<>();
            int ttl = Integer.MAX_VALUE;
            if (answers != null) {
                for (int i = 0; i < answers.length(); i++) {
                    JSONObject a = answers.getJSONObject(i);
                    if (a.optInt("type") != 1) continue;
                    String ip = a.optString("data", "");
                    if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
                    out.add(InetAddress.getByName(ip));
                    ttl = Math.min(ttl, a.optInt("TTL", 60));
                }
            }
            return new Parsed(Collections.unmodifiableList(out), ttl == Integer.MAX_VALUE ? 60 : ttl);
        } catch (JSONException e) {
            throw new IOException("doh bad json");
        }
    }
}
