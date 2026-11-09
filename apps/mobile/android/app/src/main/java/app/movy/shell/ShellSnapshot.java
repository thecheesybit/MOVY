package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One stored version of a shell route's HTML document (metadata only; the body lives next to it on disk). Immutable. */
final class ShellSnapshot {
    final String source;
    final String routeKey;
    final int tag;
    final String hash;
    final String buildId;
    final long fetchedAt;
    final String url;
    final String etag;
    final String lastModified;
    final int status;
    final String contentType;
    final String csp;
    final boolean bad;
    final List<String> assets;

    ShellSnapshot(String source, String routeKey, int tag, String hash, String buildId, long fetchedAt, String url, String etag,
                  String lastModified, int status, String contentType, String csp, boolean bad, List<String> assets) {
        this.source = source;
        this.routeKey = routeKey;
        this.tag = tag;
        this.hash = hash;
        this.buildId = buildId;
        this.fetchedAt = fetchedAt;
        this.url = url;
        this.etag = etag;
        this.lastModified = lastModified;
        this.status = status;
        this.contentType = contentType;
        this.csp = csp;
        this.bad = bad;
        this.assets = Collections.unmodifiableList(new ArrayList<>(assets));
    }

    ShellSnapshot verified(long when, String newEtag, String newLastModified) {
        return new ShellSnapshot(source, routeKey, tag, hash, buildId, when, url, newEtag != null ? newEtag : etag,
                newLastModified != null ? newLastModified : lastModified, status, contentType, csp, bad, assets);
    }

    ShellSnapshot markedBad() {
        return new ShellSnapshot(source, routeKey, tag, hash, buildId, fetchedAt, url, etag, lastModified, status, contentType, csp, true, assets);
    }

    String toJson() throws JSONException {
        JSONObject o = new JSONObject().put("tag", tag).put("hash", hash).put("buildId", buildId).put("fetchedAt", fetchedAt)
                .put("url", url).put("status", status).put("contentType", contentType).put("bad", bad);
        if (etag != null) o.put("etag", etag);
        if (lastModified != null) o.put("lastModified", lastModified);
        if (csp != null) o.put("csp", csp);
        o.put("assets", new JSONArray(assets));
        return o.toString();
    }

    static ShellSnapshot fromJson(String source, String routeKey, String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<String> assets = new ArrayList<>();
        JSONArray a = o.optJSONArray("assets");
        if (a != null) for (int i = 0; i < a.length(); i++) assets.add(a.getString(i));
        return new ShellSnapshot(source, routeKey, o.getInt("tag"), o.getString("hash"), o.optString("buildId", ""), o.getLong("fetchedAt"),
                o.optString("url", ""), o.has("etag") ? o.getString("etag") : null, o.has("lastModified") ? o.getString("lastModified") : null,
                o.optInt("status", 200), o.optString("contentType", "text/html"), o.has("csp") ? o.getString("csp") : null,
                o.optBoolean("bad", false), assets);
    }
}
