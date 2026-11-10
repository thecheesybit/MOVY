package app.movy.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure set of the players (one token per video, reported by any frame) that currently buffer. Bounded so that a hostile or
 * broken frame cannot pin the spinner: at most {@link #MAX_TOKENS} players, tokens at most {@link #MAX_TOKEN_LENGTH} characters,
 * and an entry that is not refreshed within {@link #TTL_MS} is forgotten. Time is passed in for tests.
 */
final class BufferingSet {
    static final int MAX_TOKENS = 8;
    static final int MAX_TOKEN_LENGTH = 40;
    static final long TTL_MS = 60_000;

    private final Map<String, Long> seen = new LinkedHashMap<>();

    /** Records a report; false when it was ignored (bad token or the set is full). */
    boolean set(String token, boolean on, long now) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) return false;
        expire(now);
        if (!on) {
            seen.remove(token);
            return true;
        }
        if (!seen.containsKey(token) && seen.size() >= MAX_TOKENS) return false;
        seen.put(token, now);
        return true;
    }

    boolean isEmpty(long now) {
        expire(now);
        return seen.isEmpty();
    }

    void clear() {
        seen.clear();
    }

    private void expire(long now) {
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Long> e : seen.entrySet()) if (now - e.getValue() > TTL_MS) gone.add(e.getKey());
        for (String t : gone) seen.remove(t);
    }
}
