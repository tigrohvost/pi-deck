package dev.pideck.app.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

public final class OperationRecord {
    public static final int SCHEMA_VERSION = 1;

    public final OperationId operationId;
    public final OperationKind kind;
    public final OperationState state;
    public final long createdAtMs;
    public final long updatedAtMs;
    public final JSONObject request;
    public final JSONObject process;
    public final CommandResult result;
    public final String error;
    public final boolean uiConsumed;

    private OperationRecord(
            OperationId operationId,
            OperationKind kind,
            OperationState state,
            long createdAtMs,
            long updatedAtMs,
            JSONObject request,
            JSONObject process,
            CommandResult result,
            String error,
            boolean uiConsumed
    ) {
        this.operationId = operationId;
        this.kind = kind;
        this.state = state;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
        this.request = copy(request);
        this.process = copy(process);
        this.result = result;
        this.error = error == null ? "" : error;
        this.uiConsumed = uiConsumed;
    }

    public static OperationRecord create(
            OperationId operationId,
            OperationKind kind,
            JSONObject request,
            long nowMs
    ) {
        return new OperationRecord(
                operationId,
                kind,
                OperationState.CREATED,
                nowMs,
                nowMs,
                request,
                new JSONObject(),
                null,
                "",
                false
        );
    }

    public OperationRecord transition(OperationState next, long nowMs) {
        OperationStateMachine.requireTransition(state, next);
        return new OperationRecord(
                operationId, kind, next, createdAtMs, nowMs,
                request, process, result, error, uiConsumed
        );
    }

    public OperationRecord withResult(CommandResult value, long nowMs) {
        if (!operationId.equals(value.operationId) || kind != value.kind) {
            throw new IllegalArgumentException("Operation result identity mismatch");
        }
        OperationState terminal = value.terminalState != null
                ? value.terminalState
                : value.isSuccess() ? OperationState.COMPLETED : OperationState.FAILED;
        OperationStateMachine.requireTransition(state, terminal);
        return new OperationRecord(
                operationId,
                kind,
                terminal,
                createdAtMs,
                nowMs,
                request,
                process,
                value,
                value.isSuccess() ? "" : value.usefulError(),
                false
        );
    }

    public OperationRecord withError(String value, long nowMs) {
        OperationStateMachine.requireTransition(state, OperationState.FAILED);
        return new OperationRecord(
                operationId, kind, OperationState.FAILED, createdAtMs, nowMs,
                request, process, result, value, false
        );
    }

    public OperationRecord consumed(long nowMs) {
        return new OperationRecord(
                operationId, kind, state, createdAtMs, nowMs,
                request, process, result, error, true
        );
    }

    public JSONObject toJson(int outputLimit) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("operationId", operationId.toString());
        value.put("kind", kind.name());
        value.put("state", state.name());
        value.put("createdAt", Instant.ofEpochMilli(createdAtMs).toString());
        value.put("updatedAt", Instant.ofEpochMilli(updatedAtMs).toString());
        value.put("createdAtMs", createdAtMs);
        value.put("updatedAtMs", updatedAtMs);
        value.put("request", copy(request));
        value.put("process", copy(process));
        value.put("result", result == null ? JSONObject.NULL : result.toJson(outputLimit));
        value.put("error", error.isBlank() ? JSONObject.NULL : error);
        value.put("uiConsumed", uiConsumed);
        return value;
    }

    public static OperationRecord fromJson(JSONObject value) throws JSONException {
        int schema = value.getInt("schemaVersion");
        if (schema != SCHEMA_VERSION) {
            throw new JSONException("Unsupported operation schema: " + schema);
        }
        OperationId operationId = OperationId.parse(value.getString("operationId"));
        OperationKind kind = OperationKind.valueOf(value.getString("kind"));
        OperationState state = OperationState.valueOf(value.getString("state"));
        JSONObject request = value.optJSONObject("request");
        JSONObject process = value.optJSONObject("process");
        JSONObject resultJson = value.optJSONObject("result");
        CommandResult result = resultJson == null
                ? null
                : CommandResult.fromJson(resultJson, operationId, kind);
        return new OperationRecord(
                operationId,
                kind,
                state,
                value.getLong("createdAtMs"),
                value.getLong("updatedAtMs"),
                request,
                process,
                result,
                value.isNull("error") ? "" : value.optString("error"),
                value.optBoolean("uiConsumed", false)
        );
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
