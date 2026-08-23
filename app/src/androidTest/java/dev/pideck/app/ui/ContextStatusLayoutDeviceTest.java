package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import dev.pideck.app.core.SessionContextUsage;
import dev.pideck.app.core.UiLanguage;

/** Physical-device regression coverage for the narrow in-progress metrics row. */
@RunWith(AndroidJUnit4.class)
public final class ContextStatusLayoutDeviceTest {
    private static final String COMPACT_CONTEXT = "CTX ≈10% · 986/10240";

    @Test
    public void inProgressContextStaysCompleteAndOnOneLineAtLargestTextScale()
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SessionContextUsage usage = SessionContextUsage.parse(
                new JSONObject()
                        .put("tokens", 986)
                        .put("contextWindow", 10_240)
                        .put("estimated", true),
                10_240
        );

        assertLanguageFits(instrumentation, context, usage, UiLanguage.ENGLISH);
        assertLanguageFits(instrumentation, context, usage, UiLanguage.RUSSIAN);
    }

    private static void assertLanguageFits(
            Instrumentation instrumentation,
            Context context,
            SessionContextUsage usage,
            UiLanguage language
    ) {
        AtomicReference<TextView> contextMetric = new AtomicReference<>();
        AtomicReference<TextView> progressMetric = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            DeckView deck = new DeckView(
                    context,
                    noOpListener(),
                    Palette.nord(),
                    DeckStyle.TEXT_SCALES[DeckStyle.TEXT_SCALES.length - 1],
                    language
            );
            deck.setContextUsage(usage, false, false);
            String progress = language == UiLanguage.ENGLISH
                    ? "Task in progress… · 00:59"
                    : "Задача выполняется… · 00:59";
            deck.setGenerationProgress(progress);

            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(360f * density);
            int height = Math.round(800f * density);
            deck.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            );
            deck.layout(0, 0, width, height);

            contextMetric.set(findText(deck, COMPACT_CONTEXT));
            progressMetric.set(findText(deck, progress));
        });

        TextView contextView = contextMetric.get();
        assertNotNull(contextView);
        assertNotNull(contextView.getLayout());
        assertEquals(1, contextView.getLineCount());
        assertEquals(0, contextView.getLayout().getEllipsisCount(0));
        assertTrue(contextView.getContentDescription().toString().contains("10"));
        assertTrue(contextView.getContentDescription().toString().contains("240"));

        TextView progressView = progressMetric.get();
        assertNotNull(progressView);
        assertEquals(1, progressView.getLineCount());
    }

    private static TextView findText(View view, String expected) {
        if (view instanceof TextView text && expected.contentEquals(text.getText())) return text;
        if (!(view instanceof ViewGroup group)) return null;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = findText(group.getChildAt(index), expected);
            if (found != null) return found;
        }
        return null;
    }

    private static DeckView.Listener noOpListener() {
        return (DeckView.Listener) Proxy.newProxyInstance(
                DeckView.Listener.class.getClassLoader(),
                new Class<?>[]{DeckView.Listener.class},
                (proxy, method, arguments) -> null
        );
    }
}
