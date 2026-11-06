package app.movy.shell;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "{Name} isn't responding" with Retry. Shows no URL or host. Hosts a slot for the W5b source-switch suggestion.
 */
public final class ErrorPanel extends FrameLayout {
    private final TextView title;
    private final TextView retry;
    private final LinearLayout extras;

    public ErrorPanel(Context ctx) {
        super(ctx);
        setBackgroundColor(Ui.BLACK);
        setClickable(true);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        addView(col, new LayoutParams(-1, -1));

        title = text(ctx, 22, Ui.WHITE, true);
        col.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView body = text(ctx, 15, Ui.MUTED, false);
        body.setText(R.string.movy_error_body);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = Ui.dp(ctx, 8);
        col.addView(body, bp);

        retry = text(ctx, 16, Ui.WHITE, true);
        retry.setText(R.string.movy_retry);
        retry.setFocusable(true);
        retry.setClickable(true);
        retry.setPadding(Ui.dp(ctx, 36), Ui.dp(ctx, 13), Ui.dp(ctx, 36), Ui.dp(ctx, 13));
        retry.setBackground(Ui.rounded(ctx, SpinnerView.RED, 14, Ui.WHITE, 0));
        retry.setOnFocusChangeListener((v, f) -> retry.setBackground(Ui.rounded(ctx, SpinnerView.RED, 14, Ui.WHITE, f ? 3 : 0)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = Ui.dp(ctx, 24);
        col.addView(retry, rp);

        extras = new LinearLayout(ctx);
        extras.setOrientation(LinearLayout.VERTICAL);
        extras.setGravity(Gravity.CENTER);
        col.addView(extras, new LinearLayout.LayoutParams(-2, -2));
        setVisibility(GONE);
    }

    private static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /** Container for extra controls (W5b adds the "Switch to X" suggestion here). */
    public ViewGroup extrasContainer() {
        return extras;
    }

    public void show(Source source, Runnable onRetry) {
        title.setText(getContext().getString(R.string.movy_error_title, source.name));
        retry.setOnClickListener(v -> onRetry.run());
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(180).start();
        }
        retry.post(retry::requestFocus);
    }

    public void hide() {
        if (getVisibility() != VISIBLE) return;
        animate().cancel();
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }
}
