package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The fullscreen player host. Adds a Netflix-style brightness control: swipe up or down on the left side of the screen
 * to change the real screen brightness (window brightness, so it can go above or below the system level). Taps and
 * every other gesture still reach the web player. Nothing is drawn over the video except a small pill while swiping.
 */
final class BrightnessLayout extends FrameLayout {
    private static final long HUD_HIDE_MS = 900;

    private final Window window;
    private final Prefs prefs;
    private final Hud hud;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float slop;

    private float level;
    private boolean eligible;
    private boolean dragging;
    private float downX;
    private float downY;
    private float startLevel;

    BrightnessLayout(Context ctx, Window window, Prefs prefs) {
        super(ctx);
        this.window = window;
        this.prefs = prefs;
        this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        float d = ctx.getResources().getDisplayMetrics().density;
        hud = new Hud(ctx, d);
        hud.setVisibility(GONE);
        LayoutParams lp = new LayoutParams((int) (52 * d), (int) (176 * d));
        lp.leftMargin = (int) (28 * d);
        lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
        addView(hud, lp);
    }

    /** Re-applies the brightness chosen earlier (if any); otherwise the screen keeps following the system. */
    void applySaved() {
        float saved = prefs.playerBrightness();
        if (saved >= 0f) setWindowBrightness(BrightnessMath.clamp(saved));
    }

    /** Back to the system brightness (leaving the player). */
    void release() {
        main.removeCallbacksAndMessages(null);
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    private void setWindowBrightness(float v) {
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = v;
        window.setAttributes(lp);
    }

    private float currentLevel() {
        float w = window.getAttributes().screenBrightness;
        if (w >= 0f) return w;
        try {
            int sys = Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return BrightnessMath.clamp(sys / 255f);
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            return 0.5f;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                eligible = BrightnessMath.inZone(ev.getX(), ev.getY(), getWidth(), getHeight());
                downX = ev.getX();
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                eligible = false; // a second finger: pinch/zoom belongs to the page
                break;
            case MotionEvent.ACTION_MOVE:
                if (eligible && !dragging
                        && BrightnessMath.isVerticalSwipe(ev.getX() - downX, ev.getY() - downY, slop)) {
                    dragging = true;
                    startLevel = currentLevel();
                    // the page gets ACTION_CANCEL; we own the rest of this gesture
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                level = BrightnessMath.fromDrag(startLevel, ev.getY() - downY, getHeight());
                setWindowBrightness(level);
                hud.set(level);
                hud.setVisibility(VISIBLE);
                main.removeCallbacksAndMessages(null);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                eligible = false;
                prefs.setPlayerBrightness(level);
                main.postDelayed(() -> hud.setVisibility(GONE), HUD_HIDE_MS);
                return true;
            default:
                return true;
        }
    }

    /** A dark pill with a sun, a level bar and the percentage. Not clickable: it never takes touches. */
    private static final class Hud extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF bar = new RectF();
        private final Path clip = new Path();
        private final float d;
        private float level;

        Hud(Context ctx, float density) {
            super(ctx);
            this.d = density;
            setClickable(false);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(2f * d);
            stroke.setColor(UiTokens.WHITE);
            text.setColor(UiTokens.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(13f * d);
            text.setFakeBoldText(true);
        }

        void set(float l) {
            level = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = w / 2f;
            box.set(0, 0, w, h);
            fill.setColor(0xB3000000);
            c.drawRoundRect(box, r, r, fill);

            // sun glyph
            float cx = w / 2f;
            float cy = 24f * d;
            stroke.setColor(UiTokens.WHITE);
            c.drawCircle(cx, cy, 5f * d, stroke);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float x0 = cx + (float) Math.cos(a) * 8.5f * d;
                float y0 = cy + (float) Math.sin(a) * 8.5f * d;
                float x1 = cx + (float) Math.cos(a) * 11f * d;
                float y1 = cy + (float) Math.sin(a) * 11f * d;
                c.drawLine(x0, y0, x1, y1, stroke);
            }

            // level bar (rounded track, filled from the bottom)
            float bw = 6f * d;
            float top = 44f * d;
            float bottom = h - 36f * d;
            bar.set(cx - bw / 2f, top, cx + bw / 2f, bottom);
            fill.setColor(0x40FFFFFF);
            c.drawRoundRect(bar, bw / 2f, bw / 2f, fill);
            clip.reset();
            clip.addRoundRect(bar, bw / 2f, bw / 2f, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            float filled = bottom - (bottom - top) * BrightnessMath.clamp(level);
            fill.setColor(UiTokens.WHITE);
            c.drawRect(bar.left, filled, bar.right, bottom, fill);
            c.restore();

            c.drawText(BrightnessMath.percent(level) + "%", cx, h - 12f * d, text);
        }
    }
}
