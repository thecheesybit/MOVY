package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class DohWireTest {
    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String body(DohProxy.Reply r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    @Test public void contentLengthFramesBackToBackResponsesOnOneConnection() throws IOException {
        InputStream in = stream("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n\r\n{\"a\":1}"
                + "HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\nno");
        DohProxy.Wire first = DohProxy.read(in, false);
        assertEquals(200, first.reply.status);
        assertEquals("{\"a\":1}", body(first.reply));
        assertTrue("keep-alive: the connection may be reused", first.reusable);
        DohProxy.Wire second = DohProxy.read(in, false);
        assertEquals(404, second.reply.status);
        assertEquals("no", body(second.reply));
    }

    @Test public void chunkedBodyEndsAtTheLastChunkAndKeepsTheConnection() throws IOException {
        InputStream in = stream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
                + "HTTP/1.1 204 No Content\r\n\r\n");
        DohProxy.Wire w = DohProxy.read(in, false);
        assertEquals("Wikipedia", body(w.reply));
        assertTrue(w.reusable);
        assertEquals(204, DohProxy.read(in, false).reply.status);
    }

    @Test public void connectionCloseAndCloseDelimitedBodiesAreNotReused() throws IOException {
        assertFalse(DohProxy.read(stream("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1\r\n\r\nx"), false).reusable);
        DohProxy.Wire open = DohProxy.read(stream("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nrest of stream"), false);
        assertEquals("rest of stream", body(open.reply));
        assertFalse(open.reusable);
        assertFalse(DohProxy.read(stream("HTTP/1.0 200 OK\r\nContent-Length: 1\r\n\r\nx"), false).reusable);
    }

    @Test public void headAndNoContentResponsesHaveNoBody() throws IOException {
        DohProxy.Wire head = DohProxy.read(stream("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n"), true);
        assertEquals(0, head.reply.body.length);
        assertTrue(head.reusable);
    }

    @Test public void truncatedResponsesFail() {
        for (String bad : new String[] {"HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nshort", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nFF\r\nshort",
                "", "HTTP/1.1 200 OK\r\nContent-Type: x"}) {
            try {
                DohProxy.read(stream(bad), false);
                fail("expected IOException for: " + bad);
            } catch (IOException expected) {
                // a reused connection that the server closed ends up here and is retried on a fresh one
            }
        }
    }

    @Test public void requestAsksForKeepAlive() {
        String req = DohProxy.buildRequest("GET", "api.example.org", "/x", new HashMap<>());
        assertTrue(req.contains("Connection: keep-alive\r\n"));
        assertFalse(req.contains("Connection: close"));
    }

    // ---------- reply cache ----------

    private static DohProxy.Reply reply(int status, String cc, int bodyLen) {
        Map<String, String> h = new HashMap<>();
        if (cc != null) h.put("Cache-Control", cc);
        return new DohProxy.Reply(status, h, new byte[bodyLen]);
    }

    @Test public void onlyCacheableOkResponsesAreKeptForTheirMaxAge() {
        DohReplyCache c = new DohReplyCache();
        c.put("k", reply(200, "public, max-age=60", 10), 1000);
        assertEquals(10, c.get("k", 1000 + 59_000).body.length);
        assertNull("expired after max-age", c.get("k", 1000 + 60_000));
        c.put("a", reply(200, "no-store", 10), 1000);
        c.put("b", reply(200, "private, max-age=60", 10), 1000);
        c.put("c", reply(200, null, 10), 1000);
        c.put("d", reply(404, "max-age=60", 10), 1000);
        c.put("e", reply(200, "max-age=0", 10), 1000);
        assertEquals(0, c.size());
    }

    @Test public void ttlIsCappedAndSharedMaxAgeIsHonoured() {
        assertEquals(DohReplyCache.MAX_TTL_MS, DohReplyCache.ttlMs(reply(200, "max-age=86400", 1)));
        assertEquals(120_000, DohReplyCache.ttlMs(reply(200, "s-maxage=120", 1)));
    }

    @Test public void cacheStaysUnderItsByteBudget() {
        DohReplyCache c = new DohReplyCache();
        for (int i = 0; i < 20; i++) c.put("k" + i, reply(200, "max-age=600", 900 * 1024), 0);
        assertTrue(c.size() <= 4);
        assertNull("oldest entries were evicted", c.get("k0", 1));
        assertEquals(900 * 1024, c.get("k19", 1).body.length);
        c.put("huge", reply(200, "max-age=600", 2 * 1024 * 1024), 0);
        assertNull("one reply may not take over the cache", c.get("huge", 1));
    }
}
