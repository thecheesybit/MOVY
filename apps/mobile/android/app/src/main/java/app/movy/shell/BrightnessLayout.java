package app.movy.shell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.function.Consumer;

/**
 * The fullscreen player host with a brightness control, 2%..200%.
 *
 * <p>Phones and tablets: swipe up or down on the left side of the screen (Netflix style). Up to 100% it changes the
 * real screen brightness; above 100% the picture gets a gain on top of full screen brightness. TV: the remote's
 * CH+ / CH- step the picture gain (40%..200%); the TV's own backlight is left alone. Taps and every other gesture
 * still reach the web player. At 100% nothing is added at all; a small pill shows only while the level changes.
 */
final class BrightnessLayout extends FrameLayout {
    private static final long HUD_HIDE_MS = 900;

    private final Window window;
    private final Prefs prefs;
    private final boolean tv;
    private final Consumer<Float> gainSink; // page picture gain; 1 = remove the filter
    private final Hud hud;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float slop;

    private float level = Float.NaN; // unknown until first use
    private float appliedGain = 1f;
    private boolean eligible;
    private boolean dragging;
    private float downX;
    private float downY;
    private float startLevel;

    BrightnessLayout(Context ctx, Window window, Prefs prefs, boolean tv, Consumer<Float> gainSink) {
        super(ctx);
        this.window = window;
        this.prefs = prefs;
        this.tv = tv;
        this.gainSink = gainSink;
        this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        float d = ctx.getResources().getDisplayMetrics().density;
        float scale = tv ? 1.6f : 1f; // legible from the sofa
        hud = new Hud(ctx, d * scale, tv);
        hud.setVisibility(GONE);
        LayoutParams lp = new LayoutParams((int) (52 * d * scale), (int) (176 * d * scale));
        lp.leftMargin = (int) (28 * d * scale);
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        addView(hud, lp);
    }

    /** Re-applies the level chosen earlier (if any); otherwise the screen keeps following the system. */
    void applySaved() {
        float saved = prefs.playerBrightness();
        if (saved >= 0f) apply(saved, false);
    }

    /** Remote key (TV): dir > 0 brighter, dir < 0 dimmer. */
    void step(int dir) {
        apply(BrightnessMath.step(levelNow(), dir, tv), true);
        prefs.setPlayerBrightness(level);
    }

    /** Back to the system brightness and the untouched picture (leaving the player). */
    void release() {
        main.removeCallbacksAndMessages(null);
        if (!tv) setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        pushGain(1f);
        level = Float.NaN;
    }

    private void apply(float next, boolean showHud) {
        level = BrightnessMath.clamp(next, tv);
        float wl = BrightnessMath.windowLevel(level, tv);
        if (wl >= 0f) setWindowBrightness(wl);
        pushGain(BrightnessMath.gain(level, tv));
        if (showHud) {
            hud.set(level);
            hud.bringToFront(); // the video view is added after the pill, so lift the pill above it
            hud.setVisibility(VISIBLE);
            main.removeCallbacksAndMessages(null);
            main.postDelayed(() -> hud.setVisibility(GONE), HUD_HIDE_MS);
        }
    }

    private void pushGain(float g) {
        if (Math.abs(g - appliedGain) < 0.005f) return; // no JS churn while dragging inside the same band
        appliedGain = g;
        gainSink.accept(g);
    }

    private void setWindowBrightness(float v) {
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = v;
        window.setAttributes(lp);
    }

    private float levelNow() {
        if (!Float.isNaN(level)) return level;
        float saved = prefs.playerBrightness();
        if (saved >= 0f) return BrightnessMath.clamp(saved, tv);
        if (tv) return 1f;
        float w = window.getAttributes().screenBrightness;
        if (w >= 0f) return BrightnessMath.clamp(w, false);
        try {
            int sys = Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return BrightnessMath.clamp(sys / 255f, false);
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
                    startLevel = levelNow();
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
                apply(BrightnessMath.fromDrag(startLevel, ev.getY() - downY, getHeight(), tv), true);
                main.removeCallbacksAndMessages(null); // stays up while the finger is down
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                eligible = false;
                if (!Float.isNaN(level)) prefs.setPlayerBrightness(level);
                main.postDelayed(() -> hud.setVisibility(GONE), HUD_HIDE_MS);
                return true;
            default:
                return true;
        }
    }

    /** A dark pill with a sun, a level bar (tick at 100%) and the percentage. Never takes touches. */
    private static final class Hud extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF bar = new RectF();
        private final Path clip = new Path();
        private final float d;
        private final boolean tv;
        private float level = 1f;

        Hud(Context ctx, float density, boolean tv) {
            super(ctx);
            this.d = density;
            this.tv = tv;
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
                c.drawLine(cx + (float) Math.cos(a) * 8.5f * d, cy + (float) Math.sin(a) * 8.5f * d,
                        cx + (float) Math.cos(a) * 11f * d, cy + (float) Math.sin(a) * 11f * d, stroke);
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
            float filled = bottom - (bottom - top) * BrightnessMath.fraction(level, tv);
            fill.setColor(UiTokens.WHITE);
            c.drawRect(bar.left, filled, bar.right, bottom, fill);
            c.restore();

            // tick at 100%: above it the picture is boosted past the screen's own maximum
            float tick = bottom - (bottom - top) * BrightnessMath.fraction(1f, tv);
            stroke.setColor(0xFF8E8E93);
            stroke.setStrokeWidth(1.5f * d);
            c.drawLine(cx - bw, tick, cx + bw, tick, stroke);
            stroke.setStrokeWidth(2f * d);

            c.drawText(BrightnessMath.percent(level, tv) + "%", cx, h - 12f * d, text);
        }
    }
}
