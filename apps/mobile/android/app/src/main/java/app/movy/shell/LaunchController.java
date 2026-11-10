package app.movy.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.util.Log;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Launch flow: native splash (never waits for the network), then either the saved source or the chooser.
 * Also owns the "isn't responding" panel and the one-time soft update dialog.
 */
final class LaunchController implements SourceRepository.Listener, ChooserView.Listener {
    private static final long SPLASH_HARD_CAP_MS = 1500;
    private static final int READY_PROGRESS = 70;
    /** The start screen stops waiting for a first paint after this long and reveals the site anyway. */
    private static final long PAINT_CAP_MS = 15_000;

    private final Activity activity;
    private final ViewGroup content;
    private final SourceRepository repo;
    private final Prefs prefs;
    private final String appName;
    private final boolean remote;
    private final boolean isTv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ErrorPanel errorPanel;

    private SplashView splash;
    private ChooserView chooser;
    private StartView startView;
    private String requestedId;
    private boolean tvStart;
    /** Sources whose page painted since the last real load (a paint can come during the splash, before the start screen exists). */
    private final PaintLedger ledger = new PaintLedger();
    private final Runnable paintCap = () -> { if (startView != null && requestedId != null) startView.onPagePainted(requestedId); };
    private long splashShownAt;
    private boolean launching;
    /** TV, very first run: the splash also waits (at most 5 s) for the hidden source warm-up. */
    private boolean firstRunWarmup;
    private boolean adapterReady;
    private boolean warmupDone;
    /** Debug builds only: extra time the splash stays up (screenshots). */
    private long holdMs;

    LaunchController(Activity activity, SourceRepository repo, Prefs prefs, String appName, boolean remote, boolean isTv) {
        this.activity = activity;
        this.content = activity.findViewById(android.R.id.content);
        this.repo = repo;
        this.prefs = prefs;
        this.appName = appName;
        this.remote = remote;
        this.isTv = isTv;
        this.errorPanel = new ErrorPanel(activity);
        content.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        repo.addListener(this);
    }

    ErrorPanel errorPanel() {
        return errorPanel;
    }

    ViewGroup chooserView() {
        return chooser != null ? chooser : startView;
    }

    boolean isChooserShowing() {
        return chooser != null || startView != null;
    }

    /** TV: enables the first-run warm-up wait (call before {@link #start}). */
    void setFirstRunWarmup(boolean on) {
        firstRunWarmup = on;
    }

    boolean isFirstRunWarmup() {
        return firstRunWarmup;
    }

    void setDebugHold(long ms) {
        holdMs = Math.max(0, Math.min(ms, 10_000));
    }

    /** Shows the splash, then opens the saved source or the chooser. */
    void start() {
        LiveConfig cfg = repo.config();
        splash = new SplashView(activity, appName, cfg, BuildConfig.VERSION_NAME, BuildConfig.MOVY_BUILD, remote);
        content.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        splashShownAt = SystemClock.elapsedRealtime();
        if (isTv && repo.resumeSource() == null) {
            startTvScreen(cfg);
            return;
        }
        Source launch = repo.launchSource();
        Log.i(KillLog.TAG, "launch flow: " + (launch != null ? "saved source " + launch.id : "chooser"));
        if (launch != null) {
            launching = true;
            if (firstRunWarmup) splash.setStatus(activity.getString(R.string.movy_preparing_sources));
            repo.switchTo(launch.id);
            long cap = isTv ? SplashPolicy.capMs(firstRunWarmup) : SPLASH_HARD_CAP_MS;
            main.postDelayed(this::dismissSplash, cap + holdMs);
        } else {
            main.postDelayed(this::showChooser, cfg.splashMinMs + holdMs);
        }
        main.postDelayed(() -> checkUpdate(repo.config()), (isTv ? SplashPolicy.NORMAL_CAP_MS : SPLASH_HARD_CAP_MS) + 300);
    }

    /** TV: the focused source loads behind the splash right away; the start screen takes over from the splash. */
    private void startTvScreen(LiveConfig cfg) {
        tvStart = true;
        Source pre = repo.preselected();
        Log.i(KillLog.TAG, "launch flow: tv start screen, focus " + pre.id);
        requestLoad(pre);
        main.postDelayed(this::showStartScreen, cfg.splashMinMs + holdMs);
        main.postDelayed(() -> checkUpdate(repo.config()), SplashPolicy.NORMAL_CAP_MS + 300);
    }

    private void showStartScreen() {
        if (startView != null) return;
        if (!tvStart) { dismissSplash(); return; }
        Source pre = repo.active() != null ? repo.active() : repo.preselected();
        startView = new StartView(activity, repo.config(), pre.id, new StartView.Listener() {
            @Override public void onFocusSource(Source source) { requestLoad(source); }
            @Override public void onOpen(Source source) { openFromStart(); }
        });
        content.addView(startView, new FrameLayout.LayoutParams(-1, -1));
        if (requestedId != null && ledger.hasPainted(requestedId)) startView.onPagePainted(requestedId); // painted during the splash
        if (splash != null) {
            splash.bringToFront();
            dismissSplash();
        }
    }

    /** Loads a source behind the start screen (once per focus change). */
    private void requestLoad(Source s) {
        if (s.id.equals(requestedId)) { // same source again (focus went away and back): its paint may have come already
            if (startView != null && ledger.hasPainted(s.id)) startView.onPagePainted(s.id);
            return;
        }
        requestedId = s.id;
        ledger.loadRequested();
        DohRescue.prewarm(); // idle connections for the page's first burst of calls, while the start screen is up
        main.removeCallbacks(paintCap);
        main.postDelayed(paintCap, PAINT_CAP_MS);
        repo.switchTo(s.id);
    }

    private void openFromStart() {
        tvStart = false;
        StartView v = startView;
        startView = null;
        main.removeCallbacks(paintCap);
        if (v != null) v.dismiss(null);
    }

    /** Debug and shell actions that open a source themselves: the start screen is no longer wanted. */
    void dismissStart() {
        tvStart = false;
        if (startView != null) openFromStart();
    }

    /** TV: the start flow (splash, then the start screen) owns loading: no cover of its own is drawn. */
    boolean isStartFlow() {
        return tvStart;
    }

    /** The page painted its first content (tvpaint.js); only the focused start-screen source counts. */
    void onPagePainted(String sourceId) {
        if (sourceId == null) return;
        ledger.painted(sourceId);
        if (startView != null) startView.onPagePainted(sourceId);
    }

    /** Page progress (UI thread); lets the splash go once the site has started to paint. */
    void onProgress(int percent) {
        if (isTv) return; // TV waits for MovyTvNative.ready() or the 6s hard cap
        if (!launching || splash == null || percent < READY_PROGRESS) return;
        long left = repo.config().splashMinMs + holdMs - (SystemClock.elapsedRealtime() - splashShownAt);
        main.postDelayed(this::dismissSplash, Math.max(0, left));
    }

    /** Called when the page signals MovyTvNative.ready() (TV only). */
    void onAdapterReady() {
        if (!launching || splash == null) return;
        adapterReady = true;
        dismissWhenAllowed();
    }

    /** The hidden warm-up of the other sources has run through (TV first run). */
    void onWarmupDone() {
        warmupDone = true;
        dismissWhenAllowed();
    }

    private void dismissWhenAllowed() {
        if (!launching || splash == null || !SplashPolicy.mayDismiss(firstRunWarmup, adapterReady, warmupDone)) return;
        Log.i(KillLog.TAG, "adapter ready" + (firstRunWarmup ? " and sources warm" : "") + "; dismissing TV splash");
        long left = repo.config().splashMinMs + holdMs - (SystemClock.elapsedRealtime() - splashShownAt);
        main.postDelayed(this::dismissSplash, Math.max(0, left));
    }

    void onMainFrameError() {
        Source a = repo.active();
        if (a == null) return;
        dismissSplash();
        errorPanel.show(a, () -> {
            errorPanel.hide();
            repo.switchTo(a.id);
        });
    }

    void onPageStarted() {
        if (errorPanel.isShowing()) errorPanel.hide();
    }

    private void dismissSplash() {
        launching = false;
        SplashView s = splash;
        splash = null;
        if (s != null) s.dismiss(null);
    }

    private void showChooser() {
        LiveConfig cfg = repo.config();
        Source pre = repo.preselected();
        chooser = new ChooserView(activity, cfg, pre.id, !prefs.askEveryLaunch(), this);
        content.addView(chooser, new FrameLayout.LayoutParams(-1, -1));
        // Chooser sits under the splash; the splash fades to reveal it.
        if (splash != null) {
            splash.bringToFront();
            dismissSplash();
        }
    }

    /** Debug builds only: shows the chooser on top of whatever is loaded (never persists anything by itself). */
    void debugShowChooser() {
        if (chooser == null) showChooser();
    }

    @Override
    public void onChosen(Source source, boolean alwaysOpen) {
        Log.i(KillLog.TAG, "chooser: chose " + source.id + " always=" + alwaysOpen);
        prefs.setAskEveryLaunch(!alwaysOpen);
        if (alwaysOpen) prefs.setDefaultSourceId(source.id);
        ChooserView c = chooser;
        chooser = null;
        repo.switchTo(source.id);
        if (c != null) c.dismiss(null);
    }

    @Override
    public void onConfigChanged(LiveConfig config) {
        if (chooser != null) chooser.setConfig(config);
        checkUpdate(config);
    }

    @Override
    public void onActiveSourceChanged(Source active) {
        errorPanel.hide();
    }

    private void checkUpdate(LiveConfig cfg) {
        if (activity.isFinishing() || cfg.minBuild <= BuildConfig.MOVY_BUILD) return;
        if (prefs.updateShownForVersion() == cfg.contentVersion) return;
        prefs.setUpdateShownForVersion(cfg.contentVersion);
        String msg = cfg.updateMessage.isEmpty() ? activity.getString(R.string.movy_update_default) : cfg.updateMessage;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.movy_update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.movy_ok, null)
                .show();
    }
}
