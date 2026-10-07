package dev.pideck.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import dev.pideck.app.core.CpuProfile;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.NativeLlamaService;
import dev.pideck.app.core.OperationId;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in native-service probe in an isolated APK; does not adopt the shared Termux runtime. */
@RunWith(AndroidJUnit4.class)
public final class NativeRuntimeProbeDeviceTest {
    @Test
    public void keepNativeProbeRunningWhenRequested() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments()
                .getString("pideck.nativeProbe")));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("Use an isolated probe APK", "dev.pideck.app.bonsaiprobe", context.getPackageName());
        ModelSpec model = ModelCatalog.initialize(context).byId("bonsai2-27b").orElseThrow();
        File file = new File(context.getFilesDir(), "models/" + model.id + "/" + model.fileName);
        assertEquals(model.bytes, file.length());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
        assertEquals(model.sha256, hash.toString());

        File ready = new File(context.getFilesDir(), "native-probe-ready.json");
        File stop = new File(context.getFilesDir(), "native-probe-stop");
        Files.deleteIfExists(ready.toPath());
        Files.deleteIfExists(stop.toPath());
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String key = UUID.randomUUID().toString();
        CpuProfile profile = CpuProfile.detect().forModel(model);
        OperationId operation = OperationId.create();
        int port = 18089;
        List<String> arguments = new ArrayList<>(
                model.nativeLlamaServerArguments(file.getAbsolutePath(), profile, port, key));
        if ("true".equals(InstrumentationRegistry.getArguments().getString("pideck.singlePool"))) {
            arguments.set(arguments.indexOf("-tb") + 1, Integer.toString(profile.decodeThreads));
            arguments.set(arguments.indexOf("-Crb") + 1, profile.decodeCpuSet);
        }
        long started = System.nanoTime();
        try {
            NativeLlamaService.start(context, operation, model, file, profile, arguments);
            long deadline = System.nanoTime() + 300_000_000_000L;
            boolean healthy = false;
            while (System.nanoTime() < deadline) {
                NativeLlamaService.Snapshot state = NativeLlamaService.snapshot(context);
                assertTrue(state.error, !"FAILED".equals(state.state));
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port + "/health").openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                try {
                    if (connection.getResponseCode() == 200) { healthy = true; break; }
                } catch (java.io.IOException loading) {
                    // Server has not bound the socket yet.
                } finally { connection.disconnect(); }
                Thread.sleep(250);
            }
            assertTrue("Native startup timed out", healthy);
            NativeLlamaService.markReady(context, operation.toString());
            NativeLlamaService.beginInference(context, "Native runtime probe");
            JSONObject value = new JSONObject().put("apiKey", key).put("port", port)
                    .put("arguments", new JSONArray(arguments)).put("profile", profile.toString())
                    .put("startupSeconds", (System.nanoTime() - started) / 1e9);
            Files.write(ready.toPath(), value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            deadline = System.nanoTime() + 900_000_000_000L;
            while (!stop.exists() && System.nanoTime() < deadline) {
                assertEquals("Native server stopped", "READY", NativeLlamaService.snapshot(context).state);
                Thread.sleep(500);
            }
            assertTrue("Probe stop signal timed out", stop.exists());
        } finally {
            NativeLlamaService.stop(context);
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (NativeLlamaService.snapshot(context).isStartingOrReady() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            Files.deleteIfExists(ready.toPath());
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
