package app.movy.shell;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Loading UI. Phones: the 3dp page-load progress bar. TV: ONE centred red arc spinner (with an optional one-line caption) used
 * for source switches, slow navigations and player buffering; a switch also covers the screen so there is never a black page.
 * The launch splash is {@link SplashView}.
 */
public final class LoadingUi {
    private static final long FADE_MS = 180;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View bar;
    private final boolean tv;
    private boolean suppressed;
    private IntConsumer progressListener;

    private FrameLayout centre;
    private SpinnerView spinner;
    private TextView caption;
    private boolean screen;
    private boolean navigating;
    private final Set<String> buffering = new HashSet<>();
    private boolean bufferingShown;
    private final Runnable navShow = () -> { navigating = true; refresh(); };
    private final Runnable bufferShow = () -> { bufferingShown = true; refresh(); };

    public LoadingUi(Activity activity) {
        this.activity = activity;
        this.tv = Ui.isTv(activity);
        float d = activity.getResources().getDisplayMetrics().density;
        ViewGroup content = activity.findViewById(android.R.id.content);
        bar = new View(activity);
        bar.setBackgroundColor(SpinnerView.RED);
        bar.setPivotX(0f);
        bar.setScaleX(0f);
        bar.setAlpha(0f);
        if (tv) {
            bar.setVisibility(View.GONE);
            buildCentre(content);
        }
        content.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3 * d), Gravity.TOP));
    }

    private void buildCentre(ViewGroup content) {
        centre = new FrameLayout(activity);
        centre.setVisibility(View.GONE);
        centre.setClickable(false);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        spinner = new SpinnerView(activity);
        col.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        caption = new TextView(activity);
        caption.setTextColor(0xB3FFFFFF);
        caption.setTextSize(16);
        caption.setGravity(Gravity.CENTER);
        caption.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = Ui.dp(activity, 14);
        col.addView(caption, cp);
        centre.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        content.addView(centre, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Fullscreen video: the bar never draws over it; the centred spinner may (buffering only). */
    public void setSuppressed(boolean on) {
        suppressed = on;
        bar.animate().cancel();
        bar.setVisibility(on || tv ? View.GONE : View.VISIBLE);
        if (tv) refresh();
    }

    public void setProgressListener(IntConsumer l) {
        this.progressListener = l;
    }

    // ---------- TV: one spinner ----------

    /** A source is being opened: cover the page with the spinner and the caption ("Opening Cinejoy..."). */
    public void showScreen(String text) {
        if (!tv) return;
        screen = true;
        caption.setText(text == null ? "" : text);
        caption.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        refresh();
    }

    /** The new page painted (or gave up): lift the cover. */
    public void hideScreen() {
        if (!tv) return;
        screen = false;
        refresh();
    }

    /** A main-frame navigation started: the spinner appears if it is still going after 400 ms. */
    public void navigationStarted() {
        if (!tv) return;
        main.removeCallbacks(navShow);
        main.removeCallbacks(bufferShow);
        buffering.clear();
        bufferingShown = false;
        main.postDelayed(navShow, LoadingMode.NAV_DELAY_MS);
    }

    public void navigationFinished() {
        if (!tv) return;
        main.removeCallbacks(navShow);
        navigating = false;
        refresh();
    }

    /** A player (identified by frame token) is buffering; shown 300 ms after the first report, hidden when none buffers. */
    public void setBuffering(String token, boolean on) {
        if (!tv) return;
        if (on) buffering.add(token); else buffering.remove(token);
        main.removeCallbacks(bufferShow);
        if (buffering.isEmpty()) {
            bufferingShown = false;
            refresh();
        } else if (!bufferingShown) {
            main.postDelayed(bufferShow, LoadingMode.BUFFER_DELAY_MS);
        }
    }

    private boolean fullscreen;
    private ViewGroup home;

    /** The fullscreen video host sits above the content: the spinner moves into it while a player is fullscreen, and back after. */
    void setFullscreen(boolean on, ViewGroup host) {
        if (centre != null) {
            if (on && host != null) {
                if (home == null) home = (ViewGroup) centre.getParent();
                if (centre.getParent() != null) ((ViewGroup) centre.getParent()).removeView(centre);
                host.addView(centre, new FrameLayout.LayoutParams(-1, -1));
            } else if (!on && home != null) {
                if (centre.getParent() != null) ((ViewGroup) centre.getParent()).removeView(centre);
                home.addView(centre, new FrameLayout.LayoutParams(-1, -1));
                home = null;
            }
        }
        fullscreen = on;
        if (!on) buffering.clear();
        if (!on) bufferingShown = false;
        refresh();
    }

    private void refresh() {
        if (centre == null) return;
        LoadingMode.Mode m = LoadingMode.resolve(screen, navigating, bufferingShown && !buffering.isEmpty(), fullscreen);
        if (m == LoadingMode.Mode.NONE) {
            if (centre.getVisibility() == View.VISIBLE) {
                centre.animate().cancel();
                centre.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> centre.setVisibility(View.GONE)).start();
            }
            return;
        }
        centre.animate().cancel();
        centre.setBackgroundColor(m == LoadingMode.Mode.SCREEN ? StartView.BACKGROUND : 0x00000000);
        if (m != LoadingMode.Mode.SCREEN) caption.setVisibility(View.GONE);
        else caption.setVisibility(caption.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (centre.getVisibility() != View.VISIBLE) {
            centre.setAlpha(0f);
            centre.setVisibility(View.VISIBLE);
        }
        if (fullscreen) centre.bringToFront();
        centre.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    // ---------- phone bar ----------

    /** Shows the bar immediately (e.g. on a source switch) before the WebView reports progress. */
    public void begin() {
        if (suppressed || tv) return;
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.setScaleX(0.08f);
    }

    /** Called from WebChromeClient.onProgressChanged (UI thread). */
    public void onProgress(int percent) {
        if (progressListener != null) progressListener.accept(percent);
        if (suppressed || tv) return;
        if (percent >= 100) {
            bar.animate().scaleX(1f).setDuration(120).withEndAction(
                    () -> bar.animate().alpha(0f).setDuration(FADE_MS).start()).start();
            return;
        }
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.animate().scaleX(Math.max(percent, 8) / 100f).setDuration(200).start();
    }
}
