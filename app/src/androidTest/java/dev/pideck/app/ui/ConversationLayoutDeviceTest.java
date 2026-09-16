package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import dev.pideck.app.core.GenerationSpeed;
import dev.pideck.app.core.UiLanguage;

/** Regression coverage for the narrow conversation layouts shown on a 360 dp handset. */
@RunWith(AndroidJUnit4.class)
public final class ConversationLayoutDeviceTest {
    private static final float MAX_TEXT_SCALE =
            DeckStyle.TEXT_SCALES[DeckStyle.TEXT_SCALES.length - 1];

    @Test
    public void answerMetricsSitAboveActionsAtLargestTextScale() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AtomicReference<DeckView> deckRef = new AtomicReference<>();
        AtomicReference<TextView> rateRef = new AtomicReference<>();
        AtomicReference<TextView> copyRef = new AtomicReference<>();
        AtomicReference<TextView> shareRef = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            DeckView deck = new DeckView(
                    context, noOpListener(), Palette.deck(), MAX_TEXT_SCALE, UiLanguage.RUSSIAN
            );
            deck.hideBootPanel();
            deck.setEntries(List.of(new ConsoleEntry(
                    ConsoleEntry.Channel.AGENT,
                    "Погода в Москве на завтра.",
                    System.currentTimeMillis(),
                    "",
                    "",
                    7.3d,
                    78L
            )));
            measureAt360Dp(context, deck);

            String rate = GenerationSpeed.exact(7.3d, 78L)
                    .label(UiLanguage.RUSSIAN.locale, UiLanguage.RUSSIAN)
                    .toUpperCase(Locale.getDefault());
            deckRef.set(deck);
            rateRef.set(findText(deck, rate));
            copyRef.set(findText(deck, "КОПИРОВАТЬ"));
            shareRef.set(findText(deck, "ОТПРАВИТЬ"));
        });

        DeckView deck = deckRef.get();
        TextView rate = rateRef.get();
        TextView copy = copyRef.get();
        TextView share = shareRef.get();
        assertNotNull(rate);
        assertNotNull(copy);
        assertNotNull(share);

        Rect rateBounds = boundsIn(deck, rate);
        Rect copyBounds = boundsIn(deck, copy);
        Rect shareBounds = boundsIn(deck, share);
        assertTrue(rateBounds.bottom <= copyBounds.top);
        assertTrue(copyBounds.right <= shareBounds.left);
        assertTrue(shareBounds.right <= deck.getWidth());
    }

    @Test
    public void weatherToolNameDoesNotWrapOrTouchOtherColumns() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AtomicReference<TraceFeedView> traceRef = new AtomicReference<>();
        AtomicReference<TextView> verbRef = new AtomicReference<>();
        AtomicReference<TextView> argumentRef = new AtomicReference<>();
        AtomicReference<TextView> detailRef = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            TraceFeedView trace = new TraceFeedView(
                    context,
                    new DeckStyle(context, Palette.deck(), MAX_TEXT_SCALE),
                    UiLanguage.RUSSIAN
            );
            trace.add("weather", "{\"location\": \"Москва\"}", "готово");
            int width = Math.round(316f * context.getResources().getDisplayMetrics().density);
            trace.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            trace.layout(0, 0, width, trace.getMeasuredHeight());

            traceRef.set(trace);
            verbRef.set(findText(trace, "weather"));
            argumentRef.set(findText(trace, "{\"location\": \"Москва\"}"));
            detailRef.set(findText(trace, "готово"));
        });

        TraceFeedView trace = traceRef.get();
        TextView verb = verbRef.get();
        TextView argument = argumentRef.get();
        TextView detail = detailRef.get();
        assertNotNull(verb);
        assertNotNull(argument);
        assertNotNull(detail);
        assertEquals(1, verb.getLineCount());
        assertEquals(0, verb.getLayout().getEllipsisCount(0));

        Rect verbBounds = boundsIn(trace, verb);
        Rect argumentBounds = boundsIn(trace, argument);
        Rect detailBounds = boundsIn(trace, detail);
        assertTrue(verbBounds.right <= argumentBounds.left);
        assertTrue(argumentBounds.right <= detailBounds.left);
        assertTrue(detailBounds.right <= trace.getWidth());
    }

    private static void measureAt360Dp(Context context, View view) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(360f * density);
        int height = Math.round(800f * density);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        );
        view.layout(0, 0, width, height);
    }

    private static Rect boundsIn(ViewGroup root, View descendant) {
        Rect bounds = new Rect(0, 0, descendant.getWidth(), descendant.getHeight());
        root.offsetDescendantRectToMyCoords(descendant, bounds);
        return bounds;
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
