package app.movy.shell;

import android.graphics.RectF;

/**
 * Layout math of the Island, in pixels, recomputed in place (no allocation) for an expansion progress {@code p}
 * (0 collapsed, 1 expanded, may overshoot). The view is a full-size overlay; the shape is fused to its right edge.
 */
final class IslandGeometry {
    /** Pixels per dp including the TV scale. */
    final float u;
    final float tabW, tabH, pillW, pitch, circle, padTop, padBottom, gearD, gearGap, margin;
    final float fyCollapsed, fyExpanded, fxExpanded, rcCollapsed, rcExpanded, hitW;

    float viewW, viewH;
    int count;
    /** Collapsed centre as a 0..1 fraction of the view height. */
    float centerFrac = 0.4f;
    float scroll;

    // results of update()
    float bw, top, bottom, fx, fy, rc;
    float scrollMax, pillH;
    float[] itemY = new float[8];
    float itemX, gearX, gearY, gearAlpha;
    boolean hasGear = true;
    final RectF card = new RectF();
    final RectF cardClose = new RectF();
    boolean hasCard;

    IslandGeometry(float density, boolean tv) {
        u = density * (tv ? 1.5f : 1f);
        tabW = 6 * u;
        tabH = 56 * u;
        pillW = 76 * u;
        pitch = 78 * u;
        circle = 44 * u;
        padTop = 14 * u;
        padBottom = 8 * u;
        gearD = 52 * u;
        gearGap = 10 * u;
        margin = 8 * u;
        fyCollapsed = 14 * u;
        fyExpanded = 34 * u;
        fxExpanded = 28 * u;
        rcCollapsed = 3 * u;
        rcExpanded = 18 * u;
        hitW = 28 * u;
    }

    void setSize(float w, float h, int itemCount) {
        viewW = w;
        viewH = h;
        count = itemCount;
        if (itemY.length < itemCount) itemY = new float[itemCount];
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    void update(float p) {
        float natural = padTop + count * pitch + padBottom;
        float gearBlock = hasGear ? gearGap + gearD : 0;
        float availPill = viewH - 2 * margin - 2 * fyExpanded - gearBlock;
        float minPill = Math.min(natural, 2 * pitch + padTop + padBottom);
        pillH = Math.max(Math.min(natural, availPill), minPill);
        scrollMax = Math.max(0, natural - pillH);
        scroll = clamp(scroll, 0, scrollMax);

        float cC = collapsedCenter();
        float tC = cC - tabH / 2;
        float tE = clamp(cC - pillH / 2, margin + fyExpanded, Math.max(margin + fyExpanded, viewH - margin - fyExpanded - gearBlock - pillH));
        top = lerp(tC, tE, p);
        bottom = top + lerp(tabH, pillH, p);
        bw = Math.max(tabW, lerp(tabW, pillW, p));
        rc = lerp(rcCollapsed, rcExpanded, clamp(p, 0, 1.2f));
        fy = lerp(fyCollapsed, fyExpanded, clamp(p, 0, 1.2f));
        fx = Math.max(0, Math.min(lerp(2 * u, fxExpanded, clamp(p, 0, 1.2f)), bw - rc));
        itemX = viewW - bw / 2;
        for (int i = 0; i < count; i++) itemY[i] = top + padTop - scroll + i * pitch + circle / 2;
        float e = clamp((p - 0.45f) * 1.9f, 0, 1);
        gearAlpha = e;
        gearX = viewW - bw / 2;
        float gearRest = bottom + gearGap + gearD / 2;
        gearY = lerp(bottom - gearD / 2, gearRest, e);
        layoutCard();
    }

    float collapsedCenter() {
        float lo = margin + fyCollapsed + tabH / 2;
        float hi = Math.max(lo, viewH - margin - fyCollapsed - tabH / 2);
        return clamp(centerFrac * viewH, lo, hi);
    }

    /** Card sits left of the pill, aligned with its top; height is set by the painter from its text layout. */
    float cardWidth() {
        return Math.min(230 * u, Math.max(0, viewW - pillW - 28 * u));
    }

    private void layoutCard() {
        float w = cardWidth();
        float h = card.height();
        float l = viewW - bw - 10 * u - w;
        card.set(l, top, l + w, top + h);
        float s = 26 * u;
        cardClose.set(card.right - s, card.top, card.right, card.top + s);
    }

    // ---- hit tests (px in view coordinates) ----

    boolean tabHit(float x, float y) {
        return x >= viewW - hitW && y >= top - fy - 8 * u && y <= bottom + fy + 8 * u;
    }

    boolean pillHit(float x, float y) {
        return x >= viewW - bw - 4 * u && y >= top && y <= bottom;
    }

    /** Index of the item under the point, or -1. */
    int itemAt(float x, float y) {
        if (!pillHit(x, y)) return -1;
        for (int i = 0; i < count; i++) {
            if (Math.abs(y - itemY[i]) <= pitch / 2 - 4 * u && y > top + rc && y < bottom - rc) return i;
        }
        return -1;
    }

    boolean gearHit(float x, float y) {
        if (!hasGear || gearAlpha < 0.9f) return false;
        float dx = x - gearX;
        float dy = y - gearY;
        float r = gearD / 2 + 6 * u;
        return dx * dx + dy * dy <= r * r;
    }

    boolean cardHit(float x, float y) {
        return hasCard && card.contains(x, y);
    }

    /** Bounds of everything drawn in the expanded state, for gesture exclusion and invalidation. */
    void blockBounds(RectF out) {
        float l = viewW - Math.max(bw, tabW) - fxExpanded - 6 * u;
        float b = bottom + fy + (hasGear && gearAlpha > 0 ? gearGap + gearD : 0);
        out.set(Math.max(0, l), Math.max(0, top - fy), viewW, Math.min(viewH, b));
    }
}
