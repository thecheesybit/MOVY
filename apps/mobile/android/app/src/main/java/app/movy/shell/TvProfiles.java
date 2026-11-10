package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-source TV navigation profiles (selectors only), shipped in the defaults and overridable from Remote Config.
 * Values are kept as compact JSON strings, one per source id, and handed to the page as-is. Unknown keys are dropped
 * (an older app must survive a newer remote profile); known keys are type and size checked.
 */
public final class TvProfiles {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final String[] LISTS = {"header", "bottomBar", "rows", "cards", "focusables", "skip", "firstFocus", "playButtons", "killOnTv"};
    private static final String[] SERVERS = {"toggle", "list", "item", "active", "failed", "frame"};
    private static final String[] EPISODES = {"list", "item", "next"};
    private static final String[] PLAYER_TEXT = {"frame", "video", "root", "fullscreenTarget", "menuConfirm", "closeButton"};
    private static final String[] PLAYER_LISTS = {"fullscreenButton", "controls", "overlayKill", "menu", "hide", "toggles",
        "menuItems", "menuColumns", "menuSelected", "never", "subtitles", "seekBar", "docMarkers"};
    private static final String[] CLOSE_MODES = {"escape", "toggle", "click"};
    private static final String[] INSET_PROPS = {"padding-left", "margin-left", "left"};
    private static final String[] DRAWER_ICONS = {"search", "home", "film", "tv", "sparkles", "bookmark", "settings", "user", "users",
        "history", "clip", "flame", "play"};
    private static final String[] DRAWER_ACTIONS = {"search", "pick", "account"};
    private static final Pattern DRAWER_ID = Pattern.compile("[a-z0-9-]{1,24}");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_DRAWER_ITEMS = 16;
    private static final int MAX_LABEL = 24;
    private static final int MAX_INSET = 20;
    private static final int MAX_ROUTES = 12;
    private static final int MAX_CARD_ACTIVATE = 8;
    private static final int MAX_SELECTOR = 400;
    private static final int MAX_LIST = 40;

    private TvProfiles() {}

    /** Applies {@code remote} (id -> profile object) over {@code base}; the remote wins per top-level key. */
    public static Map<String, String> merge(Map<String, String> base, JSONObject remote) throws JSONException {
        Map<String, String> out = new LinkedHashMap<>(base);
        Iterator<String> ids = remote.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("tvProfiles id");
            JSONObject prof = sanitize(remote.getJSONObject(id));
            JSONObject merged = out.containsKey(id) ? new JSONObject(out.get(id)) : new JSONObject();
            Iterator<String> keys = prof.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                merged.put(k, prof.get(k));
            }
            out.put(id, merged.toString());
        }
        return Collections.unmodifiableMap(out);
    }

    /** Validated copy containing only known keys. */
    static JSONObject sanitize(JSONObject p) throws JSONException {
        JSONObject o = new JSONObject();
        for (String k : LISTS) if (p.has(k)) o.put(k, list(p, k));
        if (p.has("servers")) o.put("servers", texts(p.getJSONObject("servers"), SERVERS));
        if (p.has("episodes")) o.put("episodes", texts(p.getJSONObject("episodes"), EPISODES));
        if (p.has("player")) {
            JSONObject src = p.getJSONObject("player");
            JSONObject pl = texts(src, PLAYER_TEXT);
            for (String k : PLAYER_LISTS) if (src.has(k)) pl.put(k, list(src, k));
            if (src.has("depth")) pl.put("depth", range(src, "depth", 0, 4));
            if (src.has("menuClose")) pl.put("menuClose", menuClose(src.getJSONObject("menuClose")));
            o.put("player", pl);
        }
        if (p.has("loading")) o.put("loading", loading(p.getJSONObject("loading")));
        if (p.has("drawer")) o.put("drawer", drawer(p.getJSONObject("drawer")));
        if (p.has("railInset")) o.put("railInset", railInset(p.getJSONArray("railInset")));
        if (p.has("cardActivate")) o.put("cardActivate", cardActivate(p.getJSONArray("cardActivate")));
        if (p.has("shell")) o.put("shell", shell(p.getJSONObject("shell")));
        if (p.has("viewportWidth")) o.put("viewportWidth", range(p, "viewportWidth", 320, 3840));
        return o;
    }

    /**
     * The native drawer of a source: accent colour, brand name (so the site's own title is never listed as a "recent"), whether detail
     * modals are remembered, and the items (id, label, icon, then a route {@code path} or an {@code action}: search, pick, account).
     */
    private static JSONObject drawer(JSONObject d) throws JSONException {
        JSONObject out = new JSONObject();
        if (d.has("accent")) {
            Object a = d.get("accent");
            if (!(a instanceof String) || !HEX_COLOUR.matcher((String) a).matches()) throw new IllegalArgumentException("drawer.accent");
            out.put("accent", a);
        }
        if (d.has("brand")) out.put("brand", text(d.get("brand"), "drawer.brand", 40));
        if (d.has("modalTitles")) {
            if (!(d.get("modalTitles") instanceof Boolean)) throw new IllegalArgumentException("drawer.modalTitles");
            out.put("modalTitles", d.get("modalTitles"));
        }
        JSONArray items = d.getJSONArray("items");
        if (items.length() > MAX_DRAWER_ITEMS) throw new IllegalArgumentException("drawer.items too long");
        JSONArray outItems = new JSONArray();
        for (int i = 0; i < items.length(); i++) outItems.put(drawerItem(items.getJSONObject(i)));
        out.put("items", outItems);
        return out;
    }

    private static JSONObject drawerItem(JSONObject it) throws JSONException {
        JSONObject o = new JSONObject();
        Object id = it.get("id");
        if (!(id instanceof String) || !DRAWER_ID.matcher((String) id).matches()) throw new IllegalArgumentException("drawer.item.id");
        o.put("id", id);
        o.put("label", text(it.get("label"), "drawer.item.label", MAX_LABEL));
        Object icon = it.get("icon");
        if (!(icon instanceof String) || !oneOf((String) icon, DRAWER_ICONS)) throw new IllegalArgumentException("drawer.item.icon");
        o.put("icon", icon);
        if (it.has("path")) {
            Object p = it.get("path");
            if (!(p instanceof String) || !((String) p).startsWith("/") || ((String) p).length() > 120) throw new IllegalArgumentException("drawer.item.path");
            o.put("path", p);
        }
        if (it.has("action")) {
            Object a = it.get("action");
            if (!(a instanceof String) || !oneOf((String) a, DRAWER_ACTIONS)) throw new IllegalArgumentException("drawer.item.action");
            o.put("action", a);
        }
        for (String k : new String[] {"open", "openText", "item", "itemSel"}) if (it.has(k)) o.put(k, selector(it.get(k), "drawer.item." + k));
        return o;
    }

    private static String text(Object v, String key, int max) {
        String s = selector(v, key);
        if (s.length() > max) throw new IllegalArgumentException(key + " length");
        return s;
    }

    /** Site spinners / skeletons hidden because the app draws the loading spinner. */
    private static JSONObject loading(JSONObject l) throws JSONException {
        JSONObject out = new JSONObject();
        if (l.has("hide")) out.put("hide", list(l, "hide"));
        return out;
    }

    /** How a player menu is closed: Escape, a click on the toggle that opened it, or a click on the given selector. */
    private static JSONObject menuClose(JSONObject c) throws JSONException {
        JSONObject out = new JSONObject();
        Object mode = c.get("mode");
        if (!(mode instanceof String) || !oneOf((String) mode, CLOSE_MODES)) throw new IllegalArgumentException("menuClose.mode");
        out.put("mode", mode);
        if (c.has("selector")) out.put("selector", selector(c.get("selector"), "menuClose.selector"));
        return out;
    }

    /** Page wrappers that get extra left padding while the mini rail is visible. */
    private static JSONArray railInset(JSONArray a) throws JSONException {
        if (a.length() > MAX_INSET) throw new IllegalArgumentException("railInset too long");
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            Object prop = e.get("prop");
            if (!(prop instanceof String) || !oneOf((String) prop, INSET_PROPS)) throw new IllegalArgumentException("railInset.prop");
            out.put(new JSONObject().put("selector", selector(e.get("selector"), "railInset.selector")).put("prop", prop));
        }
        return out;
    }

    /** Cards whose OK press must go on to a target (the inner Play of an expanded preview): selector pairs. */
    private static JSONArray cardActivate(JSONArray a) throws JSONException {
        if (a.length() > MAX_CARD_ACTIVATE) throw new IllegalArgumentException("cardActivate too long");
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            out.put(new JSONObject().put("card", selector(e.get("card"), "cardActivate.card"))
                .put("target", selector(e.get("target"), "cardActivate.target")));
        }
        return out;
    }

    /** Local site shell: the routes to snapshot and how to judge, race and age them. */
    private static JSONObject shell(JSONObject s) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray routes = s.getJSONArray("routes");
        if (routes.length() > MAX_ROUTES) throw new IllegalArgumentException("shell.routes too long");
        JSONArray rs = new JSONArray();
        for (int i = 0; i < routes.length(); i++) {
            Object r = routes.get(i);
            if (!(r instanceof String) || !((String) r).startsWith("/") || ((String) r).indexOf('?') >= 0 || ((String) r).length() > 120) {
                throw new IllegalArgumentException("shell.routes must be paths without a query");
            }
            rs.put(r);
        }
        out.put("routes", rs);
        if (s.has("volatile")) out.put("volatile", list(s, "volatile"));
        if (s.has("root")) out.put("root", list(s, "root"));
        if (s.has("html")) out.put("html", list(s, "html"));
        if (s.has("raceMs")) out.put("raceMs", range(s, "raceMs", 100, 3000));
        if (s.has("maxAgeHours")) out.put("maxAgeHours", range(s, "maxAgeHours", 1, 72));
        if (s.has("keepVersions")) out.put("keepVersions", range(s, "keepVersions", 1, 5));
        return out;
    }

    private static boolean oneOf(String v, String[] allowed) {
        for (String s : allowed) if (s.equals(v)) return true;
        return false;
    }

    private static JSONArray list(JSONObject o, String key) throws JSONException {
        JSONArray a = o.getJSONArray(key);
        if (a.length() > MAX_LIST) throw new IllegalArgumentException(key + " too long");
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) out.put(selector(a.get(i), key));
        return out;
    }

    private static JSONObject texts(JSONObject o, String[] keys) throws JSONException {
        JSONObject out = new JSONObject();
        for (String k : keys) if (o.has(k)) out.put(k, selector(o.get(k), k));
        return out;
    }

    private static String selector(Object v, String key) {
        if (!(v instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        String s = ((String) v).trim();
        if (s.isEmpty() || s.length() > MAX_SELECTOR) throw new IllegalArgumentException(key + " length");
        return s;
    }

    private static int range(JSONObject o, String key, int min, int max) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double d = ((Number) v).doubleValue();
        if (d != Math.floor(d) || d < min || d > max) throw new IllegalArgumentException(key + " out of range");
        return (int) d;
    }
}
