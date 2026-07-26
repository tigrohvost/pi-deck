package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The three roots of the deck: КОНСОЛЬ, ЯДРО, СЕССИИ.
 *
 * <p>Glyphs are drawn on canvas rather than shipped as vector drawables — the silhouettes are a
 * filled square, a diamond and three lines, which cost less as eight lines of {@link Canvas} than
 * as three XML assets in an APK that is measured in tens of kilobytes.
 */
@SuppressLint("ViewConstructor")
public final class TabBarView extends LinearLayout {
    public interface Listener {
        void onTabSelected(int index);
    }

    /** Console, the stream of prompts, trace and answers. */
    public static final int TAB_CONSOLE = 0;
    /** Core: model, colour scheme, maintenance. */
    public static final int TAB_CORE = 1;
    /** Sessions on disk. */
    public static final int TAB_SESSIONS = 2;

    private static final String[] LABELS = {"КОНСОЛЬ", "ЯДРО", "СЕССИИ"};

    private final DeckStyle style;
    private final GlyphView[] glyphs = new GlyphView[LABELS.length];
    private final TextView[] captions = new TextView[LABELS.length];
    private int selected = TAB_CONSOLE;

    public TabBarView(Context context, DeckStyle style, Listener listener) {
        super(context);
        this.style = style;
        setOrientation(HORIZONTAL);
        setBackgroundColor(style.palette.background);

        for (int index = 0; index < LABELS.length; index++) {
            addView(buildTab(index, listener), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        setSelectedTab(TAB_CONSOLE);
    }

    private View buildTab(int index, Listener listener) {
        LinearLayout tab = new LinearLayout(getContext());
        tab.setOrientation(VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(style.dp(56));
        tab.setPadding(0, style.dp(8), 0, style.dp(8));

        GlyphView glyph = new GlyphView(getContext(), index);
        glyphs[index] = glyph;
        int glyphSize = style.dp(15 * style.textScale());
        tab.addView(glyph, new LayoutParams(glyphSize, glyphSize));

        TextView caption = style.monoAt(LABELS[index], 10.5f, style.palette.muted, true);
        caption.setGravity(Gravity.CENTER);
        captions[index] = caption;
        LayoutParams captionLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        captionLp.topMargin = style.dp(4);
        tab.addView(caption, captionLp);

        style.clickable(tab, () -> listener.onTabSelected(index));
        return tab;
    }

    public void setSelectedTab(int index) {
        selected = index;
        for (int i = 0; i < LABELS.length; i++) {
            int color = i == index ? style.palette.accent : style.palette.muted;
            captions[i].setTextColor(color);
            glyphs[i].setColor(color);
        }
    }

    public int selectedTab() {
        return selected;
    }

    /** Filled square, diamond, or three lines, sized to whatever box the tab gives it. */
    private static final class GlyphView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;

        GlyphView(Context context, int kind) {
            super(context);
            this.kind = kind;
            paint.setStyle(Paint.Style.FILL);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            switch (kind) {
                case TAB_CORE -> {
                    path.reset();
                    path.moveTo(left + size / 2f, top);
                    path.lineTo(left + size, top + size / 2f);
                    path.lineTo(left + size / 2f, top + size);
                    path.lineTo(left, top + size / 2f);
                    path.close();
                    canvas.drawPath(path, paint);
                }
                case TAB_SESSIONS -> {
                    float thickness = size * 0.16f;
                    for (int line = 0; line < 3; line++) {
                        float y = top + size * (0.18f + line * 0.32f);
                        canvas.drawRect(left, y, left + size, y + thickness, paint);
                    }
                }
                default -> canvas.drawRect(left, top, left + size, top + size, paint);
            }
        }
    }
}
