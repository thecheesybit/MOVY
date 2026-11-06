package app.movy.shell;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

import java.io.ByteArrayInputStream;

import app.movy.adblock.AdblockEngine;
import app.movy.adblock.BlockStats;

/**
 * Extends Capacitor's chrome client (keeps its permission, geolocation, file chooser and dialog handling)
 * with fullscreen video and popup control.
 */
public class ShellWebChromeClient extends BridgeWebChromeClient {
    private static final long POPUP_TIMEOUT_MS = 3_000;

    private final Activity activity;
    private final ShellConfig cfg;
    private final NavRules rules;
    private final BlockStats stats;
    private final LoadingUi loadingUi;
    private Bitmap transparentPoster;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View customView;
    private CustomViewCallback customCallback;
    private FrameLayout customContainer;
    private int previousOrientation;
    private java.util.function.Consumer<Boolean> fullscreenListener;

    public ShellWebChromeClient(Bridge bridge, Activity activity, ShellConfig cfg, NavRules rules,
                                 BlockStats stats, LoadingUi loadingUi) {
        super(bridge);
        this.loadingUi = loadingUi;
        this.activity = activity;
        this.cfg = cfg;
        this.rules = rules;
        this.stats = stats;
    }

    /** Replaces the grey default play-icon placeholder with nothing. */
    @Override
    public Bitmap getDefaultVideoPoster() {
        if (transparentPoster == null) transparentPoster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        return transparentPoster;
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        super.onProgressChanged(view, newProgress);
        loadingUi.onProgress(newProgress);
    }

    /** Called on the UI thread whenever web fullscreen starts or ends. */
    public void setFullscreenListener(java.util.function.Consumer<Boolean> l) {
        this.fullscreenListener = l;
    }

    public boolean isFullscreen() {
        return customView != null;
    }

    // ---- fullscreen video ----

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        Log.i("MovyShell", "fullscreen enter");
        previousOrientation = activity.getRequestedOrientation();
        if (previousOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }
        customView = view;
        customCallback = callback;
        Window w = activity.getWindow();
        customContainer = new FrameLayout(activity);
        customContainer.setBackgroundColor(0xFF000000);
        customContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((ViewGroup) w.getDecorView()).addView(customContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
        if (fullscreenListener != null) fullscreenListener.accept(true);
    }

    @Override
    public void onHideCustomView() {
        if (customView == null) return;
        Log.i("MovyShell", "fullscreen exit");
        Window w = activity.getWindow();
        ((ViewGroup) w.getDecorView()).removeView(customContainer);
        customContainer.removeAllViews();
        customContainer = null;
        customView = null;
        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.setRequestedOrientation(previousOrientation);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        w.setAttributes(lp);
        WindowCompat.getInsetsController(w, w.getDecorView()).show(WindowInsetsCompat.Type.systemBars());
        ViewCompat.requestApplyInsets(activity.findViewById(android.R.id.content));
        CustomViewCallback cb = customCallback;
        customCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        if (fullscreenListener != null) fullscreenListener.accept(false);
    }

    // ---- popups ----

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        if (!isUserGesture) {
            // No tap behind it: nothing to read a target from, refuse right away (window.open returns null).
            stats.onPopupKilled();
            Log.i(KillLog.TAG, "popup killed (no gesture) total=" + stats.redirectsKilled());
            return false;
        }
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        WebView temp = new WebView(view.getContext());
        temp.getSettings().setJavaScriptEnabled(false);
        temp.setWebViewClient(new PopupClient(view, temp));
        transport.setWebView(temp);
        resultMsg.sendToTarget();
        main.postDelayed(temp::destroy, POPUP_TIMEOUT_MS); // popup that never navigates anywhere
        return true;
    }

    /** Throwaway client: captures the popup's target URL, never lets it hit the network or become visible. */
    private final class PopupClient extends WebViewClient {
        private final WebView opener;
        private final WebView temp;
        private boolean done;

        PopupClient(WebView opener, WebView temp) {
            this.opener = opener;
            this.temp = temp;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return new WebResourceResponse("text/plain", "utf-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            handle(r.getUrl());
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
            if (url != null) handle(Uri.parse(url));
        }

        private void handle(Uri target) {
            String scheme = target.getScheme();
            if (done || scheme == null || scheme.equals("about")) return;
            done = true;
            main.post(temp::destroy);
            String url = target.toString();
            boolean web = "http".equals(scheme) || "https".equals(scheme);
            boolean adHit = web && app.movy.adblock.AdblockSwitch.isOn()
                    && !AdblockEngine.check(url, opener.getUrl(), "document").isAllowed();
            if (rules.popupOpensInMain(url, true, adHit)) {
                Log.i(KillLog.TAG, "popup to allowed host opened in main view");
                opener.loadUrl(url);
                return;
            }
            stats.onPopupKilled();
            Log.i(KillLog.TAG, "popup killed " + KillLog.describe(url, cfg.debug) + " total=" + stats.redirectsKilled());
        }
    }
}
