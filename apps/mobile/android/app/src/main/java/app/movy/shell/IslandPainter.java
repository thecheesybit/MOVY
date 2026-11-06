package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.core.graphics.PathParser;

/** All Island drawing. Paints and paths are built once; nothing is allocated while drawing. */
final class IslandPainter {
    static final int HEALTH_UP = 0xFF30D158;
    static final int HEALTH_SLOW = 0xFFFFD60A;
    static final int HEALTH_DOWN = 0xFFFF453A;
    static final int HEALTH_UNKNOWN = 0xFF8E8E93;
    private static final int IDLE_ALPHA = 179; // ~70%
    private static final String GEAR_24 = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58"
            + "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94"
            + "L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29"
            + "L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12"
            + "s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96"
            + "c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
            + "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61"
            + "L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";

    private final IslandGeometry g;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint cardText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path neck = new Path();
    private final Path gear24;
    private final RectF arc = new RectF();
    private final float monoBaseline;
    private final float labelSize;

    IslandPainter(Context ctx, IslandGeometry g, boolean tv) {
        this.g = g;
        float tvScale = tv ? 1.5f : 1f;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11 * tvScale, dm);
        label.setTextSize(labelSize);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mono.setTextSize(18 * g.u);
        mono.setTextAlign(Paint.Align.CENTER);
        mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mono.setColor(Ui.WHITE);
        monoBaseline = -(mono.ascent() + mono.descent()) / 2f;
        stroke.setStyle(Paint.Style.STROKE);
        cardText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13 * tvScale, dm));
        cardText.setColor(0xFFE5E5EA);
        gear24 = PathParser.createPathFromPathData(GEAR_24);
    }

    float labelSize() {
        return labelSize;
    }

    android.graphics.Paint labelPaint() {
        return label;
    }

    private static int a(int alpha255, float f) {
        return Math.max(0, Math.min(255, Math.round(alpha255 * f)));
    }

    void draw(Canvas c, IslandView v) {
        float p = v.progress;
        g.update(p);
        float pc = Math.max(0, Math.min(1, p));
        float bgAlpha = IDLE_ALPHA + (255 - IDLE_ALPHA) * Math.max(pc, v.pressAlpha);
        float content = Math.max(0, Math.min(1, (p - 0.4f) * 2.2f));

        fill.setColor(Ui.BLACK);
        fill.setAlpha(Math.round(bgAlpha));
        IslandShape.body(body, g.viewW, g.top, g.bottom, g.bw, g.fx, g.fy, g.rc);
        c.drawPath(body, fill);
        if (pc < 1f) { // faint rim so the idle tab stays findable on dark pages
            stroke.setColor(Ui.WHITE);
            stroke.setAlpha(a(70, 1 - pc));
            stroke.setStrokeWidth(Math.max(1f, g.u));
            c.drawPath(body, stroke);
        }

        if (content <= 0) return;
        drawGear(c, v, content);
        drawItems(c, v, content);
        drawCard(c, v, content);
    }

    private void drawGear(Canvas c, IslandView v, float content) {
        if (!g.hasGear || g.gearAlpha <= 0) return;
        float r = g.gearD / 2;
        float ga = g.gearAlpha;
        fill.setColor(Ui.BLACK);
        fill.setAlpha(a(255, ga));
        IslandShape.neck(neck, g.gearX, g.bottom, g.gearY - r, g.u);
        c.drawPath(neck, fill);
        c.drawCircle(g.gearX, g.gearY, r, fill);
        boolean focused = v.focusIndex == g.count && v.hasFocus();
        float scale = focused ? 1.1f : 1f;
        float s = 26 * g.u * scale / 24f;
        c.save();
        c.translate(g.gearX - 12 * s, g.gearY - 12 * s);
        c.scale(s, s);
        fill.setColor(Ui.WHITE);
        fill.setAlpha(a(255, ga));
        c.drawPath(gear24, fill);
        c.restore();
        if (focused) {
            stroke.setColor(Ui.WHITE);
            stroke.setAlpha(a(255, ga));
            stroke.setStrokeWidth(2.5f * g.u);
            c.drawCircle(g.gearX, g.gearY, r + 3 * g.u, stroke);
        }
    }

    private void drawItems(Canvas c, IslandView v, float content) {
        c.save();
        c.clipRect(g.viewW - g.bw, g.top + g.rc * 0.5f, g.viewW, g.bottom - g.rc * 0.5f);
        float r = g.circle / 2;
        for (int i = 0; i < v.sources.size(); i++) {
            float cy = g.itemY[i];
            if (cy + g.pitch < g.top || cy - g.pitch > g.bottom) continue;
            drawItem(c, v, i, g.itemX, cy, r, content);
        }
        c.restore();
    }

    private void drawItem(Canvas c, IslandView v, int i, float cx, float cy, float r, float content) {
        Source s = v.sources.get(i);
        boolean active = s.id.equals(v.activeId);
        boolean focused = v.focusIndex == i && v.hasFocus();
        c.save();
        if (focused) c.scale(1.1f, 1.1f, cx, cy);

        fill.setColor(0xFF1C1C1E);
        fill.setAlpha(a(255, content));
        c.drawCircle(cx, cy, r, fill);

        float ringR = r - 1.5f * g.u;
        if (active) {
            stroke.setColor(s.accent);
            stroke.setAlpha(a(255, content));
            stroke.setStrokeWidth(3 * g.u);
            if (v.sweep >= 1f) {
                c.drawCircle(cx, cy, ringR, stroke);
            } else {
                arc.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
                c.drawArc(arc, -90, 360 * v.sweep, false, stroke);
            }
        } else {
            stroke.setColor(0xFF3A3A3C);
            stroke.setAlpha(a(255, content));
            stroke.setStrokeWidth(1.5f * g.u);
            c.drawCircle(cx, cy, ringR, stroke);
        }
        if (v.pulseId != null && v.pulseId.equals(s.id)) {
            float ph = v.pulsePhase;
            stroke.setColor(s.accent);
            stroke.setAlpha(a(220, (1 - ph) * content));
            stroke.setStrokeWidth(2.5f * g.u);
            c.drawCircle(cx, cy, r + (3 + 9 * ph) * g.u, stroke);
        }

        mono.setAlpha(a(255, content));
        c.drawText(v.monograms[i], cx, cy + monoBaseline, mono);

        float dx = cx + r * 0.72f;
        float dy = cy + r * 0.72f;
        fill.setColor(Ui.BLACK);
        fill.setAlpha(a(255, content));
        c.drawCircle(dx, dy, 6 * g.u, fill);
        fill.setColor(healthColor(v.health.status(s.id)));
        fill.setAlpha(a(255, content));
        c.drawCircle(dx, dy, 4 * g.u, fill);

        label.setColor(active ? Ui.WHITE : 0xFFB0B0B5);
        label.setAlpha(a(255, content));
        c.drawText(v.labels[i], cx, cy + r + 6 * g.u - label.ascent(), label);

        if (focused) {
            stroke.setColor(Ui.WHITE);
            stroke.setAlpha(a(255, content));
            stroke.setStrokeWidth(2.5f * g.u);
            c.drawCircle(cx, cy, r + 3 * g.u, stroke);
        }
        c.restore();
    }

    private void drawCard(Canvas c, IslandView v, float content) {
        if (!g.hasCard || v.cardLayout == null || content < 0.3f) return;
        RectF r = g.card;
        fill.setColor(0xFF151517);
        fill.setAlpha(a(255, content));
        c.drawRoundRect(r, 14 * g.u, 14 * g.u, fill);
        stroke.setColor(v.cardAccent);
        stroke.setAlpha(a(255, content));
        stroke.setStrokeWidth(1.5f * g.u);
        c.drawRoundRect(r, 14 * g.u, 14 * g.u, stroke);
        cardText.setAlpha(a(255, content));
        float pad = 12 * g.u;
        c.save();
        c.translate(r.left + pad, r.top + pad);
        v.cardLayout.draw(c);
        c.restore();
        // dismiss cross
        float cx = g.cardClose.centerX();
        float cy = g.cardClose.centerY() + 2 * g.u;
        float k = 4 * g.u;
        stroke.setColor(0xFF8E8E93);
        stroke.setAlpha(a(255, content));
        stroke.setStrokeWidth(1.8f * g.u);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    android.text.TextPaint cardTextPaint() {
        return cardText;
    }

    static int healthColor(HealthChecker.Status s) {
        switch (s) {
            case UP: return HEALTH_UP;
            case SLOW: return HEALTH_SLOW;
            case DOWN: return HEALTH_DOWN;
            default: return HEALTH_UNKNOWN;
        }
    }
}
