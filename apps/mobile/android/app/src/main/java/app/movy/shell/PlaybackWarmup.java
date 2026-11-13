package app.movy.shell;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Warm-up during playback (TV, plan stage 7b): while a video plays smoothly ({@link WarmupGate}), the other sources'
 * shell pages and their hashed scripts and styles are refreshed natively into the asset and shell caches, one source at a
 * time, so a source switch after the movie is quick. No WebView is used (standing rule: no second WebView during
 * playback); the video's own stream is never touched. Each source is refreshed at most once per app session.
 * shortcut: a refresh already in flight is not cancelled by a stall (it is one source's shell plus its assets, small);
 * cancel it in the fetch pool if the Bravia shows `waiting` events during the warm-up.
 */
final class PlaybackWarmup {
    interface Refresher {
        void refresh(Source s);
    }

    private static final String TAG = "MovyWarmup";

    private final SourceRepository repo;
    private final Refresher refresher;
    private final boolean debug;
    private final WarmupGate gate = new WarmupGate();
    private final Set<String> done = new HashSet<>();

    PlaybackWarmup(SourceRepository repo, Refresher refresher, boolean debug) {
        this.repo = repo;
        this.refresher = refresher;
        this.debug = debug;
    }

    /** The page's media status JSON (tvfs.js TV.status), about once a second. Main thread. */
    void onMedia(String json) {
        JSONObject st;
        try {
            st = new JSONObject(json);
        } catch (JSONException | RuntimeException e) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        gate.onStatus(now, st.optBoolean("paused", true), st.optDouble("currentTime", 0), st.optDouble("ahead", 0),
                st.optBoolean("fullscreen", false));
        if (!gate.open(now)) return;
        Source next = nextSource();
        if (next == null) return;
        gate.started(now);
        done.add(next.id);
        if (debug) Log.i(TAG, "refreshing " + next.id + " during playback (" + gate.startedCount() + ")");
        refresher.refresh(next);
    }

    /** The buffering spinner came up: the network is needed by the video. Main thread. */
    void onBuffering(boolean on) {
        if (on) gate.onStall(SystemClock.elapsedRealtime());
    }

    private Source nextSource() {
        Source active = repo.active();
        for (Source s : repo.config().enabledSources()) {
            if (active != null && s.id.equals(active.id)) continue;
            if (!done.contains(s.id)) return s;
        }
        return null;
    }
}
