package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

@SuppressLint("ViewConstructor")
public final class ScanlineView extends View {
    private static final float[] VIGNETTE_STOPS = {0f, 0.5f, 1f};
    /** One 1-px line every four rows, exactly as the per-row loop used to draw it. */
    static final int SCANLINE_PERIOD_PX = 4;

    private final Paint lines = new Paint();
    private final Paint vignette = new Paint();
    private final Palette palette;

    public ScanlineView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
        setClickable(false);
        setFocusable(false);
        // The overlay sits above the whole conversation and is composited on every frame; a
        // repeating 1x4 tile turns ~770 line commands into a single rectangle.
        lines.setShader(new BitmapShader(
                scanlineTile(palette.scanline), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
        ));
    }

    static Bitmap scanlineTile(int color) {
        Bitmap tile = Bitmap.createBitmap(1, SCANLINE_PERIOD_PX, Bitmap.Config.ARGB_8888);
        tile.eraseColor(Color.TRANSPARENT);
        tile.setPixel(0, 0, color);
        return tile;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        vignette.setShader(new LinearGradient(
                0, 0, width, 0,
                new int[]{palette.vignette, Color.TRANSPARENT, palette.vignette},
                VIGNETTE_STOPS,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), lines);
        canvas.drawRect(0, 0, getWidth(), getHeight(), vignette);
    }
}
