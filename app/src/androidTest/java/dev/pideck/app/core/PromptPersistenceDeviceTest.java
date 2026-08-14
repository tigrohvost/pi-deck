package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Physical-device proof that user working text survives a new application object. */
@RunWith(AndroidJUnit4.class)
public final class PromptPersistenceDeviceTest {
    @Test
    public void draftAndQueuedPromptSurviveRecreationWithoutUnboundedWrites() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeckPreferences original = new DeckPreferences(context);
        String previousDraft = original.composerDraft();
        String previousQueue = original.queuedPrompt();
        try {
            original.setComposerDraft("черновик до перезапуска");
            assertTrue(original.setQueuedPrompt("запрос ждёт ядро"));

            DeckPreferences recreated = new DeckPreferences(context);
            assertEquals("черновик до перезапуска", recreated.composerDraft());
            assertEquals("запрос ждёт ядро", recreated.queuedPrompt());
            assertFalse(recreated.setQueuedPrompt("x".repeat(64 * 1024 + 1)));

            recreated.clearQueuedPrompt();
            assertEquals("", new DeckPreferences(context).queuedPrompt());
        } finally {
            original.setComposerDraft(previousDraft);
            if (previousQueue.isEmpty()) original.clearQueuedPrompt();
            else original.setQueuedPrompt(previousQueue);
        }
    }
}
