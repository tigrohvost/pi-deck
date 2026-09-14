package dev.pideck.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelDownloadManager;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.NativeModelStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in handset proof for the production SHA verification and app-private GGUF install pipeline.
 *
 * <p>Run with {@code -e pideck.modelId ID}. The exact catalog entry supplies the expected size,
 * hash and private filename, so instrumentation arguments cannot weaken the admission contract.
 */
@RunWith(AndroidJUnit4.class)
public final class ModelInstallDeviceTest {
    private static final long VERIFY_TIMEOUT_MINUTES = 5L;
    private static final long INSTALL_TIMEOUT_MINUTES = 10L;

    @Test
    public void verifyAndInstallPinnedModelWhenExplicitlyRequested() throws Exception {
        String modelId = InstrumentationRegistry.getArguments().getString("pideck.modelId");
        Assume.assumeTrue(
                modelId != null && modelId.matches("[a-z0-9][a-z0-9._-]+")
        );

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeckPreferences preferences = new DeckPreferences(context);
        ModelSpec model = ModelCatalog.initialize(context).byId(modelId).orElseThrow();
        if ("true".equals(InstrumentationRegistry.getArguments()
                .getString("pideck.useStagedSource"))) {
            File stagingDirectory = context.getExternalFilesDir("model-test");
            assertTrue("App-scoped staging directory is unavailable", stagingDirectory != null);
            File staged = new File(
                    stagingDirectory,
                    model.id + "-" + model.sha256.substring(0, 12) + ".gguf"
            );
            assertTrue("Exact app-scoped staged GGUF is unavailable", staged.isFile());
            preferences.clearDownloadId(model.id);
            preferences.clearExternalModelUri(model.id);
            preferences.setDownloadUri(model.id, Uri.fromFile(staged).toString());
        }
        ModelDownloadManager downloads = new ModelDownloadManager(context, preferences);
        NativeModelStore privateModels = new NativeModelStore(context, preferences);
        assertTrue("Pinned incoming GGUF is unavailable", downloads.isDownloaded(model));

        AtomicReference<ModelDownloadManager.VerifyResult> verification =
                new AtomicReference<>();
        CountDownLatch verified = new CountDownLatch(1);
        downloads.verifyAsync(model, new ModelDownloadManager.VerifyListener() {
            @Override
            public void onProgress(int percent) {
                // Progress is deliberately consumed without changing production preferences.
            }

            @Override
            public void onComplete(ModelDownloadManager.VerifyResult result) {
                verification.set(result);
                verified.countDown();
            }
        });
        assertTrue(
                "Timed out while hashing the incoming GGUF",
                verified.await(VERIFY_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        );
        ModelDownloadManager.VerifyResult verifyResult = verification.get();
        assertTrue(
                "Incoming GGUF verification failed: " + verifyResult.failure
                        + " " + verifyResult.error,
                verifyResult.valid
        );
        assertEquals(model.sha256, verifyResult.actualHash);
        preferences.setModelVerified(model, true);

        AtomicBoolean installed = new AtomicBoolean();
        AtomicReference<String> installError = new AtomicReference<>("");
        CountDownLatch copied = new CountDownLatch(1);
        privateModels.installAsync(model, downloads, new NativeModelStore.Listener() {
            @Override
            public void onProgress(int percent) {
                // NativeModelStore itself owns the durable state transition.
            }

            @Override
            public void onComplete(boolean valid, String error) {
                installed.set(valid);
                installError.set(error);
                copied.countDown();
            }
        });
        assertTrue(
                "Timed out while copying the verified GGUF into app-private storage",
                copied.await(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        );
        assertTrue("Private GGUF installation failed: " + installError.get(), installed.get());
        assertTrue(privateModels.isInstalled(model));

        File privateFile = privateModels.fileFor(model);
        assertEquals(model.bytes, privateFile.length());
        assertFalse("Private GGUF must be read-only", privateFile.canWrite());
        // Instrumentation exits its target process immediately after the test; give Android's
        // asynchronous SharedPreferences.apply() enough time to commit the durable marker.
        Thread.sleep(1_000L);
        assertTrue(preferences.isPrivateModelInstalled(model));
    }
}
