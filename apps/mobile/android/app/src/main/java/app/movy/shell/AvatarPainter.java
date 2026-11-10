package app.movy.shell;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Draws a source "profile" disc (accent gradient, white initial). Shared by the start screen and the TV drawer. */
final class AvatarPainter {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);

    AvatarPainter() {
        letter.setColor(UiTokens.WHITE);
        letter.setTextAlign(Paint.Align.CENTER);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    void draw(Canvas c, float cx, float cy, float r, int accent, String initial) {
        fill.setShader(new LinearGradient(0, cy - r, 0, cy + r, accent, IslandPainter.darken(accent, 0.3f), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, fill);
        letter.setTextSize(r * 0.95f);
        c.drawText(initial, cx, cy - (letter.ascent() + letter.descent()) / 2f, letter);
    }
}
