package dev.pideck.app.core;

import java.util.Objects;

/** Operation-owned composer acknowledgement for an RPC prompt awaiting authoritative acceptance. */
public final class PendingPromptDispatch {
    private OperationId operationId;
    private String prompt;

    public synchronized void begin(OperationId operationId, String prompt) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    public synchronized boolean isPending() {
        return operationId != null;
    }

    /** Returns the accepted prompt only when this exact operation still owns the pending state. */
    public synchronized String acknowledge(OperationId operationId) {
        if (!owns(operationId)) return null;
        String accepted = prompt;
        clear();
        return accepted;
    }

    /** Releases the send affordance without consuming composer text after a definite failure. */
    public synchronized boolean release(OperationId operationId) {
        if (!owns(operationId)) return false;
        clear();
        return true;
    }

    private boolean owns(OperationId candidate) {
        return candidate != null && candidate.equals(operationId);
    }

    private void clear() {
        operationId = null;
        prompt = null;
    }
}
