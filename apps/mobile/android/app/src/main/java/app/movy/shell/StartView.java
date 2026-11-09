package app.movy.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * TV start screen: the sources as circular "profiles". The focused source loads behind this screen while a ring fills once around
 * its avatar ({@link StartRing}); the site opens when the ring is full and the page has painted. LEFT/RIGHT restarts ring and
 * load for another source, OK opens at once. Names only, never URLs.
 */
final class StartView extends FrameLayout {
    static final int BACKGROUND = 0xFF0F1014;
    private static final long FADE_MS = 200;
    private static final long LOAD_DEBOUNCE_MS = 250;
    private static final float FOCUS_SCALE = 1.1f;
    private static final long SCALE_MS = 160;
    private static final float SPIN_DEG_PER_MS = 360f / 900f;

    interface Listener {
        /** The focused source changed (also the first one): load it behind the screen. */
        void onFocusSource(Source source);

        /** Ring full and page painted (or OK on a painted page): reveal the site. */
        void onOpen(Source source);
    }

    private final Listener listener;
    private final List<Source> sources;
    private final List<AvatarView> avatars = new ArrayList<>();
    private final List<View> columns = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();
    private final StartRing ring;
    private int focused;
    private boolean opened;
    private boolean firstFocusSeen;
    private final Runnable loadFocused = this::loadFocused;
    private final Runnable frame = this::tick;

    StartView(Context ctx, LiveConfig cfg, String focusId, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.sources = cfg.enabledSources();
        this.ring = new StartRing(SystemClock.elapsedRealtime());
        setBackgroundColor(BACKGROUND);
        setClickable(true);
        setClipChildren(false);
        int sh = ctx.getResources().getConfiguration().screenHeightDp;
        boolean dense = TvLayout.dense(sh);
        int safeH = Ui.safeH(ctx), safeV = Ui.safeV(ctx);

        ImageView mark = new ImageView(ctx);
        int icon = ctx.getResources().getIdentifier("splash_icon", "drawable", ctx.getPackageName());
        if (icon != 0) mark.setImageResource(icon);
        int ms = Ui.dp(ctx, 30);
        LayoutParams mp = new LayoutParams(ms, ms, Gravity.TOP | Gravity.START);
        mp.leftMargin = safeH;
        mp.topMargin = safeV;
        addView(mark, mp);

        TextView title = text(ctx, ctx.getString(R.string.movy_start_title), dense ? 24 : 30, UiTokens.WHITE, true);
        title.setAccessibilityHeading(true);
        LayoutParams tp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = safeV + Ui.dp(ctx, dense ? 22 : 40);
        addView(title, tp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int disc = Ui.dp(ctx, dense ? 96 : Math.min(140, Math.max(100, Math.round(sh * 0.26f))));
        int size = AvatarView.viewSizeFor(ctx, disc);
        int gap = Ui.dp(ctx, dense ? 20 : 34);
        focused = Math.max(0, indexOf(focusId));
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setFocusable(true);
            col.setClickable(true);
            col.setClipChildren(false);
            col.setTag(s.id);
            col.setContentDescription(s.name);
            AvatarView av = new AvatarView(ctx, s);
            col.addView(av, new LinearLayout.LayoutParams(size, size));
            TextView name = text(ctx, s.name, dense ? 15 : 18, UiTokens.LABEL_SECONDARY, false);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
            np.topMargin = Ui.dp(ctx, 6);
            col.addView(name, np);
            final int index = i;
            col.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f).setDuration(SCALE_MS).start();
                if (has) onFocused(index);
            });
            col.setOnClickListener(v -> onOk(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = gap;
            row.addView(col, lp);
            avatars.add(av);
            columns.add(col);
            names.add(name);
        }
        LayoutParams rp = new LayoutParams(-2, -2, Gravity.CENTER);
        rp.topMargin = Ui.dp(ctx, dense ? 10 : 24);
        addView(row, rp);
        wireFocus();
        applyNames();
        if (!columns.isEmpty()) post(() -> columns.get(focused).requestFocus());
    }

    private static TextView text(Context ctx, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private void wireFocus() {
        for (View c : columns) c.setId(View.generateViewId());
        for (int i = 0; i < columns.size(); i++) {
            View c = columns.get(i);
            c.setNextFocusLeftId(i == 0 ? c.getId() : columns.get(i - 1).getId());
            c.setNextFocusRightId(i == columns.size() - 1 ? c.getId() : columns.get(i + 1).getId());
            c.setNextFocusUpId(c.getId());
            c.setNextFocusDownId(c.getId());
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return 0;
    }

    private void applyNames() {
        for (int i = 0; i < names.size(); i++) {
            boolean on = i == focused;
            names.get(i).setTextColor(on ? UiTokens.WHITE : UiTokens.LABEL_SECONDARY);
            names.get(i).setTypeface(Typeface.create("sans-serif", on ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ring.restart(SystemClock.elapsedRealtime());
        postOnAnimation(frame);
        loadFocused();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(loadFocused);
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    private void onFocused(int index) {
        boolean first = !firstFocusSeen;
        firstFocusSeen = true;
        boolean changed = index != focused;
        focused = index;
        applyNames();
        if (!changed) return; // the initial focus: ring and load already started on attach
        ring.restart(SystemClock.elapsedRealtime());
        for (AvatarView a : avatars) a.setRing(AvatarView.Mode.NONE, 0f, 0f);
        removeCallbacks(loadFocused);
        postDelayed(loadFocused, first ? 0 : LOAD_DEBOUNCE_MS);
    }

    private void loadFocused() {
        if (opened || sources.isEmpty()) return;
        removeCallbacks(loadFocused);
        listener.onFocusSource(sources.get(focused));
    }

    private void onOk(int index) {
        if (opened) return;
        if (index != focused) { // touch or mouse
            columns.get(index).requestFocus();
            return;
        }
        ring.skip(SystemClock.elapsedRealtime());
    }

    /** The page of {@code sourceId} painted (or gave up waiting); counts only for the focused source. */
    void onPagePainted(String sourceId) {
        if (opened || sources.isEmpty() || !sources.get(focused).id.equals(sourceId)) return;
        ring.pageReady(SystemClock.elapsedRealtime());
    }

    private void tick() {
        if (opened) return;
        long now = SystemClock.elapsedRealtime();
        StartRing.Phase p = ring.phase(now);
        if (!avatars.isEmpty()) {
            AvatarView a = avatars.get(focused);
            if (p == StartRing.Phase.SPINNER) a.setRing(AvatarView.Mode.SPINNER, 1f, (now * SPIN_DEG_PER_MS) % 360f);
            else a.setRing(AvatarView.Mode.PROGRESS, ring.progress(now), 0f);
        }
        if (p == StartRing.Phase.OPEN) {
            opened = true;
            listener.onOpen(sources.get(focused));
            return;
        }
        postOnAnimation(frame);
    }

    /** Fades out and removes itself. */
    void dismiss(Runnable after) {
        opened = true;
        removeCallbacks(loadFocused);
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }
}
