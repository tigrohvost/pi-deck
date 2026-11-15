package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;

import dev.pideck.app.core.UiLanguage;

@RunWith(AndroidJUnit4.class)
public final class TelemetryLayoutDeviceTest {
    @Test
    public void fullWidthPanelKeepsAllReadingsVisibleOnNarrowScreens() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            for (UiLanguage language : UiLanguage.values()) {
                for (float scale : DeckStyle.TEXT_SCALES) {
                    for (int widthDp : new int[]{320, 360, 412}) {
                        DeckView.Listener listener = (DeckView.Listener) Proxy.newProxyInstance(
                                DeckView.Listener.class.getClassLoader(),
                                new Class<?>[]{DeckView.Listener.class}, (proxy, method, args) -> null);
                        DeckView deck = new DeckView(context, listener, Palette.nord(), scale, language);
                        deck.hideBootPanel();
                        deck.addTelemetrySample(2_000L, 12.3, true,
                                8L << 30, 12L << 30, 38.5);
                        measure(context, deck, widthDp, 360);
                        TelemetryView panel = findPanel(deck);
                        assertNotNull(panel);
                        assertEquals(deck.getWidth(), panel.getWidth());
                        assertEquals(3, panel.getChildCount());
                        int narrowest = Integer.MAX_VALUE, widest = 0;
                        for (int i = 0; i < 3; i++) {
                            View child = panel.getChildAt(i);
                            narrowest = Math.min(narrowest, child.getWidth());
                            widest = Math.max(widest, child.getWidth());
                            assertTrue(child.getContentDescription().length() > 10);
                            assertTrue(child.getRight() <= panel.getWidth() - panel.getPaddingRight());
                        }
                        assertTrue(widest - narrowest <= 1);
                        assertTextFits(panel);
                        assertTrue(panel.getHeight() < context.getResources().getDisplayMetrics().density * 72);
                    }
                }
            }
        });
    }

    @Test
    public void missingReadingsAndPausedSamplingNeverInventZeroTemperature() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            TelemetryView panel = new TelemetryView(context,
                    new DeckStyle(context, Palette.nord(), 1.3f), Palette.nord(), UiLanguage.ENGLISH);
            // Exercise a full ring, a pause longer than its history and unavailable sensors.
            for (int i = 0; i < 150; i++) {
                panel.addSample(i * 2_000L, 3.2, true, 6L << 30, 12L << 30, 36.2);
            }
            panel.addSample(500_000L, 0, false, 0, 0, Double.NaN);
            measure(context, panel, 320, 80);
            ViewGroup speed = (ViewGroup) panel.getChildAt(0);
            ViewGroup memory = (ViewGroup) panel.getChildAt(1);
            ViewGroup temperature = (ViewGroup) panel.getChildAt(2);
            assertEquals("0.0 tok/s", ((TextView) speed.getChildAt(1)).getText().toString());
            assertEquals("—", ((TextView) memory.getChildAt(1)).getText().toString());
            assertEquals("—", ((TextView) temperature.getChildAt(1)).getText().toString());
            assertTrue(temperature.getContentDescription().toString().contains("not CPU"));
            Bitmap bitmap = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
            panel.draw(new Canvas(bitmap));
            bitmap.recycle();
        });
    }

    private static void measure(Context context, View view, int widthDp, int heightDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static TelemetryView findPanel(View view) {
        if (view instanceof TelemetryView panel) return panel;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TelemetryView panel = findPanel(group.getChildAt(i));
                if (panel != null) return panel;
            }
        }
        return null;
    }

    private static void assertTextFits(View view) {
        if (view instanceof TextView text) {
            assertNotNull(text.getLayout());
            assertEquals(1, text.getLineCount());
            assertEquals(text.getText().length(), text.getLayout().getLineEnd(0));
            assertTrue("Clipped reading: " + text.getText(), text.getLayout().getLineWidth(0) <= text.getWidth()
                    - text.getPaddingLeft() - text.getPaddingRight() + 1);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
        }
    }
}
