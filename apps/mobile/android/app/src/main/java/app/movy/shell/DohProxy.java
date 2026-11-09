package app.movy.shell;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches body-less requests (GET/HEAD/OPTIONS) for configured hosts by connecting to a DoH-resolved address, so
 * sites keep working when the network's resolver poisons a name they depend on. TLS still verifies the real host name.
 * Connections are kept alive and reused (a page makes dozens of calls to the same API host: a fresh TCP + TLS handshake
 * for each one made the page wait for seconds on a TV).
 */
final class DohProxy {
    /** A fully buffered upstream response. */
    static final class Reply {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Reply(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        InputStream stream() {
            return new ByteArrayInputStream(body);
        }
    }

    /** One response read off the wire, and whether its connection may carry another request. */
    static final class Wire {
        final Reply reply;
        final boolean reusable;

        Wire(Reply reply, boolean reusable) {
            this.reply = reply;
            this.reusable = reusable;
        }
    }

    /** An open TLS connection with a buffered reader. */
    private static final class Conn {
        final SSLSocket tls;
        final InputStream in;
        final OutputStream out;
        long idleSince;

        Conn(SSLSocket tls) throws IOException {
            this.tls = tls;
            this.in = new BufferedInputStream(tls.getInputStream(), 8192);
            this.out = tls.getOutputStream();
        }

        void close() {
            try {
                tls.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 15000;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_IDLE_PER_HOST = 8;
    private static final long IDLE_TTL_MS = 15_000;
    private static final String[] DROP_REQUEST = {"host", "accept-encoding", "connection", "content-length",
            "cookie", "transfer-encoding", "upgrade-insecure-requests"};

    private final DohResolver resolver = new DohResolver();
    private final Map<String, ArrayDeque<Conn>> idle = new ConcurrentHashMap<>();

    /** Resolves a host without fetching anything (warm-up). */
    void resolve(String host) throws IOException {
        resolver.resolve(host);
    }

    /** Closes every idle connection (background, low memory). */
    void closeIdle() {
        for (ArrayDeque<Conn> q : idle.values()) {
            synchronized (q) {
                for (Conn c : q) c.close();
                q.clear();
            }
        }
    }

    Reply fetch(String method, String host, String path, Map<String, String> requestHeaders) throws IOException {
        boolean head = "HEAD".equals(method);
        String request = buildRequest(method, host, path, requestHeaders);
        Conn reused = takeIdle(host);
        if (reused != null) {
            try {
                return roundTrip(reused, host, request, head);
            } catch (IOException stale) {
                reused.close(); // the server closed it while idle: GET/HEAD/OPTIONS are safe to repeat on a new connection
            }
        }
        List<InetAddress> addrs = resolver.resolve(host);
        IOException last = null;
        for (InetAddress addr : addrs) {
            Socket raw = new Socket();
            Conn c = null;
            try {
                raw.connect(new InetSocketAddress(addr, 443), CONNECT_MS);
                raw.setSoTimeout(READ_MS);
                c = open(raw, host);
                return roundTrip(c, host, request, head);
            } catch (IOException e) {
                last = e;
                if (c != null) c.close();
                else {
                    try {
                        raw.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
        throw last != null ? last : new IOException("no address");
    }

    private static Conn open(Socket raw, String host) throws IOException {
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, 443, true);
        Conn c = new Conn(tls);
        tls.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession())) {
            c.close();
            throw new SSLPeerUnverifiedException("hostname verification failed");
        }
        return c;
    }

    private Reply roundTrip(Conn c, String host, String request, boolean head) throws IOException {
        c.out.write(request.getBytes(StandardCharsets.UTF_8));
        c.out.flush();
        Wire w = read(c.in, head);
        if (w.reusable) giveBack(host, c); else c.close();
        return w.reply;
    }

    private Conn takeIdle(String host) {
        ArrayDeque<Conn> q = idle.get(host);
        if (q == null) return null;
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty()) {
                Conn c = q.pollLast(); // most recently used first
                if (now - c.idleSince < IDLE_TTL_MS && !c.tls.isClosed()) return c;
                c.close();
            }
        }
        return null;
    }

    private void giveBack(String host, Conn c) {
        c.idleSince = System.currentTimeMillis();
        ArrayDeque<Conn> q = idle.computeIfAbsent(host, h -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(c);
            while (q.size() > MAX_IDLE_PER_HOST) q.pollFirst().close();
        }
    }

    static String buildRequest(String method, String host, String path, Map<String, String> headers) {
        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\nAccept-Encoding: identity\r\nConnection: keep-alive\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (isDropped(h.getKey()) || h.getValue().indexOf('\r') >= 0 || h.getValue().indexOf('\n') >= 0) continue;
            req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        return req.append("\r\n").toString();
    }

    private static boolean isDropped(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String d : DROP_REQUEST) if (d.equals(n)) return true;
        return false;
    }

    // ---------- response framing ----------

    /** Parses a whole buffered response (identity, chunked or close-delimited body). */
    static Reply parse(byte[] raw, boolean head) throws IOException {
        return read(new ByteArrayInputStream(raw), head).reply;
    }

    /**
     * Reads exactly one HTTP/1.x response: status line, headers, then the body framed by chunked encoding or
     * Content-Length (the connection stays reusable), or read to EOF when neither is given (it does not).
     */
    static Wire read(InputStream in, boolean head) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) throw new IOException("bad response");
        String[] status = statusLine.split(" ");
        if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IOException("bad status line");
        int code;
        try {
            code = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status");
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        if (line == null) throw new IOException("bad response");
        boolean keepAlive = status[0].equals("HTTP/1.1") && !connectionClose(headers);
        boolean noBody = head || code == 204 || code == 304 || (code >= 100 && code < 200);
        byte[] body = new byte[0];
        if (!noBody) {
            String cl = header(headers, "content-length");
            if (isChunked(headers)) {
                body = readChunked(in);
            } else if (cl != null) {
                body = readFixed(in, cl);
            } else {
                body = readToEnd(in);
                keepAlive = false; // close-delimited
            }
        }
        return new Wire(new Reply(code, headers, body), keepAlive);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] a = out.toByteArray();
                int n = a.length > 0 && a[a.length - 1] == '\r' ? a.length - 1 : a.length;
                return new String(a, 0, n, StandardCharsets.ISO_8859_1);
            }
            out.write(b);
            if (out.size() > MAX_LINE) throw new IOException("line too long");
        }
        if (out.size() == 0) return null;
        throw new IOException("bad response");
    }

    private static byte[] readFixed(InputStream in, String contentLength) throws IOException {
        long n;
        try {
            n = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad content-length");
        }
        if (n < 0 || n > MAX_BODY) throw new IOException("response too large");
        byte[] body = new byte[(int) n];
        int off = 0;
        while (off < body.length) {
            int r = in.read(body, off, body.length - off);
            if (r < 0) throw new IOException("short body");
            off += r;
        }
        return body;
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("response too large");
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String size = readLine(in);
            if (size == null) throw new IOException("bad chunk");
            int semi = size.indexOf(';');
            int len;
            try {
                len = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size");
            }
            if (len == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) { /* trailers are ignored */ }
                return out.toByteArray();
            }
            if (out.size() + (long) len > MAX_BODY) throw new IOException("response too large");
            byte[] chunk = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(chunk, off, len - off);
                if (r < 0) throw new IOException("short chunk");
                off += r;
            }
            out.write(chunk, 0, len);
            if (in.read() < 0 || in.read() < 0) throw new IOException("short chunk"); // CRLF after the chunk
        }
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> h : headers.entrySet()) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    private static boolean connectionClose(Map<String, String> headers) {
        String c = header(headers, "connection");
        return c != null && c.toLowerCase(Locale.ROOT).contains("close");
    }

    private static boolean isChunked(Map<String, String> headers) {
        String te = header(headers, "transfer-encoding");
        return te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
    }
}
