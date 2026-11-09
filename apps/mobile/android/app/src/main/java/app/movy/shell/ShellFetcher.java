package app.movy.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Network access of the local site shell for HTML documents (replaced by a fake in tests). Redirects are never followed. */
interface ShellFetcher {
    final class Result {
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        /** Each Set-Cookie header on its own (they cannot be joined safely). */
        final List<String> setCookies;

        Result(int status, Map<String, String> headers, byte[] body) {
            this(status, headers, body, java.util.Collections.emptyList());
        }

        Result(int status, Map<String, String> headers, byte[] body, List<String> setCookies) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.setCookies = setCookies;
        }

        String header(String name) {
            return CachePolicy.header(headers, name);
        }
    }

    /** Conditional values are applied when non-null. The body is read up to ShellPolicy.MAX_BYTES + 1 bytes. */
    Result fetch(String url, Map<String, String> requestHeaders, String ifNoneMatch, String ifModifiedSince) throws IOException;

    /** {@link ShellFetcher} over HttpURLConnection; the platform adds and decodes gzip when no Accept-Encoding is sent. */
    final class Http implements ShellFetcher {
        private static final int CONNECT_MS = 5000;
        private static final int READ_MS = 8000;
        private static final String[] FORWARD = {"User-Agent", "Accept", "Accept-Language", "Cookie", "Referer"};
        /** Debug builds only (intent extra movy_shell_offline): every live check fails, as when the device is offline. */
        static volatile boolean debugOffline;

        @Override
        public Result fetch(String url, Map<String, String> req, String ifNoneMatch, String ifModifiedSince) throws IOException {
            if (debugOffline) throw new IOException("offline (simulated)");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(false); // a redirected document is not the route's own page
                for (String h : FORWARD) {
                    String v = CachePolicy.header(req, h);
                    if (v != null) c.setRequestProperty(h, v);
                }
                if (ifNoneMatch != null) c.setRequestProperty("If-None-Match", ifNoneMatch);
                if (ifModifiedSince != null) c.setRequestProperty("If-Modified-Since", ifModifiedSince);
                int status = c.getResponseCode();
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    headers.put(e.getKey(), String.join(", ", e.getValue()));
                }
                List<String> cookies = c.getHeaderFields().get("Set-Cookie");
                byte[] body = status == 200 ? readCapped(c.getInputStream()) : new byte[0];
                return new Result(status, headers, body, cookies == null ? java.util.Collections.<String>emptyList() : new java.util.ArrayList<>(cookies));
            } finally {
                c.disconnect();
            }
        }

        private static byte[] readCapped(InputStream in) throws IOException {
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = s.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > ShellPolicy.MAX_BYTES) break; // judged TOO_LARGE
                }
                return out.toByteArray();
            }
        }
    }
}
