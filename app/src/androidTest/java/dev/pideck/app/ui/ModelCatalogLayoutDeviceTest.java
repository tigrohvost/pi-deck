package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.pideck.app.core.UiLanguage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Catalog navigation and narrow-screen actions, without changing installed models. */
@RunWith(AndroidJUnit4.class)
public final class ModelCatalogLayoutDeviceTest {
    @Test
    public void compactCatalogExpandsAndSurvivesProgressRefreshes() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            CoreRootView view = createView(instrumentation.getTargetContext(), UiLanguage.ENGLISH);
            CoreRootView.State state = state(UiLanguage.ENGLISH, null, null);
            view.render(state);
            assertNotNull(findText(view, "Installed model"));
            assertNull(findText(view, "Bonsai 2 9B"));
            assertNotNull(findText(view, "Operating mode"));
            findText(view, "All models · 2").performClick();
            assertNotNull(findText(view, "Bonsai 2 9B"));
            // The heartbeat changes other settings; expansion must remain a view preference.
            state.maximumSpeed = !state.maximumSpeed;
            view.render(state);
            assertNotNull(findText(view, "Bonsai 2 9B"));
            findText(view, "Collapse catalog").performClick();
            assertNull(findText(view, "Bonsai 2 9B"));
        });
    }

    @Test
    public void cardTapOpensDetailsWithoutCancellingTheDownload() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            AtomicInteger cancellations = new AtomicInteger();
            AtomicInteger details = new AtomicInteger();
            CoreRootView view = createView(instrumentation.getTargetContext(), UiLanguage.ENGLISH);
            view.render(state(UiLanguage.ENGLISH, cancellations::incrementAndGet,
                    details::incrementAndGet));
            View card = (View) findText(view, "Installed model").getParent().getParent();
            card.performClick();
            assertEquals(1, details.get());
            assertEquals(0, cancellations.get());
            findText(card, "Cancel download").performClick();
            assertEquals(1, cancellations.get());
        });
    }

    @Test
    public void modelActionsWrapInsideCardsAt320DpAndLargestTextScale() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            for (UiLanguage language : UiLanguage.values()) {
                CoreRootView view = createView(context, language);
                view.render(state(language, () -> { }, () -> { }));
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(320 * density);
                int height = Math.round(800 * density);
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, width, height);
                assertFits(findText(view, language.pick("Отменить загрузку", "Cancel download")));
                assertFits(findText(view, language.pick("Подключить файл модели", "Attach model file")));
            }
        });
    }

    private static void assertFits(TextView button) {
        assertNotNull(button);
        assertNotNull(button.getLayout());
        View parent = (View) button.getParent();
        assertTrue(button.getRight() <= parent.getWidth() - parent.getPaddingRight());
        assertEquals(button.getText().length(),
                button.getLayout().getLineEnd(button.getLayout().getLineCount() - 1));
        for (int line = 0; line < button.getLayout().getLineCount(); line++) {
            assertEquals(0, button.getLayout().getEllipsisCount(line));
            assertTrue(button.getLayout().getLineWidth(line) <= button.getLayout().getWidth() + 1);
        }
    }

    private static CoreRootView createView(Context context, UiLanguage language) {
        CoreRootView.Listener listener = (CoreRootView.Listener) Proxy.newProxyInstance(
                CoreRootView.Listener.class.getClassLoader(),
                new Class<?>[]{CoreRootView.Listener.class}, (proxy, method, args) -> null);
        return new CoreRootView(context, new DeckStyle(context, Palette.nord(), 1.3f),
                listener, language);
    }

    private static CoreRootView.State state(UiLanguage language, Runnable cancel, Runnable details) {
        CoreRootView.State state = new CoreRootView.State();
        state.language = language;
        state.models.add(new CoreRootView.ModelRow("Installed model", "906 MiB", "50%", 0,
                true, true, 50, language.pick("Отменить загрузку", "Cancel download"), cancel,
                language.pick("Подключить файл модели", "Attach model file"), () -> { }, true, details));
        state.models.add(new CoreRootView.ModelRow("Bonsai 2 9B", "1.86 GiB", "Candidate", 0,
                false, true, -1, null, null, null, null, false, details));
        return state;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView label && text.toUpperCase(Locale.ROOT).equals(
                label.getText().toString().toUpperCase(Locale.ROOT))) return label;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }
}
