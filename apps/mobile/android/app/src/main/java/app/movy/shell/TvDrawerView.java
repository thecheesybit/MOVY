package app.movy.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TV drawer, drawn in one pass over the left part of the screen (above the WebView, never focusable, never touches layout):
 * a collapsed strip of outline icons (vertically centred, the current item white, the rest grey) that opens into labels over a
 * left-to-right dark gradient scrim, with the Sources row (round source avatars) at the top. Open/close animates only
 * translation and alpha of what is drawn (160 ms), no blur. State comes from {@link TvDrawerController}; this class only paints.
 */
final class TvDrawerView extends View {
    private static final long ANIM_MS = 160;
    static final int STRIP_DP = 64;
    private static final int LABEL_DP = 250;
    private static final int SOURCE_GAP_DP = 14;
    private static final int SOURCE_R_DP = 22;
    private static final int GREY = 0xFF9A9CA6;
    private static final int SCRIM = 0xF20F1014;

    private final float d;
    private final int safeV;
    private final DrawerIcons icons = new DrawerIcons();
    private final AvatarPainter avatar = new AvatarPainter();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Paint stripPaint = new Paint();
    private final RectF rect = new RectF();
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);

    private List<DrawerModel.Item> items = Collections.emptyList();
    private List<Source> sources = Collections.emptyList();
    private List<CharSequence> labels = new ArrayList<>();
    private int currentSource = -1;
    private int accent = 0xFFE50914;
    private String currentId = "";
    private DrawerNav.Zone zone = DrawerNav.Zone.CLOSED;
    private int listIdx;
    private int srcIdx;
    private float open;
    private int cell; // row pitch of the open list
    private int closedCell; // row pitch of the collapsed strip
    private float[] closedY = new float[0];
    private float[] openY = new float[0];
    private float regionTop;
    private float contentH;
    private float scroll;

    TvDrawerView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        safeV = Ui.safeV(ctx);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        anim.setDuration(ANIM_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            open = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** Width of the drawn area for a screen {@code screenPx} wide: the scrim fades out at about 45 % of the screen. */
    static int viewWidthPx(Context ctx, int screenPx) {
        return Math.max(Math.round(screenPx * 0.45f), Ui.dp(ctx, STRIP_DP + LABEL_DP + 30));
    }

    float openFraction() { return open; }

    void setContent(List<DrawerModel.Item> items, List<Source> sources, int currentSource, int accent, String currentId) {
        this.items = items;
        this.sources = sources;
        this.currentSource = currentSource;
        this.accent = accent;
        this.currentId = currentId == null ? "" : currentId;
        relayout();
        invalidate();
    }

    void setFocus(DrawerNav.Zone zone, int listIdx, int srcIdx) {
        this.zone = zone;
        this.listIdx = listIdx;
        this.srcIdx = srcIdx;
        if (zone == DrawerNav.Zone.LIST && listIdx >= 0 && listIdx < openY.length) {
            scroll = DrawerModel.clampScroll(DrawerModel.ensureVisible(Math.round(scroll), listIdx * cell, cell, Math.round(viewH())),
                    Math.round(contentH), Math.round(viewH()));
        }
        invalidate();
    }

    /** Opens (labels slide in, scrim fades in) or closes the drawer. */
    void setOpen(boolean on, boolean animate) {
        anim.cancel();
        if (!animate) {
            open = on ? 1f : 0f;
            invalidate();
            return;
        }
        anim.setFloatValues(open, on ? 1f : 0f);
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // dark where the labels are, fading out at the right edge of the view (about 45 % of the screen)
        scrimPaint.setShader(new LinearGradient(0, 0, w, 0, new int[] {SCRIM, 0xE00F1014, 0x000F1014}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        stripPaint.setShader(new LinearGradient(0, 0, px(STRIP_DP + 14), 0, 0xB3000000, 0x00000000, Shader.TileMode.CLAMP));
        relayout();
    }

    private float px(float dp) { return dp * d; }

    private float viewH() { return Math.max(0, getHeight() - regionTop - safeV); }

    // ---------- geometry ----------

    private void relayout() {
        int h = getHeight();
        if (h <= 0 || items.isEmpty()) {
            closedY = new float[0];
            openY = new float[0];
            return;
        }
        int closedN = DrawerModel.closedCount(items);
        int usableDp = Math.round((h - 2f * safeV) / d);
        closedCell = Math.round(px(DrawerModel.cellDp(usableDp, closedN)));
        regionTop = safeV + px(DrawerModel.SOURCE_BLOCK_DP);
        cell = Math.round(px(DrawerModel.openCellDp(Math.round(viewH() / d), items.size())));
        contentH = items.size() * cell;
        text.setTextSize(cell * 0.46f);
        float closedTop = Math.max(safeV, (h - closedN * closedCell) / 2f);
        float openTop = Math.max(regionTop, (h - contentH) / 2f);
        closedY = new float[items.size()];
        openY = new float[items.size()];
        int k = 0;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).recent) closedY[i] = closedTop + k++ * closedCell;
            openY[i] = openTop + i * cell;
        }
        for (int i = 0; i < items.size(); i++) if (items.get(i).recent) closedY[i] = openY[i];
        scroll = DrawerModel.clampScroll(Math.round(scroll), Math.round(contentH), Math.round(viewH()));
        ellipsize();
    }

    private void ellipsize() {
        labels = new ArrayList<>();
        float max = px(LABEL_DP - 24);
        for (DrawerModel.Item it : items) labels.add(TextUtils.ellipsize(it.label, new android.text.TextPaint(text), max, TextUtils.TruncateAt.END));
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        float f = open;
        if (f < 1f) {
            stripPaint.setAlpha(Math.round(255 * (1f - f)));
            c.drawRect(0, 0, px(STRIP_DP + 14), getHeight(), stripPaint);
        }
        if (f > 0f) {
            scrimPaint.setAlpha(Math.round(255 * f));
            c.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
            drawSources(c, f);
        } else {
            drawCurrentSourceMark(c);
        }
        boolean clip = f >= 0.999f && contentH > viewH();
        if (clip) {
            c.save();
            c.clipRect(0, regionTop - px(4), getWidth(), getHeight() - safeV + px(4));
        }
        for (int i = 0; i < items.size() && i < openY.length; i++) drawRow(c, i, f);
        if (clip) c.restore();
    }

    private void drawCurrentSourceMark(Canvas c) {
        if (currentSource < 0 || currentSource >= sources.size()) return;
        Source s = sources.get(currentSource);
        avatar.draw(c, px(STRIP_DP) / 2f, safeV + px(26), px(13), s.accent, s.monogram());
    }

    private void drawSources(Canvas c, float f) {
        int save = -1;
        if (f < 1f) save = c.saveLayerAlpha(0, 0, getWidth(), regionTop, Math.round(255 * f));
        float r = px(SOURCE_R_DP);
        float slide = (1f - f) * px(-20);
        float cy = safeV + px(6) + r;
        for (int k = 0; k < sources.size(); k++) {
            Source s = sources.get(k);
            float cx = px(16) + r + k * (2 * r + px(SOURCE_GAP_DP)) + slide;
            boolean focus = zone == DrawerNav.Zone.SOURCES && k == srcIdx;
            avatar.draw(c, cx, cy, focus ? r * 1.08f : r, s.accent, s.monogram());
            if (focus) {
                stroke.setStrokeWidth(px(2.5f));
                stroke.setColor(UiTokens.WHITE);
                c.drawCircle(cx, cy, r * 1.08f + px(5), stroke);
            } else if (k == currentSource) {
                fill.setColor(UiTokens.WHITE);
                c.drawCircle(cx, cy + r + px(9), px(2.5f), fill);
            }
        }
        int named = zone == DrawerNav.Zone.SOURCES ? srcIdx : currentSource;
        if (named >= 0 && named < sources.size()) {
            float cx = px(16) + r + named * (2 * r + px(SOURCE_GAP_DP)) + slide;
            text.setTextSize(px(14));
            text.setColor(zone == DrawerNav.Zone.SOURCES ? UiTokens.WHITE : GREY);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(zone == DrawerNav.Zone.SOURCES);
            c.drawText(sources.get(named).name, Math.max(cx, px(16) + px(30)), cy + r + px(28), text);
            text.setTextAlign(Paint.Align.LEFT);
            text.setFakeBoldText(false);
            text.setTextSize(cell * 0.46f);
        }
        if (save >= 0) c.restoreToCount(save);
    }

    private void drawRow(Canvas c, int i, float f) {
        DrawerModel.Item it = items.get(i);
        if (it.recent && f <= 0.02f) return;
        float rowAlpha = it.recent ? f * f : 1f;
        float y = it.recent ? openY[i] - scroll * f : closedY[i] + (openY[i] - scroll - closedY[i]) * f;
        float cell = closedCell + (this.cell - closedCell) * f; // the pitch tightens as the list opens
        float cy = y + cell / 2f;
        boolean focus = zone == DrawerNav.Zone.LIST && i == listIdx;
        boolean current = it.id.equals(currentId);
        int tone = focus || current ? UiTokens.WHITE : GREY;
        int a = Math.round(255 * rowAlpha);
        if (focus && f > 0f) {
            rect.set(px(8), y + px(3), px(STRIP_DP + LABEL_DP - 12), y + cell - px(3));
            fill.setColor(withAlpha(accent, Math.round(0x40 * f)));
            c.drawRoundRect(rect, cell / 2f, cell / 2f, fill);
            stroke.setStrokeWidth(px(1.5f));
            stroke.setColor(withAlpha(accent, Math.round(0xCC * f)));
            c.drawRoundRect(rect, cell / 2f, cell / 2f, stroke);
        }
        if (current && !focus) {
            fill.setColor(accent);
            rect.set(0, cy - cell * 0.25f, px(3), cy + cell * 0.25f);
            c.drawRoundRect(rect, px(1.5f), px(1.5f), fill);
        }
        icons.draw(c, it.icon, px(STRIP_DP) / 2f, cy, cell * (it.recent ? 0.42f : 0.55f), withAlpha(tone, a));
        if (f <= 0f) return;
        text.setColor(withAlpha(tone, Math.round(255 * f * rowAlpha)));
        text.setFakeBoldText(focus || current);
        c.drawText(labels.get(i), 0, labels.get(i).length(), px(STRIP_DP) + px(2) + (1f - f) * px(-20),
                cy - (text.ascent() + text.descent()) / 2f, text);
        text.setFakeBoldText(false);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha * (color >>> 24) / 255));
        return (a << 24) | (color & 0xFFFFFF);
    }

    @Override
    protected void onDetachedFromWindow() {
        anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean hasOverlappingRendering() { return false; }
}
