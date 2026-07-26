package dev.pideck.app.core;

public enum OperationState {
    CREATED,
    DISPATCHED,
    RUNNING,
    ABORT_REQUESTED,
    UNKNOWN,
    COMPLETED,
    FAILED,
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABORTED;
    }
}
