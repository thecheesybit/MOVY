package app.movy.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.function.Supplier;

import android.webkit.WebView;

import app.movy.adblock.AdblockSwitch;
import app.movy.adblock.BlockStats;

/**
 * Native settings panel sliding in from the right: dark, minimal, scrollable. Content comes from {@link SettingsModel}.
 * One instance is reused; it is attached to the content view while open.
 */
final class SettingsSheet extends FrameLayout implements SettingsModel.Actions, HealthChecker.Listener {
    private static final long SLIDE_MS = 240;

    private final Activity activity;
    private final SourceRepository repo;
    private final Prefs prefs;
    private final BlockStats stats;
    private final HealthChecker health;
    private final StorageManager storage;
    private final Supplier<WebView> webView;
    private final boolean adblockConfigured;
    private final String appName;
    private final boolean tv;
    private final ViewGroup panel;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final Drawable icon;
    private boolean open;
    private boolean checking;
    private Long storageBytes;

    SettingsSheet(Activity activity, SourceRepository repo, Prefs prefs, BlockStats stats, HealthChecker health,
                  Supplier<WebView> webView, boolean adblockConfigured, String appName, boolean tv) {
        super(activity);
        this.activity = activity;
        this.repo = repo;
        this.prefs = prefs;
        this.stats = stats;
        this.health = health;
        this.storage = new StorageManager(activity);
        this.webView = webView;
        this.adblockConfigured = adblockConfigured;
        this.appName = appName;
        this.tv = tv;
        Drawable ic = null;
        try {
            ic = activity.getPackageManager().getApplicationIcon(activity.getPackageName());
        } catch (Exception e) {
            // no icon: the About card simply omits it
        }
        icon = ic;
        health.addListener(this);

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        float scale = tv ? 1.3f : 1f;
        int width = Math.min(Ui.dp(activity, 360 * scale), Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.92f));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0B0B0C);
        col.setClickable(true);
        panel = col;
        addView(col, new LayoutParams(width, -1, Gravity.END));

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 6));
        TextView title = new TextView(activity);
        title.setText(R.string.movy_settings);
        title.setTextColor(Ui.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(Ui.MUTED);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(activity.getString(R.string.movy_close));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(v -> close());
        close.setOnFocusChangeListener((v, f) -> close.setBackground(Ui.rounded(activity, f ? 0xFF1C1C1F : 0, 22, Ui.WHITE, f ? 2 : 0)));
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 44)));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(activity, 6), 0, Ui.dp(activity, 6), Ui.dp(activity, 24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    boolean isOpen() {
        return open;
    }

    void show() {
        if (open) return;
        open = true;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(this, new FrameLayout.LayoutParams(-1, -1));
        rebuild();
        measureStorage();
        panel.setTranslationX(panel.getLayoutParams().width);
        setAlpha(0f);
        animate().alpha(1f).setDuration(SLIDE_MS).start();
        panel.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        panel.post(() -> {
            View first = list.findViewWithTag("first");
            if (tv && first != null) first.requestFocus();
        });
    }

    void close() {
        if (!open) return;
        open = false;
        panel.animate().translationX(panel.getWidth()).setDuration(200).start();
        animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (!open && getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        }).start();
        WebView wv = webView.get();
        if (wv != null) wv.requestFocus();
    }

    // ---- model ----

    @Override
    public void rebuild() {
        int y = scroll.getScrollY();
        list.removeAllViews();
        SettingsModel.Inputs in = new SettingsModel.Inputs(repo, prefs, stats, health, adblockConfigured,
                AdblockSwitch.isOn(), appName, checking, storageBytes, icon);
        List<SettingsRow> rows = SettingsModel.build(activity, in, this);
        boolean first = true;
        for (SettingsRow r : rows) {
            View v = SettingsRows.create(activity, r);
            if (first && v.isFocusable()) {
                v.setTag("first");
                first = false;
            }
            list.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void measureStorage() {
        new Thread(() -> {
            long bytes = storage.usage();
            activity.runOnUiThread(() -> {
                storageBytes = bytes;
                if (open) rebuild();
            });
        }, "movy-storage").start();
    }

    // ---- actions ----

    @Override
    public void checkSources() {
        checking = true;
        health.refreshNow();
        rebuild();
    }

    @Override
    public void onHealthChanged() {
        checking = false;
        if (open) rebuild();
    }

    @Override
    public void setAdblock(boolean on) {
        AdblockSwitch.set(on);
        prefs.setAdblockEnabled(on);
        rebuild();
    }

    @Override
    public void confirmClearCache() {
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(R.string.movy_clear_cache_title)
                .setMessage(R.string.movy_clear_cache_msg)
                .setNegativeButton(R.string.movy_cancel, null)
                .setPositiveButton(R.string.movy_clear_cache, (d, w) -> {
                    WebView wv = webView.get();
                    if (wv != null) storage.clearCacheOnly(wv);
                    Toast.makeText(activity, R.string.movy_cache_cleared, Toast.LENGTH_SHORT).show();
                    storageBytes = null;
                    measureStorage();
                    rebuild();
                }).show();
    }

    @Override
    public void confirmSignOut() {
        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(R.string.movy_sign_out_title)
                .setMessage(R.string.movy_sign_out_msg)
                .setNegativeButton(R.string.movy_cancel, null)
                .setPositiveButton(R.string.movy_sign_out_confirm, (d, w) -> signOut())
                .show();
    }

    private void signOut() {
        WebView wv = webView.get();
        if (wv == null) return;
        storage.clearAllSiteData(wv);
        Source a = repo.active();
        close();
        if (a != null) repo.openStart(a.id);
        Toast.makeText(activity, R.string.movy_signed_out, Toast.LENGTH_SHORT).show();
    }
}
