package dev.pideck.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates native server readiness with the durable Termux-side Pi provider contract. */
public final class NativeLlamaController {
    private static final String PREFS = "native_llama_controller";
    private static final String RUNTIME_BUILD = "b10092";
    private static final int PORT = 8080;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NativeLlamaController() {
    }

    public static void start(
            Context context,
            OperationId operationId,
            ModelSpec model,
            NativeModelStore store
    ) {
        Context app = context.getApplicationContext();
        CpuProfile profile = CpuProfile.detect();
        String apiKey = newApiKey();
        File modelFile = store.fileFor(model);
        remember(app, operationId, model, apiKey, profile);
        NativeLlamaService.start(
                app,
                operationId,
                model,
                modelFile,
                profile,
                model.nativeLlamaServerArguments(
                        modelFile.getAbsolutePath(),
                        profile,
                        PORT,
                        apiKey
                )
        );
        monitorAndAdopt(app, operationId, model, apiKey, profile);
    }

    /** Reattaches the durable operation after an Activity recreation in the same app process. */
    public static void resume(
            Context context,
            OperationId operationId,
            ModelSpec model
    ) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!operationId.toString().equals(prefs.getString("operation_id", ""))
                || !model.id.equals(prefs.getString("model_id", ""))) {
            publishFailure(app, operationId, "Native server recovery metadata is missing");
            return;
        }
        String apiKey = prefs.getString("api_key", "");
        CpuProfile profile = CpuProfile.detect();
        if (apiKey.isBlank()) {
            publishFailure(app, operationId, "Native server API key is missing");
            return;
        }
        monitorAndAdopt(app, operationId, model, apiKey, profile);
    }

    public static void stopThen(Context context, Runnable continuation) {
        Context app = context.getApplicationContext();
        NativeLlamaService.stop(app);
        EXECUTOR.execute(() -> {
            long deadline = System.nanoTime() + 12_000_000_000L;
            while (System.nanoTime() < deadline) {
                String state = NativeLlamaService.snapshot(app).state;
                if ("STOPPED".equals(state) || "FAILED".equals(state)) break;
                sleep(100);
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            MAIN.post(continuation);
        });
    }

    private static void monitorAndAdopt(
            Context context,
            OperationId operationId,
            ModelSpec model,
            String apiKey,
            CpuProfile profile
    ) {
        String key = operationId.toString();
        if (!ACTIVE.add(key)) return;
        EXECUTOR.execute(() -> {
            try {
                long deadline = System.nanoTime() + 240_000_000_000L;
                String lastError = "llama-server ещё загружает модель";
                while (System.nanoTime() < deadline) {
                    NativeLlamaService.Snapshot service = NativeLlamaService.snapshot(context);
                    if ("FAILED".equals(service.state)) {
                        throw new IOException(
                                service.error.isBlank()
                                        ? "Native llama-server stopped during startup"
                                        : service.error
                        );
                    }
                    if (!service.operationId.isBlank()
                            && !key.equals(service.operationId)) {
                        throw new IOException("Native server operation identity changed");
                    }
                    try {
                        strictHealth(model.id, apiKey);
                        NativeLlamaService.markReady(context, key);
                        dispatchAdoption(
                                context,
                                operationId,
                                model,
                                apiKey,
                                profile,
                                service.pid
                        );
                        return;
                    } catch (IOException error) {
                        lastError = error.getMessage();
                    }
                    sleep(250);
                }
                throw new IOException("Native llama-server startup timed out: " + lastError);
            } catch (IOException | RuntimeException error) {
                NativeLlamaService.stop(context);
                publishFailure(context, operationId, safeError(error));
            } finally {
                ACTIVE.remove(key);
            }
        });
    }

    private static void dispatchAdoption(
            Context context,
            OperationId operationId,
            ModelSpec model,
            String apiKey,
            CpuProfile profile,
            long pid
    ) {
        try {
            JSONObject request = new JSONObject();
            request.put("schemaVersion", 1);
            request.put("operationId", operationId.toString());
            request.put("modelId", model.id);
            request.put("modelSha256", model.sha256);
            request.put("port", PORT);
            request.put("apiKey", apiKey);
            request.put("owner", "android-native");
            request.put("runtimeBuild", RUNTIME_BUILD);
            request.put("pid", pid);
            request.put("decodeThreads", profile.decodeThreads);
            request.put("batchThreads", profile.batchThreads);
            request.put("decodeCpuSet", profile.decodeCpuSet);
            request.put("batchCpuSet", profile.batchCpuSet);
            new TermuxBridge(context).runRuntime(
                    operationId,
                    OperationKind.START_SERVER,
                    "server-adopt",
                    request.toString()
            );
        } catch (JSONException | RuntimeException error) {
            throw new IllegalStateException("Could not register native server", error);
        }
    }

    private static void strictHealth(String modelId, String apiKey) throws IOException {
        JSONObject health = request("/health", apiKey);
        if (!"ok".equals(health.optString("status"))) {
            throw new IOException("Native health is not ready");
        }
        JSONObject models = request("/v1/models", apiKey);
        JSONArray data = models.optJSONArray("data");
        int exact = 0;
        for (int index = 0; data != null && index < data.length(); index++) {
            JSONObject item = data.optJSONObject(index);
            if (item != null && modelId.equals(item.optString("id"))) exact++;
        }
        if (exact != 1) throw new IOException("Native server exposed the wrong model");
    }

    private static JSONObject request(String path, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + PORT + path
        ).openConnection();
        connection.setConnectTimeout(750);
        connection.setReadTimeout(1_500);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from native server");
            }
            byte[] raw;
            try (InputStream input = connection.getInputStream()) {
                raw = readBounded(input, 256 * 1024);
            }
            try {
                return new JSONObject(new String(raw, StandardCharsets.UTF_8));
            } catch (JSONException error) {
                throw new IOException("Native health returned malformed JSON", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (output.size() + count > maximum) {
                throw new IOException("Native health response is oversized");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void remember(
            Context context,
            OperationId operationId,
            ModelSpec model,
            String apiKey,
            CpuProfile profile
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("operation_id", operationId.toString())
                .putString("model_id", model.id)
                .putString("model_sha256", model.sha256)
                .putString("api_key", apiKey)
                .putString("profile", profile.toString())
                .apply();
    }

    private static String newApiKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void publishFailure(
            Context context,
            OperationId operationId,
            String message
    ) {
        String output;
        try {
            output = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("ok", false)
                    .put("error", new JSONObject()
                            .put("code", "NATIVE_SERVER_FAILED")
                            .put("message", message))
                    .toString();
        } catch (JSONException impossible) {
            output = "";
        }
        CommandResult result = new CommandResult(
                operationId,
                OperationKind.START_SERVER,
                output,
                "",
                2,
                0,
                message
        );
        new OperationStore(context).recordResult(result);
        CommandEvents.publish(result);
    }

    private static String safeError(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
