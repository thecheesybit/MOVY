package app.movy.shell;

/** Pure maths for the player brightness swipe (no Android types, unit tested). Levels are 0..1 window brightness. */
final class BrightnessMath {
    private BrightnessMath() {}

    /** Lowest level we allow: the screen never goes fully black, so the picture cannot be lost. */
    static final float MIN = 0.02f;
    static final float MAX = 1f;
    /** The swipe zone is the left part of the screen, clear of the top and bottom edges (system gestures, player bar). */
    static final float ZONE_RIGHT = 0.4f;
    static final float ZONE_TOP = 0.10f;
    static final float ZONE_BOTTOM = 0.90f;
    /** A drag across this share of the height changes brightness over the whole range. */
    static final float SPAN = 0.7f;

    static float clamp(float level) {
        if (Float.isNaN(level)) return MAX;
        return Math.max(MIN, Math.min(MAX, level));
    }

    /** True when a touch that went down at (x, y) may start a brightness swipe. */
    static boolean inZone(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        return x >= 0 && x < width * ZONE_RIGHT && y >= height * ZONE_TOP && y <= height * ZONE_BOTTOM;
    }

    /** True when the movement is mostly vertical and long enough to be a swipe rather than a tap or a seek. */
    static boolean isVerticalSwipe(float dx, float dy, float slop) {
        float ay = Math.abs(dy);
        return ay > slop * 2 && ay > Math.abs(dx) * 1.5f;
    }

    /** New level for a drag of {@code dy} pixels (negative = upwards = brighter) that started at {@code startLevel}. */
    static float fromDrag(float startLevel, float dy, int height) {
        if (height <= 0) return clamp(startLevel);
        return clamp(startLevel - dy / (height * SPAN));
    }

    static int percent(float level) {
        return Math.round(clamp(level) * 100f);
    }
}
