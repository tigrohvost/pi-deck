package dev.pideck.app.core;

import java.util.Locale;

public enum OperationKind {
    PROBE_RUNTIME(false, 45_000L),
    INSTALL_RUNTIME(true, 1_800_000L),
    UPDATE_RUNTIME(true, 900_000L),
    INSTALL_MODEL(true, 1_800_000L),
    START_SERVER(true, 300_000L),
    STOP_SERVER(true, 60_000L),
    START_BRIDGE(true, 90_000L),
    STOP_BRIDGE(true, 60_000L),
    AGENT_TURN(true, 2_700_000L),
    ABORT_AGENT(false, 60_000L),
    NEW_SESSION(true, 60_000L),
    LIST_SESSIONS(false, 45_000L),
    ARCHIVE_SESSIONS(true, 300_000L),
    RECONCILE(false, 60_000L);

    private final boolean mutating;
    private final long timeoutMs;

    OperationKind(boolean mutating, long timeoutMs) {
        this.mutating = mutating;
        this.timeoutMs = timeoutMs;
    }

    public boolean isMutating() {
        return mutating;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OperationKind fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Operation kind is missing");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown operation kind: " + value, error);
        }
    }
}
