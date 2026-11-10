package app.movy.shell;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The TV drawer's brain: owns {@link TvDrawerView} (over the WebView), the focus machine ({@link DrawerNav}) and the model
 * ({@link DrawerModel}), turns the remote's keys into drawer moves, and talks to the page (tvdrawer.js) through the host.
 * The page reports LEFT-at-the-edge, whether the strip may show and its path; the drawer asks it to run an item or to
 * hand focus back. Everything here runs on the main thread. No URLs, only ids and names.
 */
final class TvDrawerController {
    interface Host {
        /** Start screen, chooser, error panel or Settings is up: the strip hides and the drawer will not open. */
        boolean blocked();

        boolean fullscreen();

        /** Runs JS in the page (fire and forget). */
        void eval(String js);

        void switchSource(String sourceId);

        void openSettings();
    }

    private static final String TAG = "MovyDrawer";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9:_-]{1,40}");
    private static final long POLL_MS = 500;
    private static final long LATENCY_WINDOW_MS = 1500;

    private final Activity activity;
    private final SourceRepository repo;
    private final Host host;
    private final boolean debug;
    private final TvDrawerView view;
    private final DrawerNav nav = new DrawerNav();
    private final String settingsLabel;
    private List<DrawerModel.Item> items = Collections.emptyList();
    private List<Source> sources = Collections.emptyList();
    private String pagePath = "/";
    private boolean stripAllowed;
    private boolean playerPage;
    private long lastLeftAt;
    private long lastLeftAge;
    private int swallowUp = -1;
    /** The drawer is on screen (DrawerNav may already have closed itself on RIGHT/BACK: this flag is what {@link #close} checks). */
    private boolean shown;
    private boolean lastShown;
    /** The page said LEFT would leave it; set from the binder thread, read on the main thread. */
    private volatile boolean atEdge;
    private final Runnable poll = this::onPoll;

    TvDrawerController(Activity activity, SourceRepository repo, Host host, boolean debug) {
        this.activity = activity;
        this.repo = repo;
        this.host = host;
        this.debug = debug;
        this.settingsLabel = activity.getString(R.string.movy_settings);
        view = new TvDrawerView(activity);
        view.setVisibility(View.GONE);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(view, new FrameLayout.LayoutParams(TvDrawerView.viewWidthPx(activity, screenW), -1));
        rebuild();
        view.postDelayed(poll, POLL_MS);
    }

    boolean isOpen() { return nav.isOpen(); }

    // ---------- reports from the page (UI thread) ----------

    /** LEFT at the page's left edge: the list opens on the current section. The page has parked its focus already. */
    void onEdgeLeft() {
        if (nav.isOpen()) return;
        if (!canOpen()) { hand(true); return; }
        open(DrawerNav.Zone.LIST, false);
    }

    void setAtEdge(boolean on) {
        atEdge = on;
    }

    void onVisible(boolean strip, boolean player) {
        if (debug && (strip != stripAllowed || player != playerPage)) Log.i(TAG, "page reports strip=" + strip + " player=" + player);
        stripAllowed = strip;
        playerPage = player;
        refresh();
    }

    void onState(String path) {
        pagePath = path == null || path.isEmpty() ? "/" : path;
        rebuild();
    }

    void onConfigChanged() {
        rebuild();
    }

    /** A new source became active or the config changed: the strip waits for the new page's report. */
    void onSourceChanged() {
        if (debug) Log.i(TAG, "source changed: strip waits for the page");
        stripAllowed = false;
        playerPage = false;
        pagePath = "/";
        shown = false;
        nav.close();
        view.setOpen(false, false);
        rebuild();
        refresh();
    }

    // ---------- keys ----------

    /** MENU (and its vendor aliases, or a long press of OK): on a page the drawer opens on the Sources row. */
    void menuKey() {
        if (nav.isOpen()) { apply(nav.press(DrawerNav.Key.MENU)); return; }
        if (!canOpen() || playerPage) return;
        open(DrawerNav.Zone.SOURCES, true);
    }

    /** The error panel suggests a source: the Sources row opens with it focused. */
    void openSourcesFor(String sourceId) {
        if (nav.isOpen()) return;
        rebuild();
        int i = indexOfSource(sourceId);
        nav.openSources(i >= 0 ? i : currentSource());
        show(true);
    }

    /** True when the event was consumed by the open drawer. */
    boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && code == swallowUp) { // the key that just closed the drawer: its release must not reach the page as a click
            swallowUp = -1;
            return true;
        }
        if (!nav.isOpen()) {
            if (down && e.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                lastLeftAt = SystemClock.uptimeMillis(); // when the app saw the key; the injector's own event time includes its start-up
                lastLeftAge = lastLeftAt - e.getEventTime();
                if (atEdge && canOpen()) { // the page is at its left edge: no need to wait for it to handle the key
                    atEdge = false;
                    swallowUp = code;
                    open(DrawerNav.Zone.LIST, true);
                    return true;
                }
            }
            return false;
        }
        DrawerNav.Key k = keyOf(code);
        if (k == null) return false;
        if (!down) return true;
        swallowUp = code;
        boolean vertical = k == DrawerNav.Key.UP || k == DrawerNav.Key.DOWN;
        if (e.getRepeatCount() > 0 && !vertical) return true;
        apply(nav.press(k));
        return true;
    }

    private static DrawerNav.Key keyOf(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return DrawerNav.Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return DrawerNav.Key.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return DrawerNav.Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DrawerNav.Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return DrawerNav.Key.OK;
            case KeyEvent.KEYCODE_BACK: return DrawerNav.Key.BACK;
            default: return TvKeys.opensIsland(code) ? DrawerNav.Key.MENU : null;
        }
    }

    private void apply(DrawerNav.Act act) {
        switch (act) {
            case MOVED:
            case STAY:
                view.setFocus(nav.zone(), nav.listIndex(), nav.sourceIndex());
                break;
            case CLOSE_RESTORE:
                close(true);
                break;
            case ACTIVATE_ITEM:
                activateItem();
                break;
            case ACTIVATE_SOURCE:
                activateSource();
                break;
            default:
                break;
        }
    }

    private void activateItem() {
        int i = nav.listIndex();
        if (i < 0 || i >= items.size()) return;
        String id = items.get(i).id;
        if (DrawerModel.SETTINGS_ID.equals(id)) {
            close(true);
            host.openSettings();
            return;
        }
        close(false);
        if (SAFE_ID.matcher(id).matches()) host.eval("window.__movyTv&&window.__movyTv.drawerAction&&window.__movyTv.drawerAction('" + id + "')");
    }

    private void activateSource() {
        int i = nav.sourceIndex();
        if (i < 0 || i >= sources.size()) return;
        Source s = sources.get(i);
        boolean same = i == currentSource();
        close(same); // another source: the page is going away, nothing to restore
        if (!same) host.switchSource(s.id);
    }

    // ---------- open / close ----------

    private boolean canOpen() {
        return !host.blocked() && !host.fullscreen() && (stripAllowed || nav.isOpen());
    }

    private void open(DrawerNav.Zone zone, boolean tellPage) {
        rebuild();
        if (zone == DrawerNav.Zone.LIST) nav.openList(DrawerModel.openIndex(items, pagePath), currentSource());
        else nav.openSources(currentSource());
        if (tellPage) host.eval("window.__movyTv&&window.__movyTv.drawerOpened&&window.__movyTv.drawerOpened()");
        show(true);
        if (debug) {
            StringBuilder ids = new StringBuilder();
            for (DrawerModel.Item it : items) ids.append(ids.length() == 0 ? "" : ",").append(it.id);
            Log.i(TAG, "items " + ids + " zone=" + nav.zone() + " list=" + nav.listIndex() + " source=" + nav.sourceIndex() + "/" + sources.size());
            logLatency();
        }
    }

    private void show(boolean animate) {
        shown = true;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        view.setFocus(nav.zone(), nav.listIndex(), nav.sourceIndex());
        view.setOpen(true, animate);
    }

    /** Closes the drawer; {@code restore} gives the page its previous focus back. */
    void close(boolean restore) {
        if (!shown) return;
        shown = false;
        nav.close();
        view.setFocus(DrawerNav.Zone.CLOSED, 0, 0);
        view.setOpen(false, true);
        hand(restore);
        view.postDelayed(this::refresh, 200);
    }

    private void hand(boolean restore) {
        host.eval("window.__movyTv&&window.__movyTv.drawerClosed&&window.__movyTv.drawerClosed(" + restore + ")");
    }

    // ---------- model / visibility ----------

    private int currentSource() {
        Source a = repo.active();
        return a == null ? -1 : indexOfSource(a.id);
    }

    private int indexOfSource(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return -1;
    }

    private void rebuild() {
        LiveConfig cfg = repo.config();
        Source a = repo.active();
        sources = cfg.enabledSources();
        String profile = a == null ? "{}" : cfg.tvProfileFor(a.id);
        items = DrawerModel.build(profile, settingsLabel);
        nav.setCounts(items.size(), sources.size());
        int cur = DrawerModel.currentIndex(items, pagePath);
        view.setContent(items, sources, currentSource(), DrawerModel.accentOf(profile, a == null ? 0xFFE50914 : a.accent),
                cur >= 0 ? items.get(cur).id : "");
        if (nav.isOpen()) view.setFocus(nav.zone(), nav.listIndex(), nav.sourceIndex());
    }

    /** The collapsed strip shows on browse pages only: not on a player, a modal, the start screen, an error or Settings. */
    void refresh() {
        if (nav.isOpen()) {
            if (host.fullscreen()) close(false);
            return;
        }
        // Not hidden when the TV is idle: the page keeps its inset for the strip, so hiding it would leave an empty dark column.
        boolean shown = stripAllowed && !host.blocked() && !host.fullscreen();
        if (debug && shown != lastShown) Log.i(TAG, "strip shown=" + shown + " (allowed=" + stripAllowed + " blocked=" + host.blocked() + ")");
        lastShown = shown;
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    private void onPoll() {
        try {
            refresh();
        } finally {
            view.postDelayed(poll, POLL_MS);
        }
    }

    private void logLatency() {
        final long opened = SystemClock.uptimeMillis();
        final long from = opened - lastLeftAt < LATENCY_WINDOW_MS ? lastLeftAt : 0;
        view.postOnAnimation(() -> view.postOnAnimation(() -> {
            long frame = SystemClock.uptimeMillis();
            Log.i(TAG, "open: handler " + (from == 0 ? -1 : opened - from) + " ms after the key reached the app, first frame "
                    + (from == 0 ? -1 : frame - from) + " ms after (key was " + lastLeftAge + " ms old at dispatch)");
        }));
    }
}
