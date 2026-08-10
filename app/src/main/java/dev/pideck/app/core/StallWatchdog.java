package dev.pideck.app.core;

/**
 * Progress-based deadline for one operation. Fires when the bridge goes silent for the
 * kind's stall window, or when the kind's overall timeout elapses regardless of progress.
 * A confirmation that the bridge still considers the operation active is not progress;
 * only delivered events of this exact operation are.
 */
public final class StallWatchdog {
    public enum Verdict { WAIT, STALLED, EXPIRED }

    private final OperationId operationId;
    private final OperationKind kind;
    private final long armedAtMs;
    private long lastProgressAtMs;

    public StallWatchdog(OperationId operationId, OperationKind kind, long nowMs) {
        this(operationId, kind, nowMs, nowMs);
    }

    /** A restored operation keeps its original overall deadline but gets a fresh stall window. */
    public StallWatchdog(OperationId operationId, OperationKind kind, long armedAtMs, long nowMs) {
        if (operationId == null || kind == null) {
            throw new IllegalArgumentException("Watchdog needs an operation and a kind");
        }
        this.operationId = operationId;
        this.kind = kind;
        this.armedAtMs = Math.min(armedAtMs, nowMs);
        this.lastProgressAtMs = nowMs;
    }

    public OperationId operationId() {
        return operationId;
    }

    public OperationKind kind() {
        return kind;
    }

    /** Counts only events that belong to this exact operation. */
    public boolean progress(OperationId source, long nowMs) {
        if (!operationId.equals(source)) return false;
        if (nowMs > lastProgressAtMs) lastProgressAtMs = nowMs;
        return true;
    }

    public Verdict verdict(long nowMs) {
        if (nowMs - armedAtMs >= kind.timeoutMs()) return Verdict.EXPIRED;
        if (nowMs - lastProgressAtMs >= kind.stallTimeoutMs()) return Verdict.STALLED;
        return Verdict.WAIT;
    }

    public long nextCheckDelayMs(long nowMs) {
        long stallDeadline = lastProgressAtMs + kind.stallTimeoutMs();
        long overallDeadline = armedAtMs + kind.timeoutMs();
        return Math.max(1L, Math.min(stallDeadline, overallDeadline) - nowMs);
    }

    public long silentForMs(long nowMs) {
        return Math.max(0L, nowMs - lastProgressAtMs);
    }
}
