package app.movy.shell;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A small dark "Listening..." pill at the bottom centre while the speech recogniser is up. Fades in and out (alpha only). */
final class ListeningPill {
    private static final long FADE_MS = 160;

    private final TextView view;

    ListeningPill(Context c, ViewGroup parent, String text) {
        view = new TextView(c);
        view.setText(text);
        view.setTextColor(UiTokens.WHITE);
        view.setTextSize(Math.max(14f, 15f * Ui.textScale(c)));
        view.setGravity(Gravity.CENTER);
        int h = Ui.dp(c, 14);
        int v = Ui.dp(c, 8);
        view.setPadding(h * 2, v, h * 2, v);
        view.setBackground(Ui.rounded(c, 0xF01C1C1E, 24, 0x33FFFFFF, 1));
        view.setCompoundDrawablePadding(Ui.dp(c, 8));
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(c, 48);
        parent.addView(view, lp);
    }

    void show() {
        view.setVisibility(View.VISIBLE);
        view.animate().cancel();
        view.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    void hide() {
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> view.setVisibility(View.GONE)).start();
    }
}
