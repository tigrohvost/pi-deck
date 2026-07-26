package dev.pideck.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

public final class ScanlineView extends View {
    private static final int[] VIGNETTE_COLORS = {
            Color.argb(42, 0, 0, 0), Color.TRANSPARENT, Color.argb(42, 0, 0, 0)
    };
    private static final float[] VIGNETTE_STOPS = {0f, 0.5f, 1f};
    private final Paint line = new Paint();
    private final Paint vignette = new Paint();

    public ScanlineView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        line.setColor(Color.argb(13, 0, 0, 0));
        line.setStrokeWidth(1f);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        vignette.setShader(new LinearGradient(
                0, 0, width, 0,
                VIGNETTE_COLORS,
                VIGNETTE_STOPS,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int y = 0; y < getHeight(); y += 4) {
            canvas.drawLine(0, y, getWidth(), y, line);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), vignette);
    }
}
