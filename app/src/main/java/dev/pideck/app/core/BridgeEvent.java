package dev.pideck.app.core;

import org.json.JSONException;
import org.json.JSONObject;

public final class BridgeEvent {
    public enum Type {
        BRIDGE_READY,
        BRIDGE_ERROR,
        PI_STARTED,
        PI_EXITED,
        SESSION_CREATED,
        TURN_ACCEPTED,
        TURN_STARTED,
        MODEL_THINKING_STARTED,
        MODEL_OUTPUT_DELTA,
        MODEL_OUTPUT_REJECTED,
        TOOL_CALL_REQUESTED,
        TOOL_CALL_STARTED,
        TOOL_CALL_COMPLETED,
        APPROVAL_REQUESTED,
        APPROVAL_RESOLVED,
        TURN_COMPLETED,
        TURN_FAILED,
        TURN_ABORTED,
        SESSION_STATS_CHANGED,
        CONTEXT_COMPACTION_STARTED,
        CONTEXT_COMPACTION_FINISHED,
        SESSION_COMPACTED,
        SESSION_COMPACTION_FAILED,
        SERVER_STATE_CHANGED,
        DIAGNOSTIC
    }

    public final long sequence;
    public final String bridgeInstanceId;
    public final OperationId operationId;
    public final String sessionId;
    public final Type type;
    public final String timestamp;
    public final JSONObject payload;

    private BridgeEvent(
            long sequence,
            String bridgeInstanceId,
            OperationId operationId,
            String sessionId,
            Type type,
            String timestamp,
            JSONObject payload
    ) {
        this.sequence = sequence;
        this.bridgeInstanceId = bridgeInstanceId;
        this.operationId = operationId;
        this.sessionId = sessionId;
        this.type = type;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public static BridgeEvent parse(JSONObject value) throws JSONException {
        if (value.getInt("schemaVersion") != 1) {
            throw new JSONException("Unsupported bridge event schema");
        }
        long sequence = value.getLong("sequence");
        if (sequence <= 0) throw new JSONException("Invalid bridge event sequence");
        String instanceId = value.getString("bridgeInstanceId");
        if (instanceId.isBlank()) throw new JSONException("Missing bridge instance ID");
        OperationId operationId = value.isNull("operationId")
                ? null
                : OperationId.parse(value.getString("operationId"));
        Type type;
        try {
            type = Type.valueOf(value.getString("type"));
        } catch (IllegalArgumentException error) {
            throw new JSONException("Unexpected bridge event type");
        }
        JSONObject payload = value.optJSONObject("payload");
        return new BridgeEvent(
                sequence,
                instanceId,
                operationId,
                value.isNull("sessionId") ? null : value.optString("sessionId"),
                type,
                value.getString("timestamp"),
                payload == null ? new JSONObject() : payload
        );
    }
}
