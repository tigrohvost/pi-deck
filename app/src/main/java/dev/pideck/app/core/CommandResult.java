package dev.pideck.app.core;

public final class CommandResult {
    public final String requestId;
    public final String stdout;
    public final String stderr;
    public final int exitCode;
    public final int errorCode;
    public final String errorMessage;

    public CommandResult(
            String requestId,
            String stdout,
            String stderr,
            int exitCode,
            int errorCode,
            String errorMessage
    ) {
        this.requestId = requestId == null ? "" : requestId;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.exitCode = exitCode;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean isSuccess() {
        // Termux returns Activity.RESULT_OK (-1) when there was no internal IPC error.
        // Older builds may omit the field, which reads as 0.
        return errorCode <= 0 && exitCode == 0;
    }

    public String kind() {
        int separator = requestId.indexOf(':');
        return separator > 0 ? requestId.substring(0, separator) : requestId;
    }

    public String usefulError() {
        if (!errorMessage.isBlank()) return errorMessage.trim();
        if (!stderr.isBlank()) return stderr.trim();
        return "Команда завершилась с кодом " + exitCode;
    }
}
