package app.movy.shell;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules of the local site shell: which requests are shell routes, what makes an HTML response worth keeping, how a
 * page is normalised and hashed (so "changed" means a real change), where its build id and assets are, how stale a
 * snapshot may get, how the version ring rotates, and what to answer while the live site is being asked.
 */
final class ShellPolicy {
    static final int MIN_BYTES = 5 * 1024;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_ASSETS = 150;
    static final long STALE_WAIT_MS = 3000;
    static final long BYPASS_WAIT_MS = 8000;

    /** Things that differ on every request without the site having changed. */
    private static final String[] DEFAULT_VOLATILE = {
        "\\snonce=\"[^\"]*\"", "<meta[^>]+csrf[^>]*>", "\\b1[5-9]\\d{11}\\b", "\"(?:ts|timestamp|time|requestId|rid)\":\"?[\\w-]+\"?"};
    private static final List<Pattern> DEFAULT_VOLATILE_COMPILED = new ArrayList<>();

    static {
        for (String p : DEFAULT_VOLATILE) DEFAULT_VOLATILE_COMPILED.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
    }

    private static final Pattern ATTR_URL = Pattern.compile("(?:src|href)\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<(script|link)\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_IMPORT = Pattern.compile("[\"'](/?(?:_app/immutable|_next/static|assets)/[^\"'\\s]+\\.(?:m?js|css))[\"']");
    private static final Pattern BUILD_NEXT = Pattern.compile("\"buildId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BUILD_NEXT_PATH = Pattern.compile("/_next/static/([A-Za-z0-9_-]{8,})/_(?:buildManifest|ssgManifest)\\.js");
    private static final Pattern BUILD_SVELTE = Pattern.compile("/_app/immutable/entry/start\\.([A-Za-z0-9_-]+)\\.js");
    private static final Pattern BUILD_VITE = Pattern.compile("/assets/index-([A-Za-z0-9_-]+)\\.(?:m?js|css)");
    private static final Pattern CHALLENGE = Pattern.compile("cf-chl|challenge-platform|just a moment|checking your browser|attention required|cf-browser-verification",
            Pattern.CASE_INSENSITIVE);

    private ShellPolicy() {}

    // ---------- routes ----------

    /** Path of an http(s) URL without query/fragment, no trailing slash except for the root; null for anything else. */
    static String pathOf(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return null;
            if (u.getRawQuery() != null) return null; // routes with a query string are never shell routes
            String p = u.getRawPath();
            if (p == null || p.isEmpty()) return "/";
            if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isShellRoute(List<String> routes, String url) {
        String p = pathOf(url);
        if (p == null) return false;
        for (String r : routes) {
            String n = r.length() > 1 && r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
            if (n.toLowerCase(Locale.ROOT).equals(p)) return true;
        }
        return false;
    }

    /** File-system safe key of a route path ("/" is "root"). */
    static String routeKey(String path) {
        if (path == null || path.equals("/")) return "root";
        String k = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return k.isEmpty() ? "root" : k;
    }

    // ---------- what is worth keeping ----------

    enum Verdict { OK, NOT_OK_STATUS, NOT_HTML, TOO_SMALL, TOO_LARGE, CHALLENGE, NO_ROOT }

    /** 200 text/html, 5 KB to 2 MB, not a bot/CDN challenge, and containing one of the site's root elements. */
    static Verdict judge(int status, String contentType, byte[] body, String html, List<String> roots) {
        if (status != 200) return Verdict.NOT_OK_STATUS;
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) return Verdict.NOT_HTML;
        if (body == null || body.length < MIN_BYTES) return Verdict.TOO_SMALL;
        if (body.length > MAX_BYTES) return Verdict.TOO_LARGE;
        if (CHALLENGE.matcher(html.length() > 20000 ? html.substring(0, 20000) : html).find()) return Verdict.CHALLENGE;
        if (!roots.isEmpty() && !hasRoot(html, roots)) return Verdict.NO_ROOT;
        return Verdict.OK;
    }

    /** Roots are simple selectors: "#id", ".class" or a tag name, matched against the markup text. */
    static boolean hasRoot(String html, List<String> roots) {
        String lower = html.toLowerCase(Locale.ROOT);
        for (String r : roots) {
            String s = r.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#") && (lower.contains("id=\"" + s.substring(1) + "\"") || lower.contains("id='" + s.substring(1) + "'"))) return true;
            if (s.startsWith(".") && Pattern.compile("class=[\"'][^\"']*\\b" + Pattern.quote(s.substring(1)) + "\\b").matcher(lower).find()) return true;
            if (!s.startsWith("#") && !s.startsWith(".") && lower.contains("<" + s)) return true;
        }
        return false;
    }

    // ---------- normalise, hash, build id, assets ----------

    static String normalise(String html, List<Pattern> volatilePatterns) {
        String out = html;
        for (Pattern p : DEFAULT_VOLATILE_COMPILED) out = p.matcher(out).replaceAll("");
        for (Pattern p : volatilePatterns) out = p.matcher(out).replaceAll("");
        return out;
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Next.js buildId, SvelteKit entry hash or Vite entry hash; else a hash of the asset list (any deploy changes it). */
    static String buildId(String html, List<String> assets) {
        Matcher m = BUILD_NEXT.matcher(html);
        if (m.find()) return m.group(1);
        for (Pattern p : new Pattern[] {BUILD_NEXT_PATH, BUILD_SVELTE, BUILD_VITE}) {
            m = p.matcher(html);
            if (m.find()) return m.group(1);
        }
        return "a" + hash(String.join("\n", assets)).substring(0, 12);
    }

    /** Absolute URLs of the scripts, styles and preloads the document references (and inline import paths), deduped, capped. */
    static List<String> extractAssets(String html, String baseUrl) {
        Set<String> out = new LinkedHashSet<>();
        Matcher t = TAG.matcher(html);
        while (t.find() && out.size() < MAX_ASSETS) {
            String tag = t.group();
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.startsWith("<link") && !(lower.contains("stylesheet") || lower.contains("modulepreload") || lower.contains("preload"))) continue;
            Matcher a = ATTR_URL.matcher(tag);
            if (a.find()) addAbsolute(out, baseUrl, a.group(1));
        }
        Matcher im = INLINE_IMPORT.matcher(html);
        while (im.find() && out.size() < MAX_ASSETS) addAbsolute(out, baseUrl, im.group(1));
        return new ArrayList<>(out);
    }

    private static void addAbsolute(Set<String> out, String base, String ref) {
        try {
            String r = ref.trim();
            if (r.startsWith("data:") || r.startsWith("javascript:") || r.startsWith("blob:")) return;
            boolean rootless = r.startsWith("_app/") || r.startsWith("_next/") || r.startsWith("assets/");
            String abs = URI.create(base).resolve(rootless ? "/" + r : r).toString();
            if (abs.startsWith("http://") || abs.startsWith("https://")) out.add(abs);
        } catch (IllegalArgumentException ignored) {
            // an unparseable reference is simply not prefetched
        }
    }

    // ---------- staleness, ring ----------

    static boolean isStale(long fetchedAt, long now, long maxAgeMs) {
        return fetchedAt <= 0 || now - fetchedAt >= maxAgeMs || fetchedAt - now > maxAgeMs; // a clock far in the past counts as stale
    }

    /** Tags (newest first) that remain after pushing {@code newTag}; the ring never keeps more than {@code keep}. */
    static List<Integer> ringAfterPush(List<Integer> tagsNewestFirst, int newTag, int keep) {
        List<Integer> out = new ArrayList<>();
        out.add(newTag);
        for (int t : tagsNewestFirst) if (t != newTag) out.add(t);
        int k = Math.max(1, keep);
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    // ---------- what to answer ----------

    enum Live { PENDING, UNCHANGED, CHANGED, FAILED }

    enum Action { SERVE_LOCAL, PASS_THROUGH }

    /** How long to wait for the live site before answering: the race window, or longer when local cannot be trusted alone. */
    static long waitMs(boolean stale, boolean bypass, long raceMs) {
        return bypass ? BYPASS_WAIT_MS : stale ? STALE_WAIT_MS : raceMs;
    }

    /**
     * @param hasLocal a good (not bad) snapshot exists
     * @param stale    it is older than the source's max age
     * @param bypass   the newest snapshot just failed its boot check: prefer live
     */
    static Action decide(boolean hasLocal, boolean stale, boolean bypass, Live live) {
        if (!hasLocal) return Action.PASS_THROUGH;
        switch (live) {
            case CHANGED: return Action.PASS_THROUGH; // the WebView loads the live page itself; the new version is stored meanwhile
            case UNCHANGED: return Action.SERVE_LOCAL; // verified fresh
            case FAILED: return Action.SERVE_LOCAL; // offline or site down: better than an error page
            default: return stale && !bypass ? Action.PASS_THROUGH : Action.SERVE_LOCAL;
        }
    }
}
