package app.movy.shell;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.core.graphics.PathParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin outline icons of the drawer, drawn from 24x24 path data (no bitmaps, no vector assets). The set is the one the profile's
 * {@code icon} names; an unknown name draws a play triangle.
 */
final class DrawerIcons {
    private static final Map<String, String> DATA = new HashMap<>();

    static {
        DATA.put("search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0M21 21L16.65 16.65");
        DATA.put("home", "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 22V12h6v10");
        DATA.put("film", "M5 2h14a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3zM7 2v20M17 2v20M2 12h20M2 7h5M2 17h5M17 17h5M17 7h5");
        DATA.put("tv", "M5 7h14a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3v-9a3 3 0 0 1 3-3zM17 2l-5 5-5-5");
        DATA.put("sparkles", "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z");
        DATA.put("bookmark", "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z");
        DATA.put("settings", "M3 6h10M17 6h4M3 12h4M11 12h10M3 18h12M19 18h2M15 3v6M9 9v6M17 15v6");
        DATA.put("user", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0M4 21a8 8 0 0 1 16 0");
        DATA.put("users", "M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6");
        DATA.put("history", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2");
        DATA.put("clip", "M9 2h6a3 3 0 0 1 3 3v14a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V5a3 3 0 0 1 3-3zM10.5 9l4 3-4 3z");
        DATA.put("flame", "M12 22c4 0 7-2.7 7-6.8 0-3.9-3-6.2-4.4-9.2-.4 2.3-1.6 3.6-3 4.4C11 7.8 9.6 5 9.6 2 6.6 4.6 5 8.8 5 12.6 5 18.7 8 22 12 22z");
        DATA.put("play", "M7 4l13 8-13 8z");
    }

    private final Map<String, Path> paths = new HashMap<>();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    DrawerIcons() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.7f); // in 24-unit space: the canvas is scaled
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    private Path path(String name) {
        Path p = paths.get(name);
        if (p == null) {
            String d = DATA.containsKey(name) ? DATA.get(name) : DATA.get("play");
            p = PathParser.createPathFromPathData(d);
            if (p == null) p = new Path();
            paths.put(name, p);
        }
        return p;
    }

    /** Draws icon {@code name} centred at (cx, cy), {@code sizePx} wide, in {@code color}. */
    void draw(Canvas c, String name, float cx, float cy, float sizePx, int color) {
        float s = sizePx / 24f;
        stroke.setColor(color);
        c.save();
        c.translate(cx - sizePx / 2f, cy - sizePx / 2f);
        c.scale(s, s);
        c.drawPath(path(name), stroke);
        c.restore();
    }
}
