package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DohResolverTest {
    private final long[] now = {1_000_000L};
    private final AtomicInteger queries = new AtomicInteger();

    private DohResolver.Parsed answer() throws IOException {
        return new DohResolver.Parsed(Collections.singletonList(InetAddress.getByName("192.0.2.7")), 120);
    }

    @Test public void successIsCachedForItsTtl() throws IOException {
        DohResolver r = new DohResolver((p, h) -> { queries.incrementAndGet(); return answer(); }, () -> now[0]);
        assertEquals(1, r.resolve("api.example.test").size());
        r.resolve("api.example.test");
        assertEquals("second call is served from the cache", 1, queries.get());
        now[0] += 121_000;
        r.resolve("api.example.test");
        assertEquals("expired entries are looked up again", 2, queries.get());
    }

    @Test public void aHostThatFailedEverywhereFailsAtOnceForAWhile() {
        DohResolver r = new DohResolver((p, h) -> { queries.incrementAndGet(); throw new IOException("timeout"); }, () -> now[0]);
        try { r.resolve("api.example.test"); fail(); } catch (IOException expected) { /* both providers tried */ }
        assertEquals(2, queries.get());
        try { r.resolve("api.example.test"); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("recent failure")); }
        assertEquals("no further network lookups inside the failure window", 2, queries.get());
        now[0] += DohResolver.FAILURE_TTL_MS + 1;
        try { r.resolve("api.example.test"); fail(); } catch (IOException expected) { /* retried */ }
        assertEquals(4, queries.get());
    }

    @Test public void theSecondProviderIsUsedWhenTheFirstFails() throws IOException {
        List<String> asked = new ArrayList<>();
        DohResolver r = new DohResolver((p, h) -> { asked.add(p); if (asked.size() == 1) throw new IOException("down"); return answer(); }, () -> now[0]);
        assertEquals(1, r.resolve("api.example.test").size());
        assertEquals(2, asked.size());
    }

    @Test public void concurrentCallersShareOneLookup() throws Exception {
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final DohResolver r = new DohResolver((p, h) -> {
            queries.incrementAndGet();
            try { gate.await(); } catch (InterruptedException e) { throw new IOException(e); }
            return answer();
        }, () -> now[0]);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> { try { r.resolve("api.example.test"); } catch (IOException ignored) { /* checked below */ } });
            t.start();
            ts.add(t);
        }
        Thread.sleep(200);
        gate.countDown();
        for (Thread t : ts) t.join(5000);
        assertEquals("six waiting requests, one DoH query", 1, queries.get());
    }
}
