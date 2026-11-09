package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class ShellPolicyTest {
    private static final List<String> ROUTES = Arrays.asList("/", "/browse/movie", "/tv/");

    private static String pad(String html) {
        StringBuilder sb = new StringBuilder(html);
        while (sb.length() < ShellPolicy.MIN_BYTES + 10) sb.append("<!-- filler filler filler filler -->");
        return sb.toString();
    }

    @Test public void routesMatchByPathIgnoringTrailingSlashAndCase() {
        assertTrue(ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/"));
        assertTrue(ShellPolicy.isShellRoute(ROUTES, "https://a.example.test"));
        assertTrue(ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/Browse/Movie/"));
        assertTrue("a route listed with a trailing slash", ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/tv"));
        assertFalse(ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/movie/123"));
        assertFalse("never with a query string", ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/?page=2"));
        assertFalse(ShellPolicy.isShellRoute(ROUTES, "https://a.example.test/search"));
        assertFalse(ShellPolicy.isShellRoute(ROUTES, "ftp://a.example.test/"));
        assertFalse(ShellPolicy.isShellRoute(ROUTES, null));
    }

    @Test public void routeKeysAreFileSystemSafe() {
        assertEquals("root", ShellPolicy.routeKey("/"));
        assertEquals("browse_movie", ShellPolicy.routeKey("/browse/movie"));
        assertEquals("tv_shows", ShellPolicy.routeKey("/tv-shows"));
    }

    @Test public void onlyRealHtmlDocumentsAreSnapshotted() {
        List<String> roots = Collections.singletonList("#root");
        String ok = pad("<html><body><div id=\"root\"></div></body></html>");
        byte[] b = ok.getBytes(StandardCharsets.UTF_8);
        assertEquals(ShellPolicy.Verdict.OK, ShellPolicy.judge(200, "text/html; charset=utf-8", b, ok, roots));
        assertEquals(ShellPolicy.Verdict.NOT_OK_STATUS, ShellPolicy.judge(503, "text/html", b, ok, roots));
        assertEquals(ShellPolicy.Verdict.NOT_OK_STATUS, ShellPolicy.judge(302, "text/html", b, ok, roots));
        assertEquals(ShellPolicy.Verdict.NOT_HTML, ShellPolicy.judge(200, "application/json", b, ok, roots));
        assertEquals(ShellPolicy.Verdict.TOO_SMALL, ShellPolicy.judge(200, "text/html", "<div id=\"root\"></div>".getBytes(), "<div id=\"root\"></div>", roots));
        assertEquals(ShellPolicy.Verdict.TOO_LARGE, ShellPolicy.judge(200, "text/html", new byte[ShellPolicy.MAX_BYTES + 1], ok, roots));
        String challenge = pad("<html><title>Just a moment...</title><div id=\"root\"></div>");
        assertEquals(ShellPolicy.Verdict.CHALLENGE, ShellPolicy.judge(200, "text/html", challenge.getBytes(), challenge, roots));
        String noRoot = pad("<html><body><p>hello</p></body></html>");
        assertEquals(ShellPolicy.Verdict.NO_ROOT, ShellPolicy.judge(200, "text/html", noRoot.getBytes(), noRoot, roots));
        assertEquals("no markers configured = no check", ShellPolicy.Verdict.OK, ShellPolicy.judge(200, "text/html", noRoot.getBytes(), noRoot, Collections.emptyList()));
    }

    @Test public void rootMarkersMatchIdClassAndTag() {
        assertTrue(ShellPolicy.hasRoot("<div id=\"__next\"></div>", Collections.singletonList("#__next")));
        assertTrue(ShellPolicy.hasRoot("<div class=\"a app-shell b\"></div>", Collections.singletonList(".app-shell")));
        assertTrue(ShellPolicy.hasRoot("<body class=x>", Collections.singletonList("body")));
        assertFalse(ShellPolicy.hasRoot("<div id=\"other\"></div>", Collections.singletonList("#__next")));
    }

    @Test public void volatileDataDoesNotCountAsAChange() {
        List<Pattern> none = Collections.emptyList();
        String a = "<script nonce=\"abc123\">x</script><meta name=\"csrf-token\" content=\"zzz\"><i data-ts=1731000000000></i>{\"ts\":\"111\"}<p>same</p>";
        String b = "<script nonce=\"def456\">x</script><meta name=\"csrf-token\" content=\"yyy\"><i data-ts=1731999999999></i>{\"ts\":\"222\"}<p>same</p>";
        assertEquals(ShellPolicy.hash(ShellPolicy.normalise(a, none)), ShellPolicy.hash(ShellPolicy.normalise(b, none)));
        assertNotEquals(ShellPolicy.hash(ShellPolicy.normalise(a, none)), ShellPolicy.hash(ShellPolicy.normalise(a.replace("same", "different"), none)));
        List<Pattern> custom = Collections.singletonList(Pattern.compile("<aside id=\"ads\">.*?</aside>"));
        assertEquals(ShellPolicy.normalise("<p>x</p><aside id=\"ads\">1</aside>", custom), ShellPolicy.normalise("<p>x</p><aside id=\"ads\">2</aside>", custom));
    }

    @Test public void buildIdsOfTheKnownFrameworks() {
        assertEquals("kXy12_ab", ShellPolicy.buildId("<script id=\"__NEXT_DATA__\">{\"buildId\":\"kXy12_ab\"}</script>", Collections.emptyList()));
        assertEquals("abcdefgh12", ShellPolicy.buildId("<script src=\"/_next/static/abcdefgh12/_buildManifest.js\"></script>", Collections.emptyList()));
        assertEquals("Hq9zXw", ShellPolicy.buildId("<link href=\"/_app/immutable/entry/start.Hq9zXw.js\" rel=\"modulepreload\">", Collections.emptyList()));
        assertEquals("C_jkdOQv", ShellPolicy.buildId("<script src=\"/assets/index-C_jkdOQv.js\"></script>", Collections.emptyList()));
        String a = ShellPolicy.buildId("<p>no marker</p>", Arrays.asList("https://x.test/a.js"));
        assertEquals("fallback follows the asset list", a, ShellPolicy.buildId("<p>other</p>", Arrays.asList("https://x.test/a.js")));
        assertNotEquals(a, ShellPolicy.buildId("<p>no marker</p>", Arrays.asList("https://x.test/b.js")));
    }

    @Test public void assetsAreAbsoluteDedupedAndCoverInlineImports() {
        String html = "<link rel=\"stylesheet\" href=\"/assets/app.css\"><link rel=\"icon\" href=\"/favicon.ico\">"
                + "<link rel=\"modulepreload\" href=\"/_app/immutable/chunks/A1.js\"><script src=\"https://cdn.example.test/lib.js\"></script>"
                + "<script src=\"/assets/app.js\"></script><script src=\"/assets/app.js\"></script>"
                + "<script>import(\"/_app/immutable/entry/start.Hq9zXw.js\")</script>";
        List<String> a = ShellPolicy.extractAssets(html, "https://site.test/browse");
        assertTrue(a.contains("https://site.test/assets/app.css"));
        assertTrue(a.contains("https://site.test/_app/immutable/chunks/A1.js"));
        assertTrue(a.contains("https://cdn.example.test/lib.js"));
        assertTrue(a.contains("https://site.test/_app/immutable/entry/start.Hq9zXw.js"));
        assertFalse("icons are not assets", a.contains("https://site.test/favicon.ico"));
        int n = 0;
        for (String s : a) if (s.endsWith("/assets/app.js")) n++;
        assertEquals(1, n);
    }

    @Test public void stalenessUsesTheMaxAgeAndDistrustsABackwardsClock() {
        long h = 60L * 60 * 1000;
        assertFalse(ShellPolicy.isStale(1000, 1000 + 11 * h, 12 * h));
        assertTrue(ShellPolicy.isStale(1000, 1000 + 12 * h, 12 * h));
        assertTrue(ShellPolicy.isStale(0, 5, 12 * h));
        assertTrue("fetched in the far future = clock went back", ShellPolicy.isStale(1000 + 30 * h, 1000, 12 * h));
        assertTrue("SSR sites use 6 h", ShellPolicy.isStale(1000, 1000 + 7 * h, 6 * h));
    }

    @Test public void ringKeepsTheNewestAndDropsTheOldest() {
        assertEquals(Arrays.asList(4, 3, 2), ShellPolicy.ringAfterPush(Arrays.asList(3, 2, 1), 4, 3));
        assertEquals(Arrays.asList(1), ShellPolicy.ringAfterPush(Collections.emptyList(), 1, 3));
        assertEquals(Arrays.asList(2, 1), ShellPolicy.ringAfterPush(Arrays.asList(1), 2, 3));
        assertEquals(Arrays.asList(5, 4, 3, 2, 1), ShellPolicy.ringAfterPush(Arrays.asList(4, 3, 2, 1), 5, 5));
        assertEquals(Arrays.asList(3), ShellPolicy.ringAfterPush(Arrays.asList(2, 1), 3, 1));
    }

    @Test public void waitsLongerOnlyWhenLocalCannotBeTrustedAlone() {
        assertEquals(700, ShellPolicy.waitMs(false, false, 700));
        assertEquals(ShellPolicy.STALE_WAIT_MS, ShellPolicy.waitMs(true, false, 700));
        assertEquals(ShellPolicy.BYPASS_WAIT_MS, ShellPolicy.waitMs(false, true, 700));
        assertEquals(ShellPolicy.BYPASS_WAIT_MS, ShellPolicy.waitMs(true, true, 700));
    }

    @Test public void decisionTable() {
        for (ShellPolicy.Live l : ShellPolicy.Live.values()) {
            assertEquals("no local copy: always the live page", ShellPolicy.Action.PASS_THROUGH, ShellPolicy.decide(false, false, false, l));
        }
        assertEquals(ShellPolicy.Action.PASS_THROUGH, ShellPolicy.decide(true, false, false, ShellPolicy.Live.CHANGED));
        assertEquals(ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, false, false, ShellPolicy.Live.UNCHANGED));
        assertEquals("slow network: local", ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, false, false, ShellPolicy.Live.PENDING));
        assertEquals("offline: local", ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, false, false, ShellPolicy.Live.FAILED));
        assertEquals("stale and live silent: normal loading", ShellPolicy.Action.PASS_THROUGH, ShellPolicy.decide(true, true, false, ShellPolicy.Live.PENDING));
        assertEquals("stale but verified unchanged: fine", ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, true, false, ShellPolicy.Live.UNCHANGED));
        assertEquals("stale and offline: still better than an error page", ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, true, false, ShellPolicy.Live.FAILED));
        assertEquals("boot failed, live silent: previous good version", ShellPolicy.Action.SERVE_LOCAL, ShellPolicy.decide(true, false, true, ShellPolicy.Live.PENDING));
        assertEquals(ShellPolicy.Action.PASS_THROUGH, ShellPolicy.decide(true, false, true, ShellPolicy.Live.CHANGED));
        assertNull(null);
    }
}
