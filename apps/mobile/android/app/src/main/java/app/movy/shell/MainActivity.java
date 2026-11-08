package app.movy.shell;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;

import app.movy.adblock.AdblockEngine;
import app.movy.adblock.AdblockSwitch;
import app.movy.adblock.AdblockWebViewClient;
import app.movy.adblock.BlockStats;
import app.movy.adblock.CosmeticBridge;

public class MainActivity extends BridgeActivity {
    private static final long EXIT_WINDOW_MS = 2000;

    private static final String DEBUG_TAG = "MovyDebug";

    private ShellConfig cfg;
    private NavRules rules;
    private BlockStats stats;
    private ShellWebChromeClient chrome;
    private long lastBackMs;
    private volatile long lastUserFsExitMs = -10_000;
    private String fallbackScript;
    private Prefs prefs;
    private SourceRepository repo;
    private LaunchController launch;
    private LoadingUi loadingUi;
    private boolean clearHistoryOnFinish;
    private CookiePersistence cookies;
    private SourceHealth health;
    private Failover failover;
    private IslandController island;
    private SettingsSheet settings;
    private TvController tvKeys;
    private TvHint tvHint;
    private TvIdle tvIdle;
    private SourcePreloader preloader;
    private android.content.BroadcastReceiver volumeReceiver;
    private TvLifecycle lifecycle;
    private app.movy.shell.AssetCacheInterceptor assetCache;
    /** Top-level URL of the page, readable from WebView binder threads (TvBridge). */
    private volatile String pageUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        cfg = ShellConfig.load(this);
        if (cfg.tv) TvLifecycle.trimColdStart(this); // before the WebView exists: stream segments never pile up
        applyRestOrientation();
        stats = new BlockStats(cfg.debug);
        prefs = new Prefs(this);
        AdblockSwitch.set(cfg.adblockEnabled && prefs.adblockEnabled());
        LiveConfig defaults = Defaults.load(this);
        LiveConfig initial = defaults;
        String lastGood = prefs.lastGoodRemote();
        if (lastGood != null) {
            try {
                initial = LiveConfig.merge(defaults, lastGood, UrlDecoder.IDENTITY);
            } catch (IllegalArgumentException e) {
                Log.i("MovyRemote", "cached remote config ignored: " + e.getMessage());
            }
        }
        repo = new SourceRepository(prefs, initial);
        if (cfg.tv && prefs.tvResumeId() != null) {
            repo.seedResume(prefs.tvResumeId(), prefs.tvResumeUrl());
            prefs.clearTvResume();
        }
        rules = new NavRules(initial.recommended().url, initial.allAllowedHosts(), cfg.authHosts, cfg.externalLinks);
        applyHosts(initial);
        repo.addListener(new SourceRepository.Listener() {
            @Override public void onConfigChanged(LiveConfig config) {
                applyHosts(config);
                reloadTvProfile();
            }
            @Override public void onActiveSourceChanged(Source active) { }
        });
        RemoteApplier applier = new RemoteApplier(defaults, repo::config, repo::update, prefs::setLastGoodRemote,
                line -> Log.i("MovyRemote", line));
        if (cfg.adblockEnabled) AdblockEngine.load(this); // background thread; never blocks startup
        super.onCreate(savedInstanceState);
        if (BuildConfig.MOVY_REMOTE) RemoteBackends.start(this, applier, BuildConfig.MOVY_REMOTE_PARAM, cfg.tv, cfg.debug, prefs.instanceId());
    }

    /** Phones are portrait outside fullscreen video; tablets follow the rotation setting; TV keeps its manifest orientation. */
    private void applyRestOrientation() {
        if (cfg == null || cfg.tv) return;
        boolean phone = OrientationPolicy.isPhone(getResources().getConfiguration().smallestScreenWidthDp);
        setRequestedOrientation(OrientationPolicy.forState(phone, false));
    }

    /** Navigation allowlist = union of all enabled sources' hosts + extraAllowedHosts (live). */
    private void applyHosts(LiveConfig c) {
        rules.update(c.allAllowedHosts(), c.startHosts(), c.authHosts);
        DohRescue.setHosts(c.dohHosts);
    }

    /** Source API for the Island (W5b). */
    public SourceRepository sources() {
        return repo;
    }

    public Prefs prefs() {
        return prefs;
    }

    public StorageManager storage() {
        return new StorageManager(this);
    }

    public ErrorPanel errorPanel() {
        return launch.errorPanel();
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        // Earliest point where the WebView exists but Capacitor has not started loading yet.
        WebView wv = findViewById(com.getcapacitor.android.R.id.webview);
        if (wv != null && cfg != null) fallbackScript = CosmeticBridge.install(this, wv, cfg.cosmetic, cfg.tv, rules, stats, cfg.debug);
    }

    @Override
    protected void load() {
        super.load();
        WebView wv = bridge.getWebView();
        configureSettings(wv);
        // Capacitor installs a global java.net.CookieHandler that wraps the WebView's CookieManager (and drops session
        // cookies in its plugin load/destroy). The shell does not use it; it only gets in the way of HTTP clients such as Firebase.
        java.net.CookieHandler.setDefault(null);
        cookies = new CookiePersistence(this);
        cookies.restore(); // session cookies lost by a process kill, before the first load
        AdblockWebViewClient client = new AdblockWebViewClient(bridge, cfg, rules, stats);
        client.setFallbackScript(fallbackScript);
        assetCache = AssetCacheInterceptor.create(this, cfg);
        client.setAssetCache(assetCache);
        bridge.setWebViewClient(client);
        wv.setBackgroundColor(Color.parseColor(cfg.backgroundColor));
        loadingUi = new LoadingUi(this);
        chrome = new ShellWebChromeClient(bridge, this, cfg, rules, stats, loadingUi);
        wv.setWebChromeClient(chrome);
        // TV has no offline or download features: without a listener the WebView ignores download requests.
        if (!cfg.tv) wv.setDownloadListener(new DownloadHandler(this));
        setupSystemBars();
        launch = new LaunchController(this, repo, prefs, getString(R.string.app_name), BuildConfig.MOVY_REMOTE, cfg.tv);
        health = new SourceHealth(repo);
        failover = new Failover(this, repo, health, launch, suggested -> island.onFailureShown(suggested));
        settings = new SettingsSheet(this, repo, prefs, stats, health, () -> bridge.getWebView(), cfg.adblockEnabled,
                getString(R.string.app_name), cfg.tv);
        island = new IslandController(this, repo, prefs, health, launch, cfg.tv, settings::show,
                () -> bridge.getWebView().requestFocus());
        if (cfg.tv) preloader = new SourcePreloader(this, repo, wv.getSettings().getUserAgentString());
        chrome.setFullscreenListener(on -> {
            island.onFullscreen(on);
            if (on && preloader != null) preloader.stop(); // a player is starting: free the bandwidth and the hidden page
        });
        if (cfg.tv) setupTv();
        loadingUi.setProgressListener(p -> {
            launch.onProgress(p);
            failover.onProgress(p);
        });
        client.setEvents(new PageEvents() {
            @Override public void onUrl(String url) {
                pageUrl = url;
                repo.rememberUrl(url);
                island.onUrl(url);
            }
            @Override public void onPageStarted(String url) {
                if (url != null) pageUrl = url;
                failover.onLoadStarted();
            }
            @Override public void onPageCommitVisible(String url) { failover.onAlive(); }
            @Override public void onPageFinished(String url) {
                failover.onAlive();
                if (preloader != null) preloader.onActivePageFinished();
                if (tvIdle != null) {
                    tvIdle.touch();
                    if (tvIdle.isIdle()) setPageIdle(true);
                }
                if (tvHint != null) Log.i(KillLog.TAG, "tvhint: finished home=" + island.isHomeNow() + " exp=" + island.isExpanded());
                if (tvHint != null && island.isHomeNow() && !island.isExpanded() && !launch.isChooserShowing()) tvHint.onHomeLoaded();
                StorageManager.flushCookies();
                snapshotCookies();
                if (clearHistoryOnFinish) {
                    clearHistoryOnFinish = false;
                    bridge.getWebView().clearHistory();
                }
            }
            @Override public void onMainFrameError() { failover.onFailure(); }
        });
        repo.setLoader((source, url) -> {
            StorageManager.flushCookies();
            clearHistoryOnFinish = true;
            loadingUi.begin();
            failover.onLoadStarted();
            bridge.getWebView().loadUrl(url);
        });
        if (cfg.debug) launch.setDebugHold(getIntent().getLongExtra("movy_hold_splash", 0));
        launch.start();
        health.refreshNow(); // background; the dots fill in as results arrive
        handleDebugIntent(getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });
    }

    /** Content sits below the system bars (status bar visible); TV is always immersive. */
    private void setupSystemBars() {
        View content = findViewById(android.R.id.content);
        int bg = Color.parseColor(cfg.backgroundColor);
        content.setBackgroundColor(bg);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            if (cfg.tv && insets.isVisible(WindowInsetsCompat.Type.systemBars())) v.post(this::applyImmersive);
            if (chrome != null && chrome.isFullscreen()) {
                v.setPadding(0, 0, 0, 0);
            } else {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            }
            return WindowInsetsCompat.CONSUMED;
        });
        boolean dark = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) < 128;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(!dark);
        c.setAppearanceLightNavigationBars(!dark);
        if (cfg.tv) applyImmersive();
        ViewCompat.requestApplyInsets(content);
    }

    /** TV: no system bars, ever. Called on resume, focus, dialogs, IME close, fullscreen exit and whenever bars show up. */
    void applyImmersive() {
        if (cfg != null && cfg.tv) TvImmersive.apply(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onResume() {
        super.onResume();
        applyImmersive();
    }

    public app.movy.shell.AssetCacheInterceptor assetCache() {
        return assetCache;
    }

    private void reloadTvProfile() {
        if (!cfg.tv || bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.reloadProfile&&window.__movyTv.reloadProfile()", null);
    }

    private void setPageIdle(boolean idle) {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.idle&&window.__movyTv.idle(" + idle + ")", null);
    }

    private void configureSettings(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        if (cfg.tv) {
            // TV renders the page at a wider layout width (profile viewportWidth, default 1280 CSS px) and scales it to fit.
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            wv.setVerticalScrollBarEnabled(false);
            wv.setHorizontalScrollBarEnabled(false);
        }
        s.setUserAgentString(cfg.userAgent != null ? cfg.userAgent : chromeLikeUserAgent(s.getUserAgentString()));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setFocusable(true);
        wv.requestFocus();
    }

    /** Drops the "; wv" marker and the "Version/x.y" token so sites treat the view like Chrome. */
    static String chromeLikeUserAgent(String ua) {
        return ua.replace("; wv", "").replaceAll("\\s*Version/\\d+(\\.\\d+)*", "");
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (chrome == null || !chrome.isFullscreen()) applyRestOrientation();
        if (chrome != null) chrome.onConfigurationChanged();
    }

    /** TV: the remote's keys are routed by {@link TvController} before the page sees them. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // BACK in fullscreen is no longer marked as "user left" here: the page may consume it (close a menu), and the
        // real exit in backFromShell() records it.
        if (tvIdle != null && tvIdle.onKey(e)) return true; // first key after idle only wakes the screen
        if (cfg.tv && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0
                && (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)
                && bridge != null && bridge.getWebView() != null) {
            // Volume keys wake a muted banner/trailer: the system still changes the volume, the page just stops being silent.
            bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.media&&window.__movyTv.media('unmute',0)", null);
        }
        if (tvKeys != null && tvKeys.dispatch(e)) return true;
        if (cfg.tv && e.getKeyCode() == KeyEvent.KEYCODE_BACK && chrome != null && chrome.isFullscreen()
                && tvKeys != null && !tvKeys.cursor().isActive() && !nativeOverlayOpen()) {
            // Chromium would exit fullscreen by itself on BACK, before the page can use it (close a menu, hide the controls).
            // The shell takes the key, acts on release, and asks the page first.
            if (e.getAction() == KeyEvent.ACTION_UP) onBack();
            return true;
        }
        if (tvKeys != null && tvKeys.trapPanelKeys(e)) return true;
        if (island != null && island.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    /** Settings, the chooser, the error panel or the expanded Island is up: native views own BACK. */
    private boolean nativeOverlayOpen() {
        return (settings != null && settings.isOpen()) || launch.isChooserShowing()
                || launch.errorPanel().isShowing() || (island != null && island.isExpanded());
    }

    private void setupTv() {
        ViewGroup content = findViewById(android.R.id.content);
        TvCursor cursor = new TvCursor(this, content, () -> bridge.getWebView());
        tvHint = new TvHint(this, prefs);
        tvKeys = new TvController(new TvController.Host() {
            @Override public boolean nativeOverlayActive() {
                return (settings != null && settings.isOpen()) || launch.isChooserShowing()
                        || launch.errorPanel().isShowing() || island.isExpanded();
            }
            @Override public boolean isFullscreen() { return chrome != null && chrome.isFullscreen(); }
            @Override public void toggleIsland() {
                if (tvHint != null) tvHint.dismiss();
                if (settings != null && settings.isOpen()) return;
                island.menuToggle();
            }
            @Override public void pickSlot(int slot) {
                if (launch.isChooserShowing() || (settings != null && settings.isOpen())) return;
                island.pickSlot(slot);
            }
            @Override public void stepSource(int delta) {
                if (launch.isChooserShowing() || (settings != null && settings.isOpen())) return;
                String name = island.stepSource(delta);
                if (name != null) Toast.makeText(MainActivity.this, name, Toast.LENGTH_SHORT).show();
            }
            @Override public void media(String action, int seconds) {
                bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.media('" + action + "'," + seconds + ")", null);
            }
            @Override public boolean trapKeys(KeyEvent ev) {
                if (settings != null && settings.isOpen()) return settings.handleKey(ev);
                if (launch.isChooserShowing()) return TvFocusTrap.handle(ev, launch.chooserView());
                if (launch.errorPanel().isShowing()) return TvFocusTrap.handle(ev, launch.errorPanel());
                return false;
            }
            @Override public boolean deliver(KeyEvent ev) { return dispatchToPage(ev); }
        }, cursor, cfg.debug);
        bridge.getWebView().addJavascriptInterface(new TvBridge(repo::config, repo::active, () -> pageUrl,
                () -> runOnUiThread(() -> {
                    if (tvKeys == null || island.isExpanded() || launch.isChooserShowing()
                            || (settings != null && settings.isOpen()) || (chrome != null && chrome.isFullscreen())) return;
                    if (tvHint != null) tvHint.dismiss();
                    island.menuToggle();
                }),
                () -> runOnUiThread(this::injectGestureKey),
                json -> { if (cfg.debug) Log.i("MovyMedia", json); },
                () -> SystemClock.elapsedRealtime() - lastUserFsExitMs < 3000,
                () -> runOnUiThread(() -> launch.onAdapterReady()),
                () -> runOnUiThread(launch::debugShowChooser),
                sourceId -> runOnUiThread(() -> repo.openStart(sourceId))), TvBridge.NAME);
        registerVolumeUnmute();
        lifecycle = new TvLifecycle(this, () -> bridge == null ? null : bridge.getWebView(), new TvLifecycle.Host() {
            @Override public void setBackgrounded(boolean bg) {
                if (bg) {
                    if (tvIdle != null) tvIdle.suspend();
                    if (tvHint != null) tvHint.dismiss();
                    if (tvKeys != null && tvKeys.cursor().isActive()) tvKeys.cursor().exit();
                } else if (tvIdle != null) {
                    tvIdle.resume();
                    applyImmersive();
                }
            }
            @Override public void saveResume() {
                Source a = repo.active();
                if (a != null) prefs.setTvResume(a.id, repo.lastUrl(a));
            }
            @Override public void flushCookies() {
                StorageManager.flushCookies();
                snapshotCookies();
            }
            @Override public void releaseRam() {
                if (assetCache != null) assetCache.releaseMemory();
            }
        });
        tvIdle = new TvIdle(new TvIdle.Host() {
            @Override public void setNativeIdle(boolean idle) {
                island.setIdle(idle);
                if (settings != null) settings.setAlpha(idle ? 0f : 1f);
                if (idle) {
                    if (tvHint != null) tvHint.dismiss();
                    if (tvKeys != null && tvKeys.cursor().isActive()) tvKeys.cursor().exit();
                }
            }
            @Override public void setPageIdle(boolean idle) { MainActivity.this.setPageIdle(idle); }
            @Override public boolean idleSuspended() {
                return launch.isChooserShowing() || launch.errorPanel().isShowing();
            }
        });
    }

    /** Android TV handles the remote's volume keys in the system, so the app often never sees them: listen for the change itself. */
    private void registerVolumeUnmute() {
        if (volumeReceiver != null) return;
        volumeReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                if (bridge == null || bridge.getWebView() == null) return;
                bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.media&&window.__movyTv.media('unmute',0)", null);
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(this, volumeReceiver,
                new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** A real input event grants the page transient user activation (needed for requestFullscreen after a route change). */
    private void injectGestureKey() {
        WebView wv = bridge.getWebView();
        long t = SystemClock.uptimeMillis();
        wv.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 0));
        wv.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1, 0));
    }

    private boolean dispatchToPage(KeyEvent e) {
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onPause() {
        StorageManager.flushCookies();
        snapshotCookies();
        super.onPause();
    }

    @Override
    public void onStop() {
        StorageManager.flushCookies();
        snapshotCookies();
        super.onStop();
        if (lifecycle != null) lifecycle.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (lifecycle != null) lifecycle.onStart();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (lifecycle != null) lifecycle.onTrimMemory(level);
    }

    @Override
    public void onDestroy() {
        if (volumeReceiver != null) {
            try { unregisterReceiver(volumeReceiver); } catch (IllegalArgumentException ignored) { /* not registered */ }
            volumeReceiver = null;
        }
        if (lifecycle != null) lifecycle.onDestroy();
        super.onDestroy();
    }

    /** TV: leaving the app ends the process so nothing lingers in RAM; other flavors just finish. */
    /** BACK in fullscreen: the WebView itself leaves fullscreen, so the page is told it was the user (no re-entry). */
    private void leaveApp() {
        if (cfg.tv && lifecycle != null) lifecycle.exitApp();
        else finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDebugIntent(intent);
    }

    private void snapshotCookies() {
        if (cookies == null) return;
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (Source s : repo.config().enabledSources()) urls.add(s.url);
        if (bridge != null && bridge.getWebView() != null) urls.add(bridge.getWebView().getUrl());
        cookies.snapshot(urls);
    }

    /** Debug builds only: {@code --es movy_eval "<js>"} runs JS in the page and logs the result (tag MovyDebug); {@code --ez movy_force_chooser true} shows the chooser; {@code --es movy_source <id>} opens that source's start page (ids: 7movies, movy, cinejoy, moonflix). */
    private void handleDebugIntent(Intent intent) {
        if (!cfg.debug || intent == null || bridge == null) return;
        if (intent.getBooleanExtra("movy_force_fail", false) && failover != null) {
            bridge.getWebView().postDelayed(failover::onFailure, 2500);
        }
        if (intent.getBooleanExtra("movy_force_chooser", false) && launch != null) {
            bridge.getWebView().postDelayed(launch::debugShowChooser, 1500);
        }
        String sourceId = intent.getStringExtra("movy_source");
        if (sourceId != null && repo != null) {
            bridge.getWebView().postDelayed(() -> repo.openStart(sourceId), 500);
        }
        String js = intent.getStringExtra("movy_eval");
        if (js == null) return;
        bridge.getWebView().postDelayed(() -> bridge.getWebView().evaluateJavascript(js,
                v -> Log.i(DEBUG_TAG, "eval result: " + v)), 800);
    }

    private void onBack() {
        WebView wv = bridge.getWebView();
        if (tvKeys != null && tvKeys.cursor().isActive()) {
            tvKeys.cursor().exit();
            return;
        }
        if (settings != null && settings.handleBack()) return;
        if (island != null && island.isExpanded()) {
            island.collapse();
            return;
        }
        if (cfg.tv && !launch.isChooserShowing()) {
            // The page gets the first chance (close a player menu, hide the controls); only when it declines does BACK
            // leave fullscreen / go back.
            wv.evaluateJavascript("(window.__movyTv&&window.__movyTv.back)?window.__movyTv.back():false",
                    v -> { if (!"true".equals(v)) backFromShell(wv); });
            return;
        }
        backFromShell(wv);
    }

    private void backFromShell(WebView wv) {
        if (launch.isChooserShowing()) {
            leaveApp();
        } else if (chrome != null && chrome.isFullscreen()) {
            lastUserFsExitMs = SystemClock.elapsedRealtime();
            if (cfg.tv) wv.evaluateJavascript("window.__movyTv&&window.__movyTv.userExitFs&&window.__movyTv.userExitFs()", null);
            chrome.onHideCustomView();
        } else if (wv.canGoBack()) {
            wv.goBack();
        } else if (cfg.tv) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackMs < EXIT_WINDOW_MS) {
                leaveApp();
            } else {
                lastBackMs = now;
                Toast.makeText(this, R.string.movy_press_back_again, Toast.LENGTH_SHORT).show();
            }
        } else {
            finish();
        }
    }
}
