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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        cfg = ShellConfig.load(this);
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
        rules = new NavRules(initial.recommended().url, initial.allAllowedHosts(), cfg.authHosts, cfg.externalLinks);
        applyHosts(initial);
        repo.addListener(new SourceRepository.Listener() {
            @Override public void onConfigChanged(LiveConfig config) { applyHosts(config); }
            @Override public void onActiveSourceChanged(Source active) { }
        });
        RemoteApplier applier = new RemoteApplier(defaults, repo::config, repo::update, prefs::setLastGoodRemote,
                line -> Log.i("MovyRemote", line));
        if (cfg.adblockEnabled) AdblockEngine.load(this); // background thread; never blocks startup
        super.onCreate(savedInstanceState);
        if (BuildConfig.MOVY_REMOTE) RemoteBackends.start(this, applier, BuildConfig.MOVY_REMOTE_PARAM, cfg.tv, cfg.debug, prefs.instanceId());
    }

    /** Navigation allowlist = union of all enabled sources' hosts + extraAllowedHosts (live). */
    private void applyHosts(LiveConfig c) {
        rules.update(c.allAllowedHosts(), c.startHosts(), c.authHosts);
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
        bridge.setWebViewClient(client);
        wv.setBackgroundColor(Color.parseColor(cfg.backgroundColor));
        loadingUi = new LoadingUi(this);
        chrome = new ShellWebChromeClient(bridge, this, cfg, rules, stats, loadingUi);
        wv.setWebChromeClient(chrome);
        wv.setDownloadListener(new DownloadHandler(this));
        setupSystemBars();
        launch = new LaunchController(this, repo, prefs, getString(R.string.app_name), BuildConfig.MOVY_REMOTE);
        health = new SourceHealth(repo);
        failover = new Failover(this, repo, health, launch, suggested -> island.onFailureShown(suggested));
        settings = new SettingsSheet(this, repo, prefs, stats, health, () -> bridge.getWebView(), cfg.adblockEnabled,
                getString(R.string.app_name), cfg.tv);
        island = new IslandController(this, repo, prefs, health, launch, cfg.tv, settings::show,
                () -> bridge.getWebView().requestFocus());
        chrome.setFullscreenListener(island::onFullscreen);
        loadingUi.setProgressListener(p -> {
            launch.onProgress(p);
            failover.onProgress(p);
        });
        client.setEvents(new PageEvents() {
            @Override public void onUrl(String url) {
                repo.rememberUrl(url);
                island.onUrl(url);
            }
            @Override public void onPageStarted(String url) { failover.onLoadStarted(); }
            @Override public void onPageCommitVisible(String url) { failover.onAlive(); }
            @Override public void onPageFinished(String url) {
                failover.onAlive();
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
        if (cfg.tv) {
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        }
        ViewCompat.requestApplyInsets(content);
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

    /** TV: the MENU key always toggles the Island, whatever the page. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (cfg != null && cfg.tv && island != null && e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) island.menuToggle();
            return true;
        }
        if (island != null && island.handleKey(e)) return true;
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

    /** Debug builds only: {@code --es movy_eval "<js>"} runs JS in the page and logs the result (tag MovyDebug). */
    private void handleDebugIntent(Intent intent) {
        if (!cfg.debug || intent == null || bridge == null) return;
        if (intent.getBooleanExtra("movy_force_fail", false) && failover != null) {
            bridge.getWebView().postDelayed(failover::onFailure, 2500);
        }
        String js = intent.getStringExtra("movy_eval");
        if (js == null) return;
        bridge.getWebView().postDelayed(() -> bridge.getWebView().evaluateJavascript(js,
                v -> Log.i(DEBUG_TAG, "eval result: " + v)), 800);
    }

    private void onBack() {
        WebView wv = bridge.getWebView();
        if (settings != null && settings.isOpen()) {
            settings.close();
            return;
        }
        if (island != null && island.isExpanded()) {
            island.collapse();
            return;
        }
        if (launch.isChooserShowing()) {
            finish();
        } else if (chrome != null && chrome.isFullscreen()) {
            chrome.onHideCustomView();
        } else if (wv.canGoBack()) {
            wv.goBack();
        } else if (cfg.tv) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackMs < EXIT_WINDOW_MS) {
                finish();
            } else {
                lastBackMs = now;
                Toast.makeText(this, R.string.movy_press_back_again, Toast.LENGTH_SHORT).show();
            }
        } else {
            finish();
        }
    }
}
