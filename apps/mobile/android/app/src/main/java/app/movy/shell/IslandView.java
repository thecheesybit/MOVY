package app.movy.shell;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.view.ViewCompat;

import java.util.Collections;
import java.util.List;

/**
 * The Island: a black shape fused to the right screen edge. Collapsed it is a slim tab, expanded a pill with one item
 * per source and a gear below. Full-size transparent overlay; touches outside the shape fall through to the page.
 * Drawing lives in {@link IslandPainter}, layout in {@link IslandGeometry}, touch in {@link IslandGestures}.
 */
public final class IslandView extends View {
    interface Listener {
        void onSourcePicked(Source source);

        void onSettingsRequested();

        void onExpandedChanged(boolean expanded);

        void onPositionChanged(float fraction);

        void onAnnouncementDismissed(String id);
    }

    private static final long IDLE_COLLAPSE_MS = 4000;
    private static final long IDLE_COLLAPSE_TV_MS = 7000;
    private static final long EXPAND_MS = 280;
    private static final long COLLAPSE_MS = 200;
    private static final long SLIDE_MS = 180;

    final IslandGeometry g;
    private final IslandPainter painter;
    private final IslandGestures gestures;
    private final boolean tv;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleCollapse = this::collapse;
    private final ValueAnimator expandAnim = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator pulseAnim = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator sweepAnim = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator pressAnim = ValueAnimator.ofFloat(0f, 1f);
    private final Rect dirty = new Rect();
    private final RectF tmpBounds = new RectF();
    private final Rect exclusion = new Rect();

    // state read by the painter
    List<Source> sources = Collections.emptyList();
    String[] labels = new String[0];
    String[] monograms = new String[0];
    String activeId;
    HealthChecker health = HealthChecker.NONE;
    float progress;
    float pressAlpha;
    float sweep = 1f;
    float pulsePhase;
    String pulseId;
    int focusIndex;
    StaticLayout cardLayout;
    int cardAccent = 0xFF3A3A3C;

    private Listener listener;
    private Announcement announcement;
    private boolean expanded;
    private boolean shown;
    private final IslandAccessibility access;

    public IslandView(Context ctx, boolean tv) {
        super(ctx);
        this.tv = tv;
        g = new IslandGeometry(ctx.getResources().getDisplayMetrics().density, tv);
        painter = new IslandPainter(ctx, g, tv);
        gestures = new IslandGestures(this, g);
        access = new IslandAccessibility(this, g);
        ViewCompat.setAccessibilityDelegate(this, access);
        setFocusable(false);
        setVisibility(INVISIBLE);
        expandAnim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        expandAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                updateExclusion();
            }
        });
        pulseAnim.setDuration(750);
        pulseAnim.setRepeatCount(3);
        pulseAnim.addUpdateListener(a -> {
            pulsePhase = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        pulseAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                pulseId = null;
                invalidateBlock();
            }
        });
        sweepAnim.setDuration(420);
        sweepAnim.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidateBlock();
        });
        pressAnim.setDuration(160);
        pressAnim.addUpdateListener(a -> {
            pressAlpha = (float) a.getAnimatedValue();
            invalidateBlock();
        });
    }

    // ---- configuration ----

    void setListener(Listener l) {
        listener = l;
    }

    void setHealth(HealthChecker h) {
        health = h;
    }

    void setPositionFraction(float f) {
        g.centerFrac = Math.max(0f, Math.min(1f, f));
        refreshLayout();
    }

    /** Re-renders the item list (sources added, removed or reordered). */
    void setSources(List<Source> next, String active) {
        sources = next;
        activeId = active;
        labels = new String[next.size()];
        monograms = new String[next.size()];
        float maxW = g.pillW - 8 * g.u;
        for (int i = 0; i < next.size(); i++) {
            labels[i] = TextUtils.ellipsize(next.get(i).name, new android.text.TextPaint(painter.labelPaint()), maxW, TextUtils.TruncateAt.END).toString();
            monograms[i] = next.get(i).monogram();
        }
        g.setSize(getWidth(), getHeight(), next.size());
        if (focusIndex > next.size()) focusIndex = next.size();
        refreshLayout();
    }

    void setActive(String id) {
        if (id == null ? activeId == null : id.equals(activeId)) return;
        activeId = id;
        sweepAnim.cancel();
        sweep = 0f;
        sweepAnim.start();
        invalidateBlock();
    }

    void setAnnouncement(Announcement a) {
        announcement = a;
        rebuildCard();
        invalidateBlock();
    }

    String announcementText() {
        return announcement != null ? announcement.text : "";
    }

    String sourceName(int index) {
        return index >= 0 && index < sources.size() ? sources.get(index).name : "";
    }

    // ---- state ----

    boolean isExpanded() {
        return expanded;
    }

    boolean isShownFully() {
        return shown;
    }

    void expand() {
        if (expanded || !shown) return;
        expanded = true;
        focusIndex = Math.max(0, indexOf(activeId));
        setFocusable(true);
        setFocusableInTouchMode(tv);
        if (tv) requestFocus();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        animateProgress(1f, EXPAND_MS, new OvershootInterpolator(1.12f));
        touched();
        if (listener != null) listener.onExpandedChanged(true);
    }

    void collapse() {
        collapseInternal(true);
    }

    void collapseNow() {
        collapseInternal(false);
    }

    private void collapseInternal(boolean animate) {
        main.removeCallbacks(idleCollapse);
        if (!expanded) return;
        expanded = false;
        setFocusable(false);
        if (animate) {
            animateProgress(0f, COLLAPSE_MS, new DecelerateInterpolator());
        } else {
            expandAnim.cancel();
            progress = 0f;
            invalidateBlock();
        }
        updateExclusion();
        if (listener != null) listener.onExpandedChanged(false);
    }

    /** D-pad keys while expanded (TV): routed here by the activity so the page never steals them. */
    boolean handleKey(KeyEvent event) {
        return expanded && onKeyDown(event.getKeyCode(), event);
    }

    void toggle() {
        if (expanded) collapse(); else expand();
    }

    private void animateProgress(float to, long ms, android.view.animation.Interpolator ip) {
        expandAnim.cancel();
        expandAnim.setFloatValues(progress, to);
        expandAnim.setDuration(ms);
        expandAnim.setInterpolator(ip);
        expandAnim.start();
    }

    /** Slides in from / out into the edge. Always collapsed while hidden. */
    void setShown(boolean show, boolean animate) {
        if (show == shown && !(show && getVisibility() != VISIBLE)) return;
        shown = show;
        float away = g.pillW + g.fxExpanded + 14 * g.u;
        animate().cancel();
        if (show) {
            setVisibility(VISIBLE);
            if (!animate) {
                setTranslationX(0);
                return;
            }
            if (getTranslationX() == 0f) setTranslationX(away);
            animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).withLayer().start();
        } else {
            collapseNow();
            if (!animate) {
                setTranslationX(away);
                setVisibility(INVISIBLE);
                return;
            }
            animate().translationX(away).setDuration(SLIDE_MS).withLayer().withEndAction(() -> {
                if (!shown) setVisibility(INVISIBLE);
            }).start();
        }
    }

    /** Gently pulses one item; the caller expands the Island first when it should be visible. */
    void pulse(String sourceId) {
        pulseId = sourceId;
        pulseAnim.cancel();
        pulseAnim.start();
    }

    // ---- interaction callbacks (from gestures, keys, accessibility) ----

    void selectItem(int index) {
        if (index < 0 || index >= sources.size()) return;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        Source s = sources.get(index);
        if (listener != null) listener.onSourcePicked(s);
        main.postDelayed(this::collapse, 250);
        touched();
    }

    void openSettings() {
        collapse();
        if (listener != null) listener.onSettingsRequested();
    }

    void dismissAnnouncement() {
        Announcement a = announcement;
        announcement = null;
        rebuildCard();
        invalidateBlock();
        if (a != null && listener != null) listener.onAnnouncementDismissed(a.id);
    }

    void setPressedLook(boolean down) {
        pressAnim.cancel();
        pressAnim.setFloatValues(pressAlpha, down ? 1f : 0f);
        pressAnim.start();
    }

    void setCenterFraction(float f) {
        g.centerFrac = Math.max(0f, Math.min(1f, f));
        invalidateBlock();
        updateExclusion();
    }

    void commitPosition() {
        if (listener != null) listener.onPositionChanged(g.centerFrac);
    }

    /** Any interaction postpones the idle auto-collapse. */
    void touched() {
        main.removeCallbacks(idleCollapse);
        if (expanded) main.postDelayed(idleCollapse, tv ? IDLE_COLLAPSE_TV_MS : IDLE_COLLAPSE_MS);
    }

    private int indexOf(String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---- layout, drawing ----

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        g.setSize(w, h, sources.size());
        rebuildCard();
        refreshLayout();
    }

    private void refreshLayout() {
        if (getWidth() == 0) return;
        g.setSize(getWidth(), getHeight(), sources.size());
        float cardW = g.cardWidth();
        g.update(1);
        g.blockBounds(tmpBounds);
        float l = Math.max(0, g.viewW - g.pillW - cardW - 40 * g.u);
        dirty.set((int) l, (int) Math.max(0, tmpBounds.top - 12 * g.u), getWidth(), (int) Math.min(getHeight(), tmpBounds.bottom + 12 * g.u));
        g.update(0);
        g.blockBounds(tmpBounds);
        dirty.union((int) tmpBounds.left, (int) tmpBounds.top, getWidth(), (int) tmpBounds.bottom);
        updateExclusion();
        invalidateBlock();
    }

    void invalidateBlock() {
        if (dirty.isEmpty()) invalidate(); else invalidate(dirty);
    }

    private void rebuildCard() {
        Announcement a = announcement;
        float w = g.cardWidth();
        if (a == null || a.text.isEmpty() || w < 80 * g.u) {
            cardLayout = null;
            g.hasCard = false;
            return;
        }
        int textW = (int) (w - 24 * g.u - 14 * g.u);
        cardLayout = StaticLayout.Builder.obtain(a.text, 0, a.text.length(), painter.cardTextPaint(), textW)
                .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        g.card.set(0, 0, w, cardLayout.getHeight() + 24 * g.u);
        g.hasCard = true;
        switch (a.level) {
            case "critical": cardAccent = IslandPainter.HEALTH_DOWN; break;
            case "warn": cardAccent = IslandPainter.HEALTH_SLOW; break;
            default: cardAccent = 0xFF3A3A3C; break;
        }
    }

    private void updateExclusion() {
        if (Build.VERSION.SDK_INT < 29 || getWidth() == 0) return;
        if (expanded) {
            g.update(1);
            g.blockBounds(tmpBounds);
            exclusion.set((int) tmpBounds.left, (int) tmpBounds.top, getWidth(), (int) tmpBounds.bottom);
        } else {
            g.update(0);
            exclusion.set((int) (g.viewW - g.hitW), (int) (g.top - g.fy), getWidth(), (int) (g.bottom + g.fy));
        }
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(exclusion)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        painter.draw(canvas, this);
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouch(e);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent e) {
        return access.dispatchHoverEvent(e) || super.dispatchHoverEvent(e);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        invalidateBlock();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!expanded) return super.onKeyDown(keyCode, event);
        touched();
        int last = sources.size(); // gear index
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                focusIndex = Math.max(0, focusIndex - 1);
                ensureVisible(focusIndex);
                invalidateBlock();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focusIndex = Math.min(last, focusIndex + 1);
                ensureVisible(focusIndex);
                invalidateBlock();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focusIndex >= last) openSettings(); else selectItem(focusIndex);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void ensureVisible(int index) {
        if (index >= g.count || g.scrollMax <= 0) return;
        float itemTop = g.padTop + index * g.pitch;
        float viewport = g.pillH;
        if (itemTop - g.scroll < 0) g.scroll = itemTop - g.padTop;
        else if (itemTop + g.pitch - g.scroll > viewport) g.scroll = itemTop + g.pitch - viewport;
    }
}
