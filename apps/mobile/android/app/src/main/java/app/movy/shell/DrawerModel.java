package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure model of the TV drawer: the items of one source (from its profile's {@code drawer} key), the fixed order shared by
 * every source, which item is current for a page path, and the row geometry.
 * No Android classes: unit tested. Item ids never carry URLs; a path is a site-relative route.
 */
final class DrawerModel {
    static final String SETTINGS_ID = "settings";
    /** Movy's own Watch History screen: added for a source whose profile has no "history" item. */
    static final String NATIVE_HISTORY_ID = "movy-history";

    /** One row. */
    static final class Item {
        final String id;
        final String label;
        final String icon;
        final String path;
        final int rank;

        Item(String id, String label, String icon, String path, int rank) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.path = path;
            this.rank = rank;
        }
    }

    private static final int RANK_SEARCH = 0, RANK_HOME = 1, RANK_MOVIES = 2, RANK_SHOWS = 3, RANK_EXTRA = 4, RANK_LIST = 5,
            RANK_HISTORY = 6, RANK_SETTINGS = 7, RANK_ACCOUNT = 8;

    private DrawerModel() {}

    /** Position of an item id in the fixed order (Sources row is separate; unknown ids are site extras). */
    static int rankOf(String id) {
        switch (id) {
            case "search": return RANK_SEARCH;
            case "home": return RANK_HOME;
            case "movies": return RANK_MOVIES;
            case "shows": return RANK_SHOWS;
            case "mylist":
            case "watchlist": return RANK_LIST;
            case "history": return RANK_HISTORY;
            case SETTINGS_ID: return RANK_SETTINGS;
            case "account": return RANK_ACCOUNT;
            default: return RANK_EXTRA;
        }
    }

    /**
     * The drawer items for a profile JSON (may be null or "{}") and the native Settings item with its label. Sorted by the fixed order; ties keep the profile's order.
     * Items the profile names "settings" are dropped: Settings is always the native sheet.
     */
    static List<Item> build(String profileJson, String settingsLabel) {
        return build(profileJson, settingsLabel, null);
    }

    /** As above, plus the native Watch History item (label {@code historyLabel}) when the site's profile lists none. */
    static List<Item> build(String profileJson, String settingsLabel, String historyLabel) {
        List<Item> out = new ArrayList<>();
        JSONArray items = itemsOf(profileJson);
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String label = o.optString("label", "");
            if (id.isEmpty() || label.isEmpty() || SETTINGS_ID.equals(id)) continue;
            String path = o.has("path") ? o.optString("path", null) : null;
            out.add(new Item(id, label, o.optString("icon", "play"), path, rankOf(id)));
        }
        boolean siteHistory = false;
        for (Item it : out) siteHistory |= it.rank == RANK_HISTORY;
        if (historyLabel != null && !siteHistory) out.add(new Item(NATIVE_HISTORY_ID, historyLabel, "history", null, RANK_HISTORY));
        out.add(new Item(SETTINGS_ID, settingsLabel, "settings", null, RANK_SETTINGS));
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Integer.compare(a.rank, b.rank); // List.sort is stable
            }
        });
        return Collections.unmodifiableList(out);
    }

    private static JSONArray itemsOf(String profileJson) {
        JSONObject d = drawerOf(profileJson);
        return d == null ? null : d.optJSONArray("items");
    }

    private static JSONObject drawerOf(String profileJson) {
        if (profileJson == null || profileJson.isEmpty()) return null;
        try {
            return new JSONObject(profileJson).optJSONObject("drawer");
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** The accent the profile sets for the drawer, or {@code fallback} when absent or malformed. */
    static int accentOf(String profileJson, int fallback) {
        JSONObject d = drawerOf(profileJson);
        String a = d == null ? "" : d.optString("accent", "");
        if (!a.matches("#[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(a.substring(1), 16);
    }

    /** Index of the item whose path is the longest prefix of {@code pagePath} ("/" matches only itself); -1 when none. */
    static int currentIndex(List<Item> items, String pagePath) {
        String cur = pagePath == null || pagePath.isEmpty() ? "/" : pagePath;
        int best = -1;
        int bestLen = -1;
        for (int i = 0; i < items.size(); i++) {
            String p = items.get(i).path;
            if (p == null) continue;
            boolean hit = "/".equals(p) ? "/".equals(cur) : cur.startsWith(p);
            if (hit && p.length() > bestLen) {
                best = i;
                bestLen = p.length();
            }
        }
        return best;
    }

    /** Where the focus lands when the drawer opens: the current item, else Home, else the first row that is not Search. */
    static int openIndex(List<Item> items, String pagePath) {
        int i = currentIndex(items, pagePath);
        if (i >= 0) return i;
        for (int k = 0; k < items.size(); k++) if ("home".equals(items.get(k).id)) return k;
        return items.size() > 1 ? 1 : 0;
    }

    /** Index in {@code items} of the item with {@code id}, or -1. */
    static int indexOf(List<Item> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) return i;
        return -1;
    }

    /** Height of the Sources block above the list, in dp (shared with the view). */
    static final int SOURCE_BLOCK_DP = 92;
    static final int MIN_CELL_DP = 30;
    static final int MAX_CELL_DP = 40;

    /** Height in dp the open list can use: the screen minus the safe margins above and below and the Sources block. */
    static int listViewportDp(int screenDp, int safeVDp) {
        return Math.max(0, screenDp - 2 * safeVDp - SOURCE_BLOCK_DP);
    }

    /** Row height in dp of the open list: as tall as fits {@code rows} in the viewport, between 30 and 40. */
    static int openCellDp(int viewportDp, int rows) {
        if (rows <= 0) return MAX_CELL_DP;
        return Math.max(MIN_CELL_DP, Math.min(MAX_CELL_DP, viewportDp / rows));
    }

    /** Row height in dp so {@code rows} fit the usable height; between 30 and 40 dp. */
    static int cellDp(int usableHeightDp, int rows) {
        int fit = rows <= 0 ? 40 : usableHeightDp / rows;
        return Math.max(MIN_CELL_DP, Math.min(MAX_CELL_DP, fit));
    }

    /** Scroll offset (px) that keeps the row at {@code rowTop..rowTop+rowH} inside a viewport of {@code viewH}, moving as little as possible. */
    static int ensureVisible(int scroll, int rowTop, int rowH, int viewH) {
        if (rowTop < scroll) return rowTop;
        if (rowTop + rowH > scroll + viewH) return rowTop + rowH - viewH;
        return scroll;
    }

    /** Scroll clamped to the content: never negative, never past the end. */
    static int clampScroll(int scroll, int contentH, int viewH) {
        return Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
    }
}
