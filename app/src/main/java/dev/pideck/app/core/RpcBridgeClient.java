package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated, bounded HTTP client for the Termux Pi RPC bridge. */
public final class RpcBridgeClient implements AutoCloseable {
    public interface Listener {
        void onConnected(JSONObject state);

        void onEvent(BridgeEvent event);

        void onEventGap(String bridgeInstanceId, long earliestReceived);

        void onDisconnected(String reason);
    }

    public static final int DEFAULT_PORT = 8787;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_COMMAND_BYTES = 128 * 1024;
    private static final long STATE_REFRESH_INTERVAL_NANOS = 5_000_000_000L;

    private final String token;
    private final int port;
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pideck-rpc-events");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public RpcBridgeClient(String token) {
        this(token, DEFAULT_PORT);
    }

    RpcBridgeClient(String token, int port) {
        if (token == null
                || token.length() != 43
                || !token.matches("^[A-Za-z0-9_-]{43}$")) {
            throw new IllegalArgumentException("Bridge token is missing");
        }
        this.token = token;
        this.port = port;
    }

    public JSONObject state() throws IOException, JSONException {
        JSONObject response = request("GET", "/v1/state", null, 1_500, 2_500);
        requireSuccess(response);
        JSONObject state = response.optJSONObject("state");
        if (state == null || state.optInt("schemaVersion", -1) != 1) {
            throw new JSONException("Bridge state is missing or unsupported");
        }
        return state;
    }

    public JSONObject command(
            OperationId operationId,
            String type,
            JSONObject payload
    ) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("schemaVersion", 1);
        body.put("operationId", operationId.toString());
        body.put("type", type);
        body.put("payload", payload == null ? new JSONObject() : payload);
        JSONObject response = request("POST", "/v1/commands", body, 2_000, 5_000);
        requireSuccess(response);
        return response;
    }

    public void startPolling(
            String savedInstanceId,
            long savedSequence,
            Listener listener
    ) {
        if (!polling.compareAndSet(false, true)) return;
        pollingExecutor.execute(() -> pollLoop(savedInstanceId, savedSequence, listener));
    }

    private void pollLoop(
            String savedInstanceId,
            long savedSequence,
            Listener listener
    ) {
        String instanceId = savedInstanceId;
        long sequence = Math.max(0L, savedSequence);
        long backoff = 400L;
        boolean stateKnown = false;
        long nextStateRefresh = 0L;
        while (polling.get()) {
            try {
                long now = System.nanoTime();
                if (!stateKnown || now >= nextStateRefresh) {
                    JSONObject state = state();
                    String currentInstance = state.getString("bridgeInstanceId");
                    listener.onConnected(state);
                    if (!currentInstance.equals(instanceId)) {
                        instanceId = currentInstance;
                        sequence = 0L;
                    }
                    stateKnown = true;
                    nextStateRefresh = System.nanoTime() + STATE_REFRESH_INTERVAL_NANOS;
                }
                JSONObject response = request(
                        "GET",
                        "/v1/events?after=" + sequence + "&timeoutMs=20000",
                        null,
                        2_000,
                        23_000
                );
                requireSuccess(response);
                String responseInstance = response.getString("bridgeInstanceId");
                if (!responseInstance.equals(instanceId)) {
                    instanceId = responseInstance;
                    sequence = 0L;
                    stateKnown = false;
                    continue;
                }
                JSONArray events = response.getJSONArray("events");
                long earliest = events.length() == 0
                        ? sequence
                        : events.getJSONObject(0).getLong("sequence");
                if (response.optBoolean("eventGap", false)) {
                    listener.onEventGap(instanceId, earliest);
                }
                for (int i = 0; i < events.length(); i++) {
                    BridgeEvent event = BridgeEvent.parse(events.getJSONObject(i));
                    if (!event.bridgeInstanceId.equals(instanceId)) {
                        throw new JSONException("Bridge event instance mismatch");
                    }
                    if (event.sequence <= sequence) continue;
                    if (event.sequence != sequence + 1
                            && !response.optBoolean("eventGap", false)) {
                        listener.onEventGap(instanceId, event.sequence);
                    }
                    sequence = event.sequence;
                    listener.onEvent(event);
                }
                backoff = 400L;
            } catch (IOException | JSONException | IllegalArgumentException error) {
                stateKnown = false;
                listener.onDisconnected(safeMessage(error));
                sleep(backoff + ThreadLocalRandom.current().nextLong(0, 250));
                backoff = Math.min(8_000L, backoff * 2L);
            }
        }
    }

    private JSONObject request(
            String method,
            String path,
            JSONObject body,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("X-PiDeck-Token", token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_COMMAND_BYTES) {
                throw new IOException("Bridge command exceeds bounded size");
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encoded.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
        }
        int status = connection.getResponseCode();
        byte[] raw;
        try {
            raw = readBounded(
                    status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream()
            );
        } finally {
            connection.disconnect();
        }
        JSONObject response = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (status < 200 || status >= 300) {
            JSONObject error = response.optJSONObject("error");
            String code = error == null ? "HTTP_" + status : error.optString("code", "HTTP_" + status);
            String message = error == null ? "Bridge request failed" : error.optString("message");
            throw new IOException(code + ": " + message);
        }
        return response;
    }

    private static void requireSuccess(JSONObject response) throws JSONException {
        if (response.optInt("schemaVersion", -1) != 1 || !response.optBoolean("ok", false)) {
            throw new JSONException("Bridge response is not a successful schema-v1 object");
        }
    }

    private static byte[] readBounded(java.io.InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Bridge returned no response body");
        try (BufferedInputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > MAX_RESPONSE_BYTES) {
                    throw new IOException("Bridge response exceeds bounded size");
                }
            }
            return output.toByteArray();
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    @Override
    public void close() {
        polling.set(false);
        pollingExecutor.shutdownNow();
    }
}
