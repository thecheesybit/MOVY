package app.movy.shell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Native source chooser: title, cards (monogram in an accent ring, name only), "Recommended" pill,
 * "Always open this source" (checked by default) and a Continue button. D-pad navigable.
 * Shows names only, never URLs or hosts.
 */
public final class ChooserView extends FrameLayout {
    public interface Listener {
        void onChosen(Source source, boolean alwaysOpen);
    }

    private static final int TWO_COLUMN_MIN_DP = 700;

    private final Listener listener;
    private final LinearLayout column;
    private final List<View> cards = new ArrayList<>();
    private List<Source> sources = new ArrayList<>();
    private LiveConfig config;
    private String selectedId;
    private boolean always = true;
    private TextView cta;

    public ChooserView(Context ctx, LiveConfig config, String preselectedId, boolean alwaysChecked, Listener listener) {
        super(ctx);
        this.listener = listener;
        this.selectedId = preselectedId;
        this.always = alwaysChecked;
        setBackgroundColor(Ui.BLACK);
        setClickable(true);
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        addView(scroll, new LayoutParams(-1, -1));
        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(ctx, 24);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        build(config, true);
    }

    /** Live re-render when the remote config changes while the chooser is up. */
    public void setConfig(LiveConfig next) {
        build(next, false);
    }

    private void build(LiveConfig cfg, boolean animate) {
        Context ctx = getContext();
        this.config = cfg;
        this.sources = cfg.enabledSources();
        if (find(selectedId) == null) selectedId = cfg.recommended().id;
        column.removeAllViews();
        cards.clear();

        int maxW = Ui.dp(ctx, getResources().getConfiguration().screenWidthDp >= TWO_COLUMN_MIN_DP ? 640 : 460);
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f)); // top spacer

        TextView title = label(ctx, cfg.chooserTitle, 26, Ui.WHITE, true);
        column.addView(title, widthCap(maxW, 0, 0));
        if (animate) Ui.fadeIn(title, 60, Ui.dp(ctx, 10));
        if (!cfg.chooserSubtitle.isEmpty()) {
            TextView sub = label(ctx, cfg.chooserSubtitle, 14, Ui.MUTED, false);
            column.addView(sub, widthCap(maxW, 6, 0));
            if (animate) Ui.fadeIn(sub, 120, Ui.dp(ctx, 10));
        }

        boolean twoCols = getResources().getConfiguration().screenWidthDp >= TWO_COLUMN_MIN_DP;
        LinearLayout row = null;
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            View card = card(ctx, s);
            cards.add(card);
            if (twoCols) {
                if (i % 2 == 0) {
                    row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    column.addView(row, widthCap(maxW, 14, 0));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(i % 2 == 0 ? 0 : Ui.dp(ctx, 7), 0, i % 2 == 0 ? Ui.dp(ctx, 7) : 0, 0);
                row.addView(card, lp);
            } else {
                column.addView(card, widthCap(maxW, 12, 0));
            }
            if (animate) Ui.fadeIn(card, 180 + 70L * i, Ui.dp(ctx, 24));
        }

        CheckBox box = new CheckBox(ctx);
        box.setText(cfg.chooserAlways);
        box.setTextColor(0xFFD1D1D6);
        box.setTextSize(15);
        box.setChecked(always);
        box.setButtonTintList(ColorStateList.valueOf(selectedAccent()));
        box.setOnCheckedChangeListener((b, on) -> always = on);
        box.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        column.addView(box, widthCap(maxW, 18, 0));
        if (animate) Ui.fadeIn(box, 180 + 70L * sources.size(), Ui.dp(ctx, 16));

        cta = label(ctx, cfg.chooserCta, 17, Ui.WHITE, true);
        cta.setFocusable(true);
        cta.setClickable(true);
        cta.setPadding(0, Ui.dp(ctx, 15), 0, Ui.dp(ctx, 15));
        cta.setOnClickListener(v -> confirm());
        cta.setOnFocusChangeListener((v, f) -> styleCta());
        column.addView(cta, widthCap(maxW, 14, 0));
        if (animate) Ui.fadeIn(cta, 260 + 70L * sources.size(), Ui.dp(ctx, 16));
        column.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1f)); // bottom spacer
        refresh();
        View sel = cards.isEmpty() ? cta : cards.get(indexOf(selectedId));
        sel.post(sel::requestFocus);
    }

    private LinearLayout.LayoutParams widthCap(int maxW, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(getContext(), topDp);
        lp.bottomMargin = Ui.dp(getContext(), bottomDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private static TextView label(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private View card(Context ctx, Source s) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(s.id);
        int p = Ui.dp(ctx, 14);
        card.setPadding(p, p, p, p);

        TextView mono = label(ctx, s.monogram(), 20, Ui.WHITE, true);
        mono.setBackground(Ui.ring(ctx, 0xFF0B0B0C, s.accent, 3));
        int d = Ui.dp(ctx, 52);
        card.addView(mono, new LinearLayout.LayoutParams(d, d));

        TextView name = label(ctx, s.name, 18, Ui.WHITE, true);
        name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
        np.leftMargin = Ui.dp(ctx, 14);
        card.addView(name, np);

        if (s.id.equals(config.recommendedSource)) {
            TextView pill = label(ctx, config.chooserRecommended, 11, Ui.onColor(s.accent), true);
            pill.setBackground(Ui.rounded(ctx, s.accent, 10, 0, 0));
            pill.setPadding(Ui.dp(ctx, 9), Ui.dp(ctx, 3), Ui.dp(ctx, 9), Ui.dp(ctx, 3));
            card.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        }
        card.setOnClickListener(v -> {
            selectedId = s.id;
            refresh();
        });
        card.setOnFocusChangeListener((v, f) -> refresh());
        return card;
    }

    private void refresh() {
        Context ctx = getContext();
        for (View c : cards) {
            boolean sel = c.getTag().equals(selectedId);
            Source s = find((String) c.getTag());
            int stroke = c.isFocused() ? Ui.WHITE : sel ? s.accent : Ui.CARD_STROKE;
            float w = c.isFocused() ? 3 : sel ? 2 : 1;
            c.setBackground(Ui.rounded(ctx, sel ? 0xFF1C1C1F : Ui.CARD, 18, stroke, w));
        }
        styleCta();
    }

    private void styleCta() {
        if (cta == null) return;
        int accent = selectedAccent();
        cta.setTextColor(Ui.onColor(accent));
        cta.setBackground(Ui.rounded(getContext(), accent, 16, Ui.WHITE, cta.isFocused() ? 3 : 0));
    }

    private int selectedAccent() {
        Source s = find(selectedId);
        return s != null ? s.accent : SpinnerView.RED;
    }

    private Source find(String id) {
        for (Source s : sources) if (s.id.equals(id)) return s;
        return null;
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return 0;
    }

    private void confirm() {
        Source s = find(selectedId);
        if (s != null) listener.onChosen(s, always);
    }

    /** Fades out and removes itself. */
    public void dismiss(Runnable after) {
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }
}
