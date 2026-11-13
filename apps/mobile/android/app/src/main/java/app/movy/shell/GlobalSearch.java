package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure model of the global search (TV, plan stage 6c): one request per source built from its profile `search.api`,
 * the site's JSON answer turned into result tiles, the order of the result rows and the recent queries. No Android
 * types and no network here; the overlay (stage 6d) fetches and draws. Hosts come only from the git-ignored profile.
 */
final class GlobalSearch {
    private GlobalSearch() {}

    static final int MAX_HITS = 12;
    static final int MAX_RECENTS = 8;
    static final int MAX_QUERY = 100;

    /** One result tile. {@code path} is the site route to open (relative to the source origin). */
    static final class Hit {
        final String id;
        final String title;
        final String year;
        final String poster;
        final String type;
        final String path;

        Hit(String id, String title, String year, String poster, String type, String path) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.poster = poster;
            this.type = type;
            this.path = path;
        }
    }

    enum Status { LOADING, OK, FAILED, NO_API }

    /** One row of the results: a source and what its search gave. */
    static final class Row {
        final String sourceId;
        final Status status;
        final List<Hit> hits;

        Row(String sourceId, Status status, List<Hit> hits) {
            this.sourceId = sourceId;
            this.status = status;
            this.hits = hits == null ? Collections.<Hit>emptyList() : hits;
        }
    }

    /** The text that is searched: trimmed, inner runs of spaces collapsed, capped; "" when nothing is left. */
    static String cleanQuery(String q) {
        if (q == null) return "";
        String t = q.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s{2,}", " ");
        return t.length() > MAX_QUERY ? t.substring(0, MAX_QUERY).trim() : t;
    }

    /**
     * The request URL for {@code query}, or null when the profile has no usable `api` or the query is empty.
     * A `url` starting with "/" is on the source's own origin; otherwise it must be an absolute https URL.
     */
    static String requestUrl(JSONObject api, String origin, String query) {
        String q = cleanQuery(query);
        if (api == null || q.isEmpty()) return null;
        String tpl = api.optString("url", "");
        if (!tpl.contains("{q}")) return null;
        String url = tpl.replace("{q}", enc(q));
        if (url.startsWith("/")) {
            if (origin == null || !origin.startsWith("https://")) return null;
            return origin + url;
        }
        return url.startsWith("https://") ? url : null;
    }

    /** True when the request should carry the source's cookies (same-origin APIs that need the session). */
    static boolean sendsCookies(JSONObject api) {
        return api != null && api.optBoolean("cookies", false);
    }

    /** Result tiles from the site's JSON answer; unknown types (people, collections) and duplicates are dropped. */
    static List<Hit> parse(JSONObject api, String body, int max) {
        List<Hit> out = new ArrayList<>();
        if (api == null || body == null) return out;
        JSONArray items;
        try {
            items = itemsAt(new JSONObject(body), api.optString("items", "results"));
        } catch (JSONException e) {
            return out;
        }
        if (items == null) return out;
        JSONObject open = api.optJSONObject("open");
        if (open == null) return out;
        String typeField = api.optString("type", "");
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.length() && out.size() < max; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String type = typeField.isEmpty() ? "*" : it.optString(typeField, "");
            String tpl = open.optString(type, typeField.isEmpty() ? "" : open.optString("*", ""));
            if (tpl.isEmpty()) continue;
            String id = idOf(it.opt(api.optString("id", "id")));
            String title = first(it, api.optJSONArray("title"), "title");
            if (id.isEmpty() || title.isEmpty()) continue;
            String year = yearOf(first(it, api.optJSONArray("year"), "year"));
            String path = tpl.replace("{id}", enc(id)).replace("{slug}", slug(title)).replace("{year}", year);
            if (!path.startsWith("/") || !seen.add(path)) continue;
            out.add(new Hit(id, title, year, posterOf(it.optString(api.optString("poster", "poster"), ""), api.optString("posterBase", "")),
                    type, path));
        }
        return out;
    }

    /** Current source first, then rows with results (most first), then loading, failed and sources without an API. */
    static List<Row> order(List<Row> rows, String currentSourceId) {
        List<Row> out = new ArrayList<>(rows);
        Collections.sort(out, (a, b) -> {
            int ra = rank(a, currentSourceId), rb = rank(b, currentSourceId);
            if (ra != rb) return Integer.compare(ra, rb);
            return Integer.compare(b.hits.size(), a.hits.size());
        });
        return out;
    }

    private static int rank(Row r, String current) {
        boolean hasHits = r.status == Status.OK && !r.hits.isEmpty();
        if (r.sourceId.equals(current) && hasHits) return 0;
        if (hasHits) return 1;
        if (r.status == Status.LOADING) return 2;
        if (r.status == Status.OK) return 3; // answered, nothing found
        if (r.status == Status.FAILED) return 4;
        return 5;
    }

    /** Recent queries, newest first, case-insensitive duplicates removed, at most {@link #MAX_RECENTS}. */
    static List<String> addRecent(List<String> recents, String query) {
        String q = cleanQuery(query);
        List<String> out = new ArrayList<>();
        if (!q.isEmpty()) out.add(q);
        if (recents != null) {
            for (String r : recents) {
                if (out.size() >= MAX_RECENTS) break;
                String c = cleanQuery(r);
                if (c.isEmpty() || c.equalsIgnoreCase(q)) continue;
                boolean dup = false;
                for (String o : out) dup |= o.equalsIgnoreCase(c);
                if (!dup) out.add(c);
            }
        }
        return out;
    }

    static String recentsToJson(List<String> recents) {
        JSONArray a = new JSONArray();
        for (String r : recents) a.put(r);
        return a.toString();
    }

    static List<String> recentsFromJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return addRecent(out, null);
    }

    // ---------- helpers ----------

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d\\d");

    private static JSONArray itemsAt(JSONObject root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(part);
        }
        return cur instanceof JSONArray ? (JSONArray) cur : null;
    }

    private static String first(JSONObject it, JSONArray fields, String fallback) {
        if (fields == null) return it.optString(fallback, "").trim();
        for (int i = 0; i < fields.length(); i++) {
            String v = it.optString(fields.optString(i, ""), "").trim();
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private static String idOf(Object v) {
        if (v instanceof Number) {
            Number n = (Number) v;
            return n.doubleValue() == n.longValue() ? Long.toString(n.longValue()) : "";
        }
        return v instanceof String ? ((String) v).trim() : "";
    }

    static String yearOf(String date) {
        Matcher m = YEAR.matcher(date == null ? "" : date);
        return m.find() ? m.group() : "";
    }

    /** Kebab-case of the title for routes such as /movie/{id}-{slug}-{year}. */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** Absolute https poster, or a relative path joined to the profile's https base; anything else gives no poster. */
    private static String posterOf(String v, String base) {
        if (v == null || v.isEmpty() || "null".equals(v)) return "";
        if (v.startsWith("https://")) return v;
        if (v.startsWith("/") && base.startsWith("https://")) return base.replaceAll("/+$", "") + v;
        return "";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
