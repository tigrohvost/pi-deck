package dev.pideck.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.pideck.app.core.DeckPreferences;

/** Opt-in check on a configured, unlocked phone; launches the real Activity without navigation. */
@RunWith(AndroidJUnit4.class)
public final class LaunchComposerDeviceTest {
    @Test
    public void configuredLaunchOpensFocusedComposerAndKeyboard() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments()
                .getString("pideck.verifyComposerStartup")));
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        try {
            Intent launch = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launch);
            Activity activity = monitor.waitForActivityWithTimeout(15_000L);
            assertNotNull("MainActivity did not open", activity);
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!ready.get() && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    EditText editor = findEditor(root);
                    WindowInsets insets = root.getRootWindowInsets();
                    ready.set(editor != null && editor.isShown() && editor.isEnabled()
                            && editor.hasFocus() && root.hasWindowFocus()
                            && insets != null && insets.isVisible(WindowInsets.Type.ime()));
                });
                if (!ready.get()) Thread.sleep(100L);
            }
            assertTrue("Fresh launch required a tap before typing, or keyboard was hidden", ready.get());
            String prompt = InstrumentationRegistry.getArguments().getString("pideck.startupPrompt");
            if (prompt != null && !prompt.isBlank()) {
                instrumentation.runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    EditText editor = findEditor(root);
                    assertNotNull(editor);
                    editor.setText(prompt);
                    View send = findSend(root);
                    assertNotNull("Send action unavailable while warming", send);
                    assertTrue(send.isEnabled());
                    assertTrue(send.performClick());
                    assertEquals("The cold-start prompt was not persisted",
                            prompt, new DeckPreferences(context).queuedPrompt());
                });
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private static EditText findEditor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText result = findEditor(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findSend(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null && (description.toString().equals("Send message")
                || description.toString().equals("Отправить сообщение")
                || description.toString().equals("Add message to queue")
                || description.toString().equals("Добавить сообщение в очередь"))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findSend(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }
}
