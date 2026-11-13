package app.movy.shell;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Records what is played (TV, plan stage 6a/6b) from the page's media status: one {@link WatchHistory} entry per title,
 * saved every 15 s and when the video pauses. On TV each unfinished title is also published to the Android home screen's
 * "Continue watching" row (Watch Next) with a movy://open link; a finished one is removed from it. Main thread, except
 * the Watch Next writes, which run on one background thread.
 */
final class WatchRecorder {
    private static final String TAG = "MovyWatch";

    private final Context context;
    private final Prefs prefs;
    private final SourceRepository repo;
    private final Supplier<String> pageUrl;
    private final Supplier<String> pageTitle;
    private final boolean debug;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private List<WatchHistory.Entry> entries;
    private long lastSave;
    private boolean wasPaused = true;

    WatchRecorder(Context context, Prefs prefs, SourceRepository repo, Supplier<String> pageUrl, Supplier<String> pageTitle, boolean debug) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.repo = repo;
        this.pageUrl = pageUrl;
        this.pageTitle = pageTitle;
        this.debug = debug;
        this.entries = WatchHistory.fromJson(prefs.watchHistory());
    }

    List<WatchHistory.Entry> entries() {
        return entries;
    }

    /** tvfs.js media status, about once a second. */
    void onMedia(String json) {
        JSONObject st;
        try {
            st = new JSONObject(json);
        } catch (JSONException | RuntimeException e) {
            return;
        }
        boolean paused = st.optBoolean("paused", true);
        double duration = st.optDouble("duration", 0), position = st.optDouble("currentTime", 0);
        long now = System.currentTimeMillis();
        boolean save = WatchHistory.shouldSave(duration, st.optBoolean("fullscreen", false), paused, wasPaused, now, lastSave);
        wasPaused = paused;
        if (!save) return;
        Source a = repo.active();
        String url = pageUrl.get();
        if (a == null || url == null || !url.equals(repo.lastUrl(a))) return; // only a page that belongs to the active source
        String path = WatchHistory.pathOn(url, WatchHistory.originOf(url));
        String title = WatchHistory.cleanTitle(pageTitle.get(), a.name);
        if (title.isEmpty()) title = WatchHistory.cleanTitle(st.optString("title", ""), a.name);
        if (path == null || title.isEmpty()) return;
        lastSave = now;
        WatchHistory.Entry e = new WatchHistory.Entry(a.id, path, title, position, duration, now);
        entries = WatchHistory.record(entries, e);
        prefs.setWatchHistory(WatchHistory.toJson(entries));
        if (debug) Log.i(TAG, "saved " + a.id + " " + e.percent() + "%");
        io.execute(() -> publish(e));
    }

    /** Sign Out of all sites: the records go with the site data. */
    void clear() {
        entries = WatchHistory.fromJson("[]");
        prefs.setWatchHistory("[]");
    }

    /** {source id, site path} of a movy://open link, or null. */
    static String[] parseLink(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        if (u == null || !"movy".equals(u.getScheme()) || !"open".equals(u.getHost())) return null;
        String src = u.getQueryParameter("src"), p = u.getQueryParameter("p");
        if (src == null || !src.matches("[a-z0-9-]{1,40}") || !WatchHistory.isSitePath(p)) return null;
        return new String[] {src, p};
    }

    // ---------- Android home "Continue watching" (Watch Next) ----------

    private void publish(WatchHistory.Entry e) {
        try {
            String internalId = Integer.toHexString(e.key().hashCode());
            long existing = findProgram(internalId);
            if (e.finished()) {
                if (existing >= 0) context.getContentResolver().delete(TvContractCompat.buildWatchNextProgramUri(existing), null, null);
                return;
            }
            WatchNextProgram p = new WatchNextProgram.Builder()
                    .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(e.at)
                    .setLastPlaybackPositionMillis((int) Math.min(Integer.MAX_VALUE, e.position * 1000))
                    .setDurationMillis((int) Math.min(Integer.MAX_VALUE, e.duration * 1000))
                    .setTitle(e.title)
                    .setInternalProviderId(internalId)
                    .setIntentUri(Uri.parse(WatchHistory.deepLink(e)))
                    .build();
            if (existing >= 0) {
                context.getContentResolver().update(TvContractCompat.buildWatchNextProgramUri(existing), p.toContentValues(), null, null);
            } else {
                context.getContentResolver().insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, p.toContentValues());
            }
        } catch (RuntimeException ex) {
            // No TV provider on this device, or the launcher refuses third-party Watch Next entries: the in-app screen still works.
            if (debug) Log.i(TAG, "watch next unavailable: " + ex.getClass().getSimpleName());
        }
    }

    private long findProgram(String internalId) {
        try (Cursor c = context.getContentResolver().query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
                new String[] {TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (internalId.equals(c.getString(1))) return c.getLong(0);
            }
        }
        return -1;
    }
}
