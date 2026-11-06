package app.movy.shell;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Turns {@link SettingsRow}s into views in the chooser's dark look. Focusable rows show a focus ring (TV). */
final class SettingsRows {
    private static final int ROW_MIN_DP = 52;

    private SettingsRows() {}

    static View create(Context ctx, SettingsRow r) {
        switch (r.type) {
            case HEADER: return header(ctx, r);
            case RADIO: return radio(ctx, r);
            case TOGGLE: return toggle(ctx, r);
            case STATUS: return status(ctx, r);
            case ACTION: return action(ctx, r);
            case ABOUT: return about(ctx, r);
            default: return value(ctx, r);
        }
    }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(Ui.dp(ctx, ROW_MIN_DP));
        int p = Ui.dp(ctx, 14);
        l.setPadding(p, Ui.dp(ctx, 6), p, Ui.dp(ctx, 6));
        return l;
    }

    private static void focusable(View v, Runnable click) {
        Context ctx = v.getContext();
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(ctx, 0x00000000, 14, Ui.WHITE, 0));
        v.setOnFocusChangeListener((x, f) -> x.setBackground(Ui.rounded(ctx, f ? 0xFF1C1C1F : 0x00000000, 14, Ui.WHITE, f ? 2 : 0)));
        v.setOnClickListener(x -> click.run());
    }

    private static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private static View header(Context ctx, SettingsRow r) {
        TextView t = text(ctx, r.title.toUpperCase(), 12, Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 22), Ui.dp(ctx, 14), Ui.dp(ctx, 6));
        return t;
    }

    private static View value(Context ctx, SettingsRow r) {
        LinearLayout l = row(ctx);
        l.setMinimumHeight(Ui.dp(ctx, r.value.isEmpty() ? 36 : ROW_MIN_DP));
        l.addView(text(ctx, r.title, 15, r.value.isEmpty() ? Ui.MUTED : 0xFFD1D1D6, false), grow());
        if (!r.value.isEmpty()) l.addView(text(ctx, r.value, 15, Ui.WHITE, true));
        return l;
    }

    private static View status(Context ctx, SettingsRow r) {
        LinearLayout l = row(ctx);
        View dot = new View(ctx);
        dot.setBackground(Ui.ring(ctx, r.color, r.color, 0));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(ctx, 10), Ui.dp(ctx, 10));
        dp.rightMargin = Ui.dp(ctx, 12);
        l.addView(dot, dp);
        l.addView(text(ctx, r.title, 16, Ui.WHITE, false), grow());
        l.addView(text(ctx, r.value, 14, Ui.MUTED, false));
        return l;
    }

    private static View radio(Context ctx, SettingsRow r) {
        LinearLayout l = row(ctx);
        View mark = new View(ctx);
        mark.setBackground(Ui.ring(ctx, r.checked ? r.color : 0x00000000, r.checked ? r.color : 0xFF48484A, 2));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20));
        mp.rightMargin = Ui.dp(ctx, 14);
        l.addView(mark, mp);
        l.addView(text(ctx, r.title, 16, Ui.WHITE, r.checked), grow());
        focusable(l, r.onClick);
        l.setContentDescription(r.title);
        return l;
    }

    private static View toggle(Context ctx, SettingsRow r) {
        LinearLayout l = row(ctx);
        l.addView(text(ctx, r.title, 16, Ui.WHITE, false), grow());
        Switch sw = new Switch(ctx);
        sw.setChecked(r.checked);
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setThumbTintList(ColorStateList.valueOf(Ui.WHITE));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {SpinnerView.RED, 0xFF48484A}));
        l.addView(sw);
        focusable(l, () -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on);
            r.onChange.accept(on);
        });
        l.setContentDescription(r.title);
        return l;
    }

    private static View action(Context ctx, SettingsRow r) {
        LinearLayout l = row(ctx);
        l.addView(text(ctx, r.title, 16, r.danger ? 0xFFFF453A : Ui.WHITE, true), grow());
        focusable(l, r.onClick);
        return l;
    }

    private static View about(Context ctx, SettingsRow r) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 28));
        if (r.icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(r.icon);
            l.addView(iv, new LinearLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 64)));
        }
        TextView name = text(ctx, r.title, 20, Ui.WHITE, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.dp(ctx, 10);
        l.addView(name, np);
        TextView tag = text(ctx, r.value, 14, 0xFFD1D1D6, false);
        tag.setGravity(Gravity.CENTER);
        l.addView(tag);
        for (String line : r.lines) {
            TextView t = text(ctx, line, 13, Ui.MUTED, false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(ctx, 4);
            l.addView(t, lp);
        }
        return l;
    }
}
