package app.movy.shell;

import android.graphics.Path;

/** Builds the fused-to-the-right-edge outline with concave fillets, reusing one Path (no allocation). */
final class IslandShape {
    private IslandShape() {}

    /** Body from {@code top} to {@code bottom}, {@code bw} wide, flaring into the edge at x = {@code w}. */
    static void body(Path p, float w, float top, float bottom, float bw, float fx, float fy, float rc) {
        float edge = w + 2; // overdraw so no hairline shows against the screen edge
        float left = w - bw;
        p.rewind();
        p.moveTo(edge, top - fy);
        p.cubicTo(edge, top - fy * 0.45f, w - fx * 0.45f, top, w - fx, top);
        p.lineTo(left + rc, top);
        p.quadTo(left, top, left, top + rc);
        p.lineTo(left, bottom - rc);
        p.quadTo(left, bottom, left + rc, bottom);
        p.lineTo(w - fx, bottom);
        p.cubicTo(w - fx * 0.45f, bottom, edge, bottom + fy * 0.45f, edge, bottom + fy);
        p.close();
    }

    /** Hourglass neck joining the pill and the gear circle (the "drip"). */
    static void neck(Path p, float cx, float pillBottom, float gearTop, float u) {
        float mid = (pillBottom + gearTop) / 2;
        p.rewind();
        p.moveTo(cx - 9 * u, pillBottom - u);
        p.quadTo(cx - 2.5f * u, mid, cx - 6 * u, gearTop + 3 * u);
        p.lineTo(cx + 6 * u, gearTop + 3 * u);
        p.quadTo(cx + 2.5f * u, mid, cx + 9 * u, pillBottom - u);
        p.close();
    }
}
