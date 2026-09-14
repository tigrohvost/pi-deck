package dev.pideck.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.NativeModelStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in model switch used by handset runtime tests; no default invocation changes state. */
@RunWith(AndroidJUnit4.class)
public final class ModelSelectionDeviceTest {
    @Test
    public void selectInstalledPinnedModelWhenExplicitlyRequested() throws Exception {
        String modelId = InstrumentationRegistry.getArguments().getString("pideck.modelId");
        Assume.assumeTrue(modelId != null && modelId.matches("[a-z0-9][a-z0-9._-]+"));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelSpec model = ModelCatalog.initialize(context).byId(modelId).orElseThrow();
        DeckPreferences preferences = new DeckPreferences(context);
        assertTrue("Requested model is not installed", new NativeModelStore(context, preferences)
                .isInstalled(model));

        preferences.setSelectedModelId(model.id);
        Thread.sleep(1_000L);
        assertEquals(model.id, new DeckPreferences(context).selectedModelId());
    }
}
