package dev.pideck.app.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public final class CommandResult {
    public final OperationId operationId;
    public final OperationKind kind;
    public final String stdout;
    public final String stderr;
    public final int exitCode;
    public final int errorCode;
    public final String errorMessage;
    public final OperationState terminalState;

    public CommandResult(
            OperationId operationId,
            OperationKind kind,
            String stdout,
            String stderr,
            int exitCode,
            int errorCode,
            String errorMessage
    ) {
        this(
                operationId,
                kind,
                stdout,
                stderr,
                exitCode,
                errorCode,
                errorMessage,
                null
        );
    }

    public CommandResult(
            OperationId operationId,
            OperationKind kind,
            String stdout,
            String stderr,
            int exitCode,
            int errorCode,
            String errorMessage,
            OperationState terminalState
    ) {
        if (operationId == null) throw new IllegalArgumentException("operationId is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (terminalState != null && !terminalState.isTerminal()) {
            throw new IllegalArgumentException("Explicit result state must be terminal");
        }
        this.operationId = operationId;
        this.kind = kind;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.exitCode = exitCode;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.terminalState = terminalState;
        if (terminalState == OperationState.COMPLETED && !isSuccess()) {
            throw new IllegalArgumentException("COMPLETED result must be successful");
        }
        if ((terminalState == OperationState.FAILED || terminalState == OperationState.ABORTED)
                && isSuccess()) {
            throw new IllegalArgumentException(terminalState + " result must be unsuccessful");
        }
    }

    public boolean isSuccess() {
        // Termux returns Activity.RESULT_OK (-1) when there was no internal IPC error.
        // Older builds may omit the field, which reads as 0.
        return errorCode <= 0 && exitCode == 0;
    }

    public String usefulError() {
        if (!errorMessage.isBlank()) return errorMessage.trim();
        if (!stderr.isBlank()) return stderr.trim();
        return "Команда завершилась с кодом " + exitCode;
    }

    JSONObject toJson(int outputLimitBytes) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("operationId", operationId.toString());
        value.put("kind", kind.name());
        value.put("stdout", truncateUtf8(stdout, outputLimitBytes));
        value.put("stderr", truncateUtf8(stderr, outputLimitBytes));
        value.put("exitCode", exitCode);
        value.put("errorCode", errorCode);
        value.put("errorMessage", truncateUtf8(errorMessage, 16 * 1024));
        value.put("terminalState", terminalState == null ? JSONObject.NULL : terminalState.name());
        return value;
    }

    static CommandResult fromJson(
            JSONObject value,
            OperationId expectedId,
            OperationKind expectedKind
    ) throws JSONException {
        OperationId id = OperationId.parse(value.getString("operationId"));
        OperationKind kind = OperationKind.valueOf(value.getString("kind"));
        if (!id.equals(expectedId) || kind != expectedKind) {
            throw new JSONException("Operation result identity mismatch");
        }
        OperationState terminalState = null;
        if (!value.isNull("terminalState")) {
            try {
                terminalState = OperationState.valueOf(value.getString("terminalState"));
            } catch (IllegalArgumentException error) {
                throw new JSONException("Invalid explicit terminal state");
            }
        }
        return new CommandResult(
                id,
                kind,
                value.optString("stdout"),
                value.optString("stderr"),
                value.optInt("exitCode", -1),
                value.optInt("errorCode", 0),
                value.optString("errorMessage"),
                terminalState
        );
    }

    private static String truncateUtf8(String value, int maxBytes) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) return value;
        int end = Math.min(value.length(), maxBytes);
        while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end -= Math.max(1, (end / 16));
        }
        while (end < value.length()
                && value.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            end++;
        }
        return value.substring(0, end) + "\n[output truncated]";
    }
}
