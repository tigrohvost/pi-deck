package dev.pideck.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.function.Predicate;

/**
 * Opt-in physical-device journey for submitting non-ASCII prompts through the real composer.
 *
 * <p>Run with {@code -e pideck.livePrompt "..."}; ordinary instrumentation runs skip it.
 */
@RunWith(AndroidJUnit4.class)
public final class LivePromptDeviceTest {
    private static final long UI_TIMEOUT_MS = 15_000L;

    @Test
    public void submitPromptThroughRealUiWhenExplicitlyRequested() throws Exception {
        String prompt = InstrumentationRegistry.getArguments().getString("pideck.livePrompt");
        String encoded = InstrumentationRegistry.getArguments().getString(
                "pideck.livePromptBase64"
        );
        if ((prompt == null || prompt.isBlank()) && encoded != null && !encoded.isBlank()) {
            prompt = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        Assume.assumeTrue(prompt != null && !prompt.isBlank());

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(
                context.getPackageName()
        );
        assertNotNull(launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launch);

        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityNodeInfo console = waitFor(
                automation,
                node -> textEquals("КОНСОЛЬ", node.getText())
        );
        assertTrue(clickNodeOrParent(console));

        AccessibilityNodeInfo composer = waitFor(
                automation,
                node -> textEquals("android.widget.EditText", node.getClassName())
                        && textEquals("Что сделать?", node.getHintText())
        );
        Bundle text = new Bundle();
        text.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                prompt
        );
        assertTrue(composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));

        AccessibilityNodeInfo send = waitFor(
                automation,
                node -> textEquals("Отправить сообщение", node.getContentDescription())
        );
        assertTrue(send.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        waitFor(
                automation,
                node -> textEquals(
                        "Добавить сообщение в очередь",
                        node.getContentDescription()
                )
        );
    }

    private static AccessibilityNodeInfo waitFor(
            UiAutomation automation,
            Predicate<AccessibilityNodeInfo> predicate
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + UI_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            AccessibilityNodeInfo found = find(root, predicate);
            if (found != null) return found;
            Thread.sleep(200L);
        }
        throw new AssertionError("Expected UI node was not found");
    }

    private static AccessibilityNodeInfo find(
            AccessibilityNodeInfo root,
            Predicate<AccessibilityNodeInfo> predicate
    ) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (predicate.test(node)) return node;
            for (int index = 0; index < node.getChildCount(); index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) pending.addLast(child);
            }
        }
        return null;
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 3; depth++) {
            if (current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static boolean textEquals(String expected, CharSequence actual) {
        return actual != null && expected.contentEquals(actual);
    }
}
