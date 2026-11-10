package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Circular source "profile" avatar for the start screen: accent gradient disc with the source initial, and an optional
 * progress ring (or the same-style spinner arc) drawn just outside the disc. Draws only; the owner animates it.
 */
final class AvatarView extends View {
    enum Mode { NONE, PROGRESS, SPINNER }

    private static final float RING_PAD_DP = 10f;
    private static final float RING_STROKE_DP = 4f;

    private final AvatarPainter disc = new AvatarPainter();
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int accent;
    private final String initial;
    private final float dp;
    private Mode mode = Mode.NONE;
    private float progress;
    private float spin;

    AvatarView(Context ctx, Source s) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        accent = s.accent;
        initial = s.monogram();
        for (Paint p : new Paint[] {track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(RING_STROKE_DP * dp);
        }
        track.setColor(0x2EFFFFFF);
        arc.setColor(SpinnerView.RED);
        arc.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Size of the view for a disc of {@code discPx}: the ring fits inside it. */
    static int viewSizeFor(Context ctx, int discPx) {
        return discPx + 2 * Math.round(RING_PAD_DP * ctx.getResources().getDisplayMetrics().density);
    }

    void setRing(Mode m, float progress, float spinDegrees) {
        if (m == mode && progress == this.progress && spinDegrees == spin) return;
        mode = m;
        this.progress = progress;
        spin = spinDegrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - RING_PAD_DP * dp;
        disc.draw(c, cx, cy, r, accent, initial);
        if (mode == Mode.NONE) return;
        float rr = r + (RING_PAD_DP - RING_STROKE_DP / 2f - 1f) * dp;
        oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
        c.drawCircle(cx, cy, rr, track);
        if (mode == Mode.PROGRESS) c.drawArc(oval, -90f, 360f * progress, false, arc);
        else c.drawArc(oval, spin, 100f, false, arc);
    }
}
