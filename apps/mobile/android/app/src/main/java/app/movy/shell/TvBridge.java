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
    private final Runnable edgeRight;
    private final Runnable gesture;
    private final Consumer<String> media;
    private final java.util.function.BooleanSupplier userLeftFullscreen;
    private final Runnable onReady;
    private final Runnable showChooser;
    private final Consumer<String> switchSource;

    TvBridge(Supplier<LiveConfig> config, Supplier<Source> active, Supplier<String> pageUrl, Runnable edgeRight,
             Runnable gesture, Consumer<String> media, java.util.function.BooleanSupplier userLeftFullscreen,
             Runnable onReady, Runnable showChooser, Consumer<String> switchSource) {
        this.userLeftFullscreen = userLeftFullscreen;
        this.config = config;
        this.active = active;
        this.pageUrl = pageUrl;
        this.edgeRight = edgeRight;
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

    /** tvnav.js: RIGHT was pressed with no focus candidate to the right, so the Island opens. */
    @JavascriptInterface
    public void edgeRight() {
        edgeRight.run();
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
