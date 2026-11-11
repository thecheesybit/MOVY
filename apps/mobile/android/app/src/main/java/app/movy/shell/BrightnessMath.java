package app.movy.shell;

import java.util.Locale;

/**
 * Pure maths for the player brightness control (no Android types, unit tested).
 *
 * <p>The level is a multiplier: 1 = the video as the site sends it, below 1 dims, above 1 brightens, up to 2 (200%).
 * Phones and tablets use the real screen brightness for 2%..100% and add a picture gain above 100%. TVs ignore the
 * screen brightness (the TV owns its backlight) and use the picture gain for the whole range, 40%..200%.
 */
final class BrightnessMath {
    private BrightnessMath() {}

    static final float MIN_PHONE = 0.02f;
    static final float MIN_TV = 0.4f;
    static final float MAX = 2f;
    /** Remote key step on TV; the touch swipe is continuous. */
    static final float STEP_TV = 0.1f;
    /** The swipe zone is the left part of the screen, clear of the top and bottom edges (system gestures, player bar). */
    static final float ZONE_RIGHT = 0.4f;
    static final float ZONE_TOP = 0.10f;
    static final float ZONE_BOTTOM = 0.90f;
    /** A drag across this share of the height changes brightness over the whole range. */
    static final float SPAN = 0.7f;

    static float min(boolean tv) {
        return tv ? MIN_TV : MIN_PHONE;
    }

    static float clamp(float level, boolean tv) {
        if (Float.isNaN(level)) return 1f;
        return Math.max(min(tv), Math.min(MAX, level));
    }

    /** Real screen brightness (0..1) for this level, or -1 when the screen brightness is left alone (TV). */
    static float windowLevel(float level, boolean tv) {
        return tv ? -1f : Math.min(1f, clamp(level, false));
    }

    /** Picture gain for this level: 1 means no filter at all. */
    static float gain(float level, boolean tv) {
        float l = clamp(level, tv);
        return tv ? l : Math.max(1f, l);
    }

    static boolean isNeutral(float gain) {
        return Math.abs(gain - 1f) < 0.005f;
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
    static float fromDrag(float startLevel, float dy, int height, boolean tv) {
        if (height <= 0) return clamp(startLevel, tv);
        float range = MAX - min(tv);
        return clamp(startLevel - dy / (height * SPAN) * range, tv);
    }

    /** One remote-key step up (dir > 0) or down, snapped to the {@link #STEP_TV} grid. */
    static float step(float level, int dir, boolean tv) {
        float n = Math.round(clamp(level, tv) / STEP_TV * 100f) / 100f; // grid position, float noise removed
        int next = dir > 0 ? (int) Math.floor(n + 1e-4f) + 1 : (int) Math.ceil(n - 1e-4f) - 1;
        return clamp(Math.round(next * STEP_TV * 100f) / 100f, tv);
    }

    /** 0..1 position of the level inside its range (the HUD bar). */
    static float fraction(float level, boolean tv) {
        float lo = min(tv);
        return (clamp(level, tv) - lo) / (MAX - lo);
    }

    static int percent(float level, boolean tv) {
        return Math.round(clamp(level, tv) * 100f);
    }

    /**
     * Page script that sets (or, when neutral, removes) a brightness filter on whatever element is fullscreen. Applied
     * to the top document: a fullscreen player inside another site's iframe makes the iframe element the fullscreen
     * element there, so the same rule covers it. Separate rules so a browser that lacks one pseudo-class keeps the other.
     */
    static String gainScript(float gain) {
        if (isNeutral(gain)) {
            return "(function(){var s=document.getElementById('__movy_bright');if(s)s.remove();})()";
        }
        String v = String.format(Locale.ROOT, "%.2f", gain);
        return "(function(){var s=document.getElementById('__movy_bright');"
                + "if(!s){s=document.createElement('style');s.id='__movy_bright';(document.head||document.documentElement).appendChild(s);}"
                + "var r='{filter:brightness(" + v + ")!important}';"
                + "s.textContent=':fullscreen'+r+'\\n:-webkit-full-screen'+r;})()";
    }
}
