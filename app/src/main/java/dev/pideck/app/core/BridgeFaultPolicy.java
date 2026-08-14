package dev.pideck.app.core;

/**
 * Keeps the always-on RPC observer from turning an absent, never-started bridge into a crash.
 *
 * <p>A polling failure is user-visible only after the bridge was known to be connected or ready.
 * Explicit launch failures are already stored by the operation result and are preserved across
 * later polling retries.
 */
public final class BridgeFaultPolicy {
    private static final String DISCONNECTED = "RPC bridge отключён";
    static final int DISCONNECT_FAILURE_THRESHOLD = 3;
    static final long DISCONNECT_GRACE_MS = 3_000L;

    private BridgeFaultPolicy() {
    }

    /**
     * Localhost can miss one read while Android reschedules Termux. A single miss is not a bridge
     * failure: surfacing it would flash a repair card and mark a live turn UNKNOWN. Three
     * consecutive failures (or a full grace interval) are enough evidence to reconcile.
     */
    public static boolean shouldSurfaceDisconnect(int consecutiveFailures, long elapsedMs) {
        if (consecutiveFailures <= 0) return false;
        return consecutiveFailures >= DISCONNECT_FAILURE_THRESHOLD
                || elapsedMs >= DISCONNECT_GRACE_MS;
    }

    public static String afterDisconnect(
            boolean wasConnected,
            boolean wasReady,
            String existingFault,
            String reason
    ) {
        String previous = clean(existingFault);
        if (!wasConnected && !wasReady) return previous;

        String current = clean(reason);
        return current.isEmpty() ? DISCONNECTED : current;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
