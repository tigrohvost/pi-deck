package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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
    public void truncatedAnswersRemainVisibleAcrossRetryFailureAndRestore() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() -> {
            DeckView deck = new DeckView(context, noOpListener(), Palette.deck(),
                    MAX_TEXT_SCALE, UiLanguage.RUSSIAN);
            deck.hideBootPanel();
            deck.addEntry(new ConsoleEntry(ConsoleEntry.Channel.USER, "Поищи новости"));
            deck.appendStreaming("Первый фрагмент");
            deck.retainIncompleteStreaming("Первый фрагмент");
            deck.appendStreaming("Второй фрагмент");
            deck.retainIncompleteStreaming("Второй фрагмент");
            deck.discardStreaming();
            deck.addEntry(new ConsoleEntry(ConsoleEntry.Channel.ERROR, "Достигнут лимит вывода"));
            assertNotNull(findText(deck, "Первый фрагмент"));
            assertNotNull(findText(deck, "Второй фрагмент"));
            assertNotNull(findText(deck, "ПОВТОРИТЬ ЗАПРОС"));
            List<ConsoleEntry> saved = deck.entries();
            assertEquals(4, saved.size());
            assertEquals(ConsoleEntry.Channel.INCOMPLETE, saved.get(1).channel);
            assertEquals(ConsoleEntry.Channel.INCOMPLETE, saved.get(2).channel);
            deck.setEntries(saved);
            measureAt360Dp(context, deck);
            assertNotNull(findText(deck, "Первый фрагмент"));
            assertNotNull(findText(deck, "Второй фрагмент"));
            assertNotNull(findText(deck, "Незавершённый ответ · достигнут лимит вывода"));
        });
    }

    @Test
    public void clearSessionsIconFitsBesideNewAtLargestTextScale() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() -> {
            for (UiLanguage language : UiLanguage.values()) {
                SessionsRootView root = new SessionsRootView(context,
                        new DeckStyle(context, Palette.deck(), MAX_TEXT_SCALE), language);
                SessionsRootView.State state = new SessionsRootView.State();
                int[] clicks = {0};
                state.onNewSession = () -> { };
                state.onClear = () -> clicks[0]++;
                root.render(state);
                measureAt360Dp(context, root);
                View clear = findDescription(root, language.pick("Очистить сессии", "Clear sessions"));
                assertNotNull(clear);
                Rect bounds = boundsIn(root, clear);
                assertTrue(bounds.right <= root.getWidth());
                int target = Math.round(48f * context.getResources().getDisplayMetrics().density);
                assertTrue(clear.getWidth() >= target && clear.getHeight() >= target);
                clear.performClick();
                assertEquals(1, clicks[0]);
                state.onClear = null;
                root.render(state);
                assertEquals(null, findDescription(root,
                        language.pick("Очистить сессии", "Clear sessions")));
            }
        });
    }

    @Test
    public void answerMetricsAndIconsShareOneRowAtLargestTextScale() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AtomicReference<DeckView> deckRef = new AtomicReference<>();
        AtomicReference<TextView> rateRef = new AtomicReference<>();
        AtomicReference<View> copyRef = new AtomicReference<>();
        AtomicReference<View> shareRef = new AtomicReference<>();

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
            copyRef.set(findDescription(deck, "Копировать ответ"));
            shareRef.set(findDescription(deck, "Поделиться ответом"));
        });

        DeckView deck = deckRef.get();
        TextView rate = rateRef.get();
        View copy = copyRef.get();
        View share = shareRef.get();
        assertNotNull(rate);
        assertNotNull(copy);
        assertNotNull(share);

        Rect rateBounds = boundsIn(deck, rate);
        Rect copyBounds = boundsIn(deck, copy);
        Rect shareBounds = boundsIn(deck, share);
        assertTrue(copy instanceof ImageButton);
        assertTrue(share instanceof ImageButton);
        assertTrue(Math.abs(rateBounds.centerY() - copyBounds.centerY()) <= 1);
        assertEquals(copyBounds.centerY(), shareBounds.centerY());
        assertTrue(rateBounds.right <= copyBounds.left);
        assertTrue(copyBounds.right <= shareBounds.left);
        assertTrue(shareBounds.right <= deck.getWidth());
        assertEquals(0, rate.getLayout().getEllipsisCount(0));
        int target = Math.round(48f * context.getResources().getDisplayMetrics().density);
        assertTrue(copy.getWidth() >= target && copy.getHeight() >= target);
        assertTrue(share.getWidth() >= target && share.getHeight() >= target);
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

    @Test
    public void codeAnswersRenderAsBlocksWithTouchSizedActions() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AtomicReference<DeckView> deckRef = new AtomicReference<>();
        AtomicReference<TextView> codeRef = new AtomicReference<>();
        AtomicReference<TextView> proseRef = new AtomicReference<>();
        AtomicReference<View> shareRef = new AtomicReference<>();

        instrumentation.runOnMainSync(() -> {
            DeckView deck = new DeckView(
                    context, noOpListener(), Palette.deck(), MAX_TEXT_SCALE, UiLanguage.RUSSIAN
            );
            deck.hideBootPanel();
            deck.setEntries(List.of(new ConsoleEntry(
                    ConsoleEntry.Channel.AGENT,
                    "Исправил **деление**:\n\n```python\ndef divide(a, b):\n    return a / b\n```",
                    System.currentTimeMillis()
            )));
            measureAt360Dp(context, deck);
            deckRef.set(deck);
            codeRef.set(findText(deck, "def divide(a, b):\n    return a / b"));
            proseRef.set(findText(deck, "Исправил деление:"));
            shareRef.set(findDescription(deck, "Поделиться ответом"));
        });

        assertNotNull("fenced code was not rendered as its own block", codeRef.get());
        assertNotNull("bold markers were left in the prose", proseRef.get());
        assertNotNull(shareRef.get());
        float density = context.getResources().getDisplayMetrics().density;
        assertTrue(shareRef.get().getHeight() >= Math.round(48f * density) - 1);
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

    private static View findDescription(View view, String expected) {
        if (expected.contentEquals(view.getContentDescription() == null
                ? "" : view.getContentDescription())) return view;
        if (!(view instanceof ViewGroup group)) return null;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = findDescription(group.getChildAt(index), expected);
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
