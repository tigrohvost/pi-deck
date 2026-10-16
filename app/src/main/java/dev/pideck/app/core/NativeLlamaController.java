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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coordinates native server readiness with the durable Termux-side Pi provider contract. */
public final class NativeLlamaController {
    private static final String PREFS = "native_llama_controller";
    private static final int PORT = 8080;
    private static final ScheduledExecutorService PROBES = Executors.newScheduledThreadPool(2);
    private static final ScheduledExecutorService CONTROL = Executors.newScheduledThreadPool(2);
    private static final Map<String, NativeStartupMonitor> ACTIVE = new ConcurrentHashMap<>();
    public static final String LOAD_CANCELLED = "Загрузка модели отменена · Model load cancelled";
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
        CpuProfile profile = CpuProfile.detect().forModel(model);
        String apiKey = newApiKey();
        String generation = UUID.randomUUID().toString();
        File modelFile = store.fileFor(model);
        ACTIVE.values().forEach(NativeStartupMonitor::cancel);
        ACTIVE.clear();
        remember(app, operationId, model, apiKey, profile, generation);
        NativeLlamaService.start(
                app,
                operationId,
                generation,
                model,
                modelFile,
                profile,
                model.nativeLlamaServerArguments(
                        modelFile.getAbsolutePath(),
                        profile,
                        PORT,
                        apiKey,
                        slotDirectory(app)
                )
        );
        monitorAndAdopt(app, operationId, model, apiKey, profile, generation);
    }

    private static String slotDirectory(Context app) {
        File directory = SlotSnapshotDirectory.prepare(app.getCacheDir());
        return directory == null ? null : directory.getAbsolutePath();
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
        CpuProfile profile = CpuProfile.detect().forModel(model);
        if (apiKey.isBlank()) {
            publishFailure(app, operationId, "Native server API key is missing");
            return;
        }
        String generation = prefs.getString("server_generation", "");
        if (generation.isBlank()) {
            publishFailure(app, operationId, "Native server generation is missing");
            return;
        }
        monitorAndAdopt(app, operationId, model, apiKey, profile, generation);
    }

    /**
     * Abandons a model load the user no longer wants. The service stops the half-loaded server at
     * once; the monitor then reports the operation as cancelled instead of waiting out its 240 s
     * startup deadline against a process that is gone.
     */
    public static void cancelStart(Context context, OperationId operationId) {
        NativeStartupMonitor monitor = ACTIVE.remove(operationId.toString());
        if (monitor == null) return;
        monitor.cancel();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!operationId.toString().equals(prefs.getString("operation_id", ""))) return;
        NativeLlamaService.stopIfGeneration(context, prefs.getString("server_generation", ""));
        CONTROL.execute(() -> publishFailure(context.getApplicationContext(), operationId, LOAD_CANCELLED));
    }

    public static void stopThen(Context context, OperationId operationId, Runnable continuation) {
        Context app = context.getApplicationContext();
        ACTIVE.values().forEach(NativeStartupMonitor::cancel);
        ACTIVE.clear();
        String generation = NativeLlamaService.currentGeneration(app);
        NativeLlamaService.stopIfGeneration(app, generation);
        new NativeStopMonitor((task, delay) -> {
            var future = CONTROL.schedule(task, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, () -> System.nanoTime() / 1_000_000L, () -> {
            NativeLlamaService.Snapshot current = NativeLlamaService.snapshot(app);
            return new NativeStopMonitor.Observation(
                    generation.equals(NativeLlamaService.currentGeneration(app)), current.processAlive, current.persistedState);
        }, result -> {
            if (result == NativeStopMonitor.Result.STOPPED) {
                MAIN.post(() -> {
                    NativeLlamaService.Snapshot current = NativeLlamaService.snapshot(app);
                    if (generation.equals(NativeLlamaService.currentGeneration(app)) && !current.processAlive
                            && "STOPPED".equals(current.persistedState)) continuation.run();
                    else publishFailure(app, operationId, OperationKind.STOP_SERVER, "Native server generation changed after stop");
                });
            } else publishFailure(app, operationId, OperationKind.STOP_SERVER,
                    "Native server stop failed or generation changed within the 5 second deadline");
        }, 5_000L).start();
    }

    private static void monitorAndAdopt(
            Context context,
            OperationId operationId,
            ModelSpec model,
            String apiKey,
            CpuProfile profile,
            String generation
    ) {
        String key = operationId.toString();
        long started = System.nanoTime();
        NativeStartupMonitor[] holder = new NativeStartupMonitor[1];
        NativeStartupMonitor monitor = new NativeStartupMonitor((task, delay) -> {
            var future = PROBES.schedule(task, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, () -> System.nanoTime() / 1_000_000L, new NativeStartupMonitor.Driver() {
            private volatile HttpURLConnection connection;
            @Override public boolean current() {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                return ACTIVE.get(key) == holder[0] && key.equals(prefs.getString("operation_id", ""))
                        && generation.equals(prefs.getString("server_generation", ""));
            }
            @Override public String state() {
                NativeLlamaService.Snapshot service = NativeLlamaService.snapshot(context);
                if (!generation.equals(service.serverGeneration)) {
                    return (System.nanoTime() - started) < 10_000_000_000L ? "WAITING" : "FAILED";
                }
                return NativeLlamaService.generationRunning(generation) ? service.persistedState : "STOPPED";
            }
            @Override public boolean health() throws IOException {
                NativeLlamaService.Snapshot service = NativeLlamaService.snapshot(context);
                if (!generation.equals(service.serverGeneration) || !service.processAlive) return false;
                strictHealth(model.id, apiKey, value -> connection = value);
                return current() && NativeLlamaService.generationRunning(generation);
            }
            @Override public void adopt() {
                if (!current() || !NativeLlamaService.markReady(context, key, generation)) {
                    throw new IllegalStateException("Native generation is no longer ready for adoption");
                }
                NativeLlamaService.recordLoadMillis(context, model.id, (System.nanoTime() - started) / 1_000_000L);
                dispatchAdoption(context, operationId, model, apiKey, profile, NativeLlamaService.snapshot(context).pid);
                ACTIVE.remove(key, holder[0]);
            }
            @Override public void failed(String message) {
                if (!current()) return;
                NativeLlamaService.stopIfGeneration(context, generation);
                ACTIVE.remove(key, holder[0]);
                publishFailure(context, operationId, message);
            }
            @Override public void cancelIo() {
                HttpURLConnection active = connection;
                if (active != null) CONTROL.execute(active::disconnect);
            }
        }, 240_000L);
        holder[0] = monitor;
        if (ACTIVE.putIfAbsent(key, monitor) == null) monitor.start();
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
            request.put("runtimeBuild", model.nativeRuntimeBuild());
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

    private static void strictHealth(String modelId, String apiKey, java.util.function.Consumer<HttpURLConnection> active) throws IOException {
        JSONObject health = request("/health", apiKey, active);
        if (!"ok".equals(health.optString("status"))) {
            throw new IOException("Native health is not ready");
        }
        JSONObject models = request("/v1/models", apiKey, active);
        JSONArray data = models.optJSONArray("data");
        int exact = 0;
        for (int index = 0; data != null && index < data.length(); index++) {
            JSONObject item = data.optJSONObject(index);
            if (item != null && modelId.equals(item.optString("id"))) exact++;
        }
        if (exact != 1) throw new IOException("Native server exposed the wrong model");
    }

    private static JSONObject request(String path, String apiKey, java.util.function.Consumer<HttpURLConnection> active) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + PORT + path
        ).openConnection();
        active.accept(connection);
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
            active.accept(null);
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
            CpuProfile profile,
            String generation
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("operation_id", operationId.toString())
                .putString("server_generation", generation)
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
        publishFailure(context, operationId, OperationKind.START_SERVER, message);
    }

    private static void publishFailure(Context context, OperationId operationId, OperationKind kind, String message) {
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
                kind,
                output,
                "",
                2,
                0,
                message
        );
        new OperationStore(context).recordResult(result);
        CommandEvents.publish(result);
    }

}
