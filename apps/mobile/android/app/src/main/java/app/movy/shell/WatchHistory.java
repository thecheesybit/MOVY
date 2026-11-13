package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure model of Movy's own watch records (plan stage 6a): what was played, on which source, at which site path and how
 * far. Feeds the native Watch History screen, the Android home "Continue watching" row and the movy://open link. Only a
 * source id and a site-relative path are kept, never a host. No Android types: unit tested.
 */
final class WatchHistory {
    private WatchHistory() {}

    static final int MAX = 20;
    /** Watched past this share of the runtime: finished (left in history, out of Continue watching). */
    static final double FINISHED = 0.95;
    /** Shorter videos are trailers, previews or ads: never recorded. */
    static final double MIN_DURATION_S = 300;
    /** How often a playing video's position is saved. */
    static final long SAVE_EVERY_MS = 15_000;

    static final class Entry {
        final String source;
        final String path;
        final String title;
        final double position;
        final double duration;
        final long at;

        Entry(String source, String path, String title, double position, double duration, long at) {
            this.source = source;
            this.path = path;
            this.title = title;
            this.position = position;
            this.duration = duration;
            this.at = at;
        }

        boolean finished() {
            return duration > 0 && position / duration >= FINISHED;
        }

        /** Whole minutes left, at least 1 while unfinished. */
        int minutesLeft() {
            return (int) Math.max(1, Math.round((duration - position) / 60));
        }

        int percent() {
            return duration > 0 ? (int) Math.min(100, Math.round(position * 100 / duration)) : 0;
        }

        /** Same title on the same source (a site that shows details in a modal keeps one path for every title). */
        String key() {
            return source + "\n" + path + "\n" + title.toLowerCase(Locale.ROOT);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("s", source).put("p", path).put("t", title).put("pos", position).put("dur", duration).put("at", at);
        }

        static Entry fromJson(JSONObject o) {
            String s = o.optString("s", ""), p = o.optString("p", ""), t = o.optString("t", "");
            if (s.isEmpty() || !isSitePath(p) || t.isEmpty()) return null;
            return new Entry(s, p, t, Math.max(0, o.optDouble("pos", 0)), Math.max(0, o.optDouble("dur", 0)), o.optLong("at", 0));
        }
    }

    /** True for a route on the site itself ("/x"), never a protocol-relative or absolute URL. */
    static boolean isSitePath(String p) {
        return p != null && p.startsWith("/") && !p.startsWith("//") && p.length() <= 400 && !p.contains("\\") && !p.matches(".*\\s.*");
    }

    /** The site path of {@code url} when it is on {@code origin} ("https://host"), else null. */
    static String pathOn(String url, String origin) {
        if (url == null || origin == null || !url.startsWith(origin)) return null;
        String rest = url.substring(origin.length());
        if (rest.isEmpty()) rest = "/";
        int hash = rest.indexOf('#');
        if (hash >= 0) rest = rest.substring(0, hash);
        return isSitePath(rest) ? rest : null;
    }

    /** "https://host" of a URL, or null. */
    static String originOf(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s <= 0) return null;
        int end = url.indexOf('/', s + 3);
        return end < 0 ? url : url.substring(0, end);
    }

    /** The page title without the site's name ("Dune - Movy" -> "Dune"); "" when only the site's name is left. */
    static String cleanTitle(String title, String siteName) {
        if (title == null) return "";
        String t = title.trim().replaceAll("\\s{2,}", " ");
        if (siteName != null && !siteName.isEmpty()) {
            String n = siteName.trim().toLowerCase(Locale.ROOT);
            for (String sep : new String[] {" | ", " - ", " – ", " — ", " · ", " :: "}) {
                int i = t.toLowerCase(Locale.ROOT).lastIndexOf(sep + n);
                if (i > 0 && i + sep.length() + n.length() >= t.length() - 1) t = t.substring(0, i).trim();
                int j = t.toLowerCase(Locale.ROOT).indexOf(n + sep);
                if (j == 0) t = t.substring(n.length() + sep.length()).trim();
            }
            if (t.equalsIgnoreCase(siteName.trim())) return "";
        }
        t = t.replaceFirst("(?i)^(watch|stream)\\s+", "");
        return t.length() > 120 ? t.substring(0, 120).trim() : t;
    }

    /** {@code e} goes to the front; the older record of the same title is replaced; capped at {@link #MAX}. */
    static List<Entry> record(List<Entry> list, Entry e) {
        List<Entry> out = new ArrayList<>();
        out.add(e);
        for (Entry x : list) {
            if (out.size() >= MAX) break;
            if (!x.key().equals(e.key())) out.add(x);
        }
        return out;
    }

    /** Unfinished entries, newest first: the Continue watching row. */
    static List<Entry> continueWatching(List<Entry> list) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (!e.finished() && e.position >= 60) out.add(e);
        return out;
    }

    /** True when the status is worth recording now: a long video, playing in fullscreen, due for a save or just paused. */
    static boolean shouldSave(double duration, boolean fullscreen, boolean paused, boolean wasPaused, long now, long lastSave) {
        if (duration < MIN_DURATION_S || !fullscreen) return false;
        if (paused) return !wasPaused; // the moment it pauses
        return now - lastSave >= SAVE_EVERY_MS;
    }

    static String toJson(List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) a.put(e.toJson());
        } catch (JSONException ex) {
            return "[]";
        }
        return a.toString();
    }

    static List<Entry> fromJson(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length() && out.size() < MAX; i++) {
                JSONObject o = a.optJSONObject(i);
                Entry e = o == null ? null : Entry.fromJson(o);
                if (e != null) out.add(e);
            }
        } catch (JSONException ex) {
            return new ArrayList<>();
        }
        return out;
    }

    /** movy://open?src=<id>&p=<path>: the link the Android home row opens. */
    static String deepLink(Entry e) {
        try {
            return "movy://open?src=" + java.net.URLEncoder.encode(e.source, "UTF-8") + "&p=" + java.net.URLEncoder.encode(e.path, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
