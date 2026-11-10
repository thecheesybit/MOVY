package app.movy.shell;

import android.webkit.JavascriptInterface;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * JS interface "MovyTvNative" (TV only), reachable from every frame. Methods run on a WebView binder thread, so only
 * thread-safe state is read here; anything touching views is posted by the callbacks.
 */
final class TvBridge {
    static final String NAME = "MovyTvNative";

    private final Supplier<LiveConfig> config;
    private final Supplier<Source> active;
    private final Supplier<String> pageUrl;
    private final Runnable gesture;
    private final Consumer<String> media;
    private final java.util.function.BooleanSupplier userLeftFullscreen;
    private final Runnable onReady;
    private final Runnable showChooser;
    private final Consumer<String> switchSource;
    private volatile Consumer<Boolean> shellBoot;
    private volatile Consumer<String> painted;
    private volatile java.util.function.BiConsumer<String, Boolean> buffering;
    private volatile Drawer drawer;
    private volatile boolean debug;

    /** The native drawer's side of tvdrawer.js; every method posts to the main thread. */
    interface Drawer {
        void edgeLeft();

        /** Whether LEFT would leave the page (focus at its left edge): the drawer then opens on LEFT without asking the page. */
        void edgeState(boolean atEdge);

        void visible(boolean strip, boolean player);

        void state(String path, String titlesJson);
    }

    TvBridge(Supplier<LiveConfig> config, Supplier<Source> active, Supplier<String> pageUrl,
             Runnable gesture, Consumer<String> media, java.util.function.BooleanSupplier userLeftFullscreen,
             Runnable onReady, Runnable showChooser, Consumer<String> switchSource) {
        this.userLeftFullscreen = userLeftFullscreen;
        this.config = config;
        this.active = active;
        this.pageUrl = pageUrl;
        this.gesture = gesture;
        this.media = media;
        this.onReady = onReady;
        this.showChooser = showChooser;
        this.switchSource = switchSource;
    }

    /** The profile JSON of the source the page belongs to (matched by the top-level URL, else the active source). */
    static String profileFor(LiveConfig cfg, Source active, String url) {
        Source s = cfg.sourceForUrl(url);
        if (s == null) s = active;
        return cfg.tvProfileFor(s == null ? null : s.id);
    }

    /** tvnav.js / tvfs.js: the profile of the source the top-level origin belongs to ("{}" when it has none). */
    @JavascriptInterface
    public String profile(String topOrigin) {
        String url = topOrigin != null && topOrigin.startsWith("http") ? topOrigin : pageUrl.get();
        return profileFor(config.get(), active.get(), url);
    }

    /** tvdrawer.js: LEFT was pressed at the page's left edge (focus already parked): the drawer opens. */
    @JavascriptInterface
    public void edgeLeft() {
        Drawer d = drawer;
        if (d != null) d.edgeLeft();
    }

    /** tvdrawer.js: LEFT has nowhere to go on the page (true) or the focus can still move left (false). */
    @JavascriptInterface
    public void edgeState(boolean atEdge) {
        Drawer d = drawer;
        if (d != null) d.edgeState(atEdge);
    }

    /** tvdrawer.js: whether the collapsed strip may show (not in a player, fullscreen or modal), and whether a player is up. */
    @JavascriptInterface
    public void drawerVisible(boolean strip, boolean player) {
        Drawer d = drawer;
        if (d != null) d.visible(strip, player);
    }

    /** tvdrawer.js: the page path and the recent titles (JSON array of strings) the drawer lists. */
    @JavascriptInterface
    public void drawerState(String path, String titlesJson) {
        Drawer d = drawer;
        if (d != null) d.state(path, titlesJson);
    }

    void setDrawer(Drawer d) {
        this.drawer = d;
    }

    /** tvfs.js: fullscreen was refused for lack of a user gesture; a synthetic key from here grants one. */
    @JavascriptInterface
    public void gesture() {
        gesture.run();
    }

    /** tvfs.js: true when BACK just left fullscreen (so the page must not re-enter it). Read synchronously, no ordering race. */
    @JavascriptInterface
    public boolean userLeftFullscreen() {
        return userLeftFullscreen.getAsBoolean();
    }

    /** tvfs.js: status of the main video ({paused, currentTime, duration, ...}) for the native player bar. */
    @JavascriptInterface
    public void onMedia(String json) {
        media.accept(json);
    }

    /** tvfs.js / tvnav.js: signals that viewport, profile CSS, cosmetic rules, and initial focus are applied. */
    @JavascriptInterface
    public void ready() {
        if (onReady != null) onReady.run();
    }

    /** tvshell.js: the page rendered (or did not) after it was served from a local snapshot. */
    @JavascriptInterface
    public void shellBoot(boolean ok, String why) {
        java.util.function.Consumer<Boolean> c = shellBoot;
        if (c != null) c.accept(ok);
    }

    /**
     * tvpaint.js: the page showed its first real content (a poster). {@code url} is the reporting document's own address; the app
     * only counts it when that is a source page and the page now showing (a late report of the previous document or of an ad
     * frame is ignored, see {@link PaintLedger#accept}).
     */
    @JavascriptInterface
    public void painted(String url) {
        Consumer<String> r = painted;
        if (r != null && url != null && url.length() < 2048) r.accept(url);
    }

    /** loading.js: a player in the frame identified by {@code token} started or stopped buffering. */
    @JavascriptInterface
    public void buffering(String token, boolean on) {
        java.util.function.BiConsumer<String, Boolean> b = buffering;
        if (b != null && token != null) b.accept(token, on);
    }

    /** tvgate.js: debug builds only; lets the page log config mistakes (an invalid selector) instead of skipping them silently. */
    @JavascriptInterface
    public boolean debug() {
        return debug;
    }

    void setDebug(boolean on) {
        this.debug = on;
    }

    void setPainted(Consumer<String> r) {
        this.painted = r;
    }

    void setBuffering(java.util.function.BiConsumer<String, Boolean> b) {
        this.buffering = b;
    }

    void setShellBoot(java.util.function.Consumer<Boolean> c) {
        this.shellBoot = c;
    }

    /** tvnav.js: opens the native source chooser dialog. */
    @JavascriptInterface
    public void showChooser() {
        if (showChooser != null) showChooser.run();
    }

    /** tvnav.js: switches directly to the given source ID (7movies, movy, cinejoy, moonflix). */
    @JavascriptInterface
    public void switchSource(String sourceId) {
        if (switchSource != null && sourceId != null) switchSource.accept(sourceId);
    }
}
