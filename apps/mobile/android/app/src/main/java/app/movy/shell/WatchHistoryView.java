package app.movy.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The native Watch History screen (TV, plan stage 6b): Movy's own watch records as a focusable list over a near-black
 * background, newest first, with the source and how far it was watched. OK opens the title on its source; BACK closes
 * (MainActivity routes BACK here). Works the same on every source, including those whose site has no history page.
 */
final class WatchHistoryView extends FrameLayout {
    private static final int BG = 0xF20F1014;
    private static final int ROW = 0x14FFFFFF;
    private static final int ROW_FOCUS = 0x33FFFFFF;

    private final LinearLayout list;

    WatchHistoryView(Context ctx, String title, String emptyText, List<WatchHistory.Entry> entries,
                     Function<String, String> sourceName, Consumer<WatchHistory.Entry> open) {
        super(ctx);
        setBackgroundColor(BG);
        setClickable(true);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 48);
        col.setPadding(pad, Ui.dp(ctx, 36), pad, Ui.dp(ctx, 24));
        TextView h = IosViews.text(ctx, title, 28, UiTokens.WHITE, true);
        h.setAccessibilityHeading(true);
        col.addView(h, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(ctx);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16));
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        addView(col, new LayoutParams(-1, -1));

        if (entries.isEmpty()) {
            TextView empty = IosViews.text(ctx, emptyText, 18, UiTokens.LABEL_SECONDARY, false);
            empty.setFocusable(true);
            list.addView(empty);
        }
        for (WatchHistory.Entry e : entries) list.addView(row(ctx, e, sourceName.apply(e.source), open));
        View first = list.getChildAt(0);
        if (first != null) first.post(first::requestFocus);
    }

    private static View row(Context ctx, WatchHistory.Entry e, String source, Consumer<WatchHistory.Entry> open) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(ctx, 16);
        r.setPadding(p, Ui.dp(ctx, 12), p, Ui.dp(ctx, 12));
        r.setBackground(Ui.rounded(ctx, ROW, 12, 0, 0));
        r.setFocusable(true);
        r.setClickable(true);
        TextView t = IosViews.text(ctx, e.title, 20, UiTokens.WHITE, true);
        t.setSingleLine(true);
        r.addView(t);
        String state = e.finished() ? "Watched" : e.percent() + "% · " + e.minutesLeft() + " min left";
        TextView s = IosViews.text(ctx, (source == null ? "" : source + " · ") + state, 15, UiTokens.LABEL_SECONDARY, false);
        s.setTypeface(Typeface.DEFAULT);
        r.addView(s);
        r.setContentDescription(e.title + ", " + s.getText());
        r.setOnClickListener(v -> open.accept(e));
        r.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Ui.rounded(ctx, has ? ROW_FOCUS : ROW, 12, 0, 0));
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(150).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(ctx, 10);
        r.setLayoutParams(lp);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    static WatchHistoryView show(ViewGroup host, WatchHistoryView v) {
        host.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(160).start();
        return v;
    }

    void close() {
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }
}
