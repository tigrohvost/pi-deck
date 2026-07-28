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

    private BridgeFaultPolicy() {
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
