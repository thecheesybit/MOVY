package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-source settings of the local site shell (profile key {@code shell}): which routes are snapshotted, what to ignore when
 * deciding that a page changed, how stale a snapshot may be. Immutable; null when a source has no shell block.
 */
final class ShellSpec {
    static final long DEFAULT_RACE_MS = 700;
    static final int DEFAULT_MAX_AGE_HOURS = 12;
    static final int DEFAULT_KEEP = 3;
    static final int MAX_KEEP = 5;

    final List<String> routes;
    final List<Pattern> volatilePatterns;
    /** Markers that must appear in the HTML of a snapshot ("#id", ".class" or a tag name); empty = no check. */
    final List<String> roots;
    final long raceMs;
    final long maxAgeMs;
    final int keep;

    ShellSpec(List<String> routes, List<Pattern> volatilePatterns, List<String> roots, long raceMs, int maxAgeHours, int keep) {
        this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
        this.volatilePatterns = Collections.unmodifiableList(new ArrayList<>(volatilePatterns));
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
        this.raceMs = raceMs;
        this.maxAgeMs = maxAgeHours * 60L * 60 * 1000;
        this.keep = Math.max(1, Math.min(MAX_KEEP, keep));
    }

    /** The shell block of a source's TV profile JSON, or null when absent or without routes. */
    static ShellSpec parse(String profileJson) {
        if (profileJson == null) return null;
        try {
            JSONObject sh = new JSONObject(profileJson).optJSONObject("shell");
            if (sh == null) return null;
            List<String> routes = strings(sh.optJSONArray("routes"));
            if (routes.isEmpty()) return null;
            List<Pattern> vol = new ArrayList<>();
            for (String p : strings(sh.optJSONArray("volatile"))) {
                try {
                    vol.add(Pattern.compile(p));
                } catch (PatternSyntaxException ignored) {
                    // a bad pattern from a remote profile must not break serving: skip it
                }
            }
            return new ShellSpec(routes, vol, strings(sh.optJSONArray("html")), sh.optLong("raceMs", DEFAULT_RACE_MS),
                    sh.optInt("maxAgeHours", DEFAULT_MAX_AGE_HOURS), sh.optInt("keepVersions", DEFAULT_KEEP));
        } catch (JSONException e) {
            return null;
        }
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
