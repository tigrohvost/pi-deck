package dev.pideck.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

public final class GridBackdropView extends View {
    private static final int[] BACKGROUND_COLORS = {
            Color.rgb(2, 5, 10), Color.rgb(7, 3, 19), Color.rgb(2, 10, 13)
    };
    private static final float[] BACKGROUND_STOPS = {0f, 0.58f, 1f};
    private static final int[] GLOW_COLORS = {
            Color.TRANSPARENT, Color.argb(18, 255, 43, 214), Color.TRANSPARENT
    };
    private final Paint background = new Paint();
    private final Paint grid = new Paint();
    private final Paint glow = new Paint();
    private final float density;

    public GridBackdropView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        grid.setStrokeWidth(Math.max(1f, density * 0.4f));
        glow.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        background.setShader(new LinearGradient(
                0, 0, width, height,
                BACKGROUND_COLORS,
                BACKGROUND_STOPS,
                Shader.TileMode.CLAMP
        ));
        glow.setShader(new LinearGradient(
                0, height * 0.15f, width, height * 0.82f,
                GLOW_COLORS,
                null,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        canvas.drawRect(0, 0, width, height, background);

        float step = 34f * density;
        float vanishingX = width * 0.74f;
        grid.setColor(Color.argb(19, 64, 247, 255));
        for (float y = height % step; y < height; y += step) {
            canvas.drawLine(0, y, width, y, grid);
        }
        for (float x = -width; x < width * 2f; x += step) {
            canvas.drawLine(vanishingX, 0, x, height, grid);
        }

        canvas.drawRect(0, 0, width, height, glow);
    }
}
