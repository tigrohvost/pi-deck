package dev.pideck.app.core;

/**
 * The decisions that stand between opening the deck and Pi receiving a prompt.
 *
 * <p>Every one of them used to be a tap or a Termux round trip. They are gathered here as pure
 * functions so the cost of the launch path is readable in one place and testable without an
 * Android context.
 */
public final class StartupPolicy {
    private static final long NATIVE_OPERATION_HANDOFF_MS = 10_000L;

    private StartupPolicy() {
    }

    /**
     * A foreground-service preference survives a process kill; its {@code Process} object does
     * not. Treating the persisted READY/STARTING label as live after that boundary makes the next
     * launch try to attach a bridge to a server that no longer exists.
     */
    public static String effectiveNativeState(String persistedState, boolean processAlive) {
        if (!processAlive
                && ("READY".equals(persistedState) || "STARTING".equals(persistedState))) {
            return "STOPPED";
        }
        return persistedState == null ? "STOPPED" : persistedState;
    }

    /**
     * Starting the foreground service and observing its new durable identity are asynchronous.
     * A snapshot from the previous process is expected briefly, but it must not be accepted
     * indefinitely or mistaken for the server that belongs to the new operation.
     */
    public static boolean nativeOperationMismatchIsFatal(
            String expectedOperationId,
            String observedOperationId,
            long elapsedMs
    ) {
        return observedOperationId != null
                && !observedOperationId.isBlank()
                && !observedOperationId.equals(expectedOperationId)
                && elapsedMs >= NATIVE_OPERATION_HANDOFF_MS;
    }

    /**
     * The Termux {@code server-stop} round trip exists to retire a managed or legacy llama-server.
     * When the app-owned service is already down and no server is claimed anywhere, that round trip
     * buys nothing and costs a Termux cold start before the model can begin loading.
     */
    public static boolean skipsRuntimeStop(
            String nativeState,
            boolean serverReady,
            boolean bridgeLive
    ) {
        if (bridgeLive || serverReady) return false;
        return "STOPPED".equals(nativeState) || "FAILED".equals(nativeState);
    }

    /**
     * Whether opening the deck should warm the core without a tap.
     *
     * <p>Finishing a bridge for a server that is already running is free: the expensive process is
     * up, and the alternative is a boot card asking for a tap that can only mean yes. Loading the
     * model is not free, so it happens only when the user asked for it in advance.
     */
    public static boolean warmsOnLaunch(
            boolean autostart,
            boolean canWarm,
            boolean serverReady,
            boolean bridgeReady,
            boolean busy,
            boolean lowMemory
    ) {
        if (!canWarm || busy) return false;
        if (serverReady) return !bridgeReady;
        return autostart && !lowMemory;
    }

    /**
     * A prompt typed at a cold core is intent, not an error. It waits in the queue while the core
     * warms instead of being bounced back with a toast, but only when warming can actually succeed.
     */
    public static boolean queuesUntilReady(
            boolean canRunAgent,
            boolean canWarmCore,
            boolean alreadyQueued
    ) {
        return !canRunAgent && canWarmCore && !alreadyQueued;
    }

    /**
     * A queued prompt has already left the composer, so a persisted session must not be replayed
     * before its size is known. Bridge restart briefly makes that telemetry unknown; failing closed
     * in that gap prevents a second readiness callback from bypassing the user's context choice.
     */
    public static boolean asksQueuedContextChoice(
            boolean hasPersistedSession,
            SessionContextUsage usage
    ) {
        if (!hasPersistedSession) return false;
        return usage == null || !usage.known() || usage.shouldCompactSoon();
    }

    /**
     * The OOM warning is worth reading once per model. Repeating it before every ignite trains the
     * user to dismiss it. Live memory pressure reported by Android is a different fact and is
     * always worth saying, so an acknowledgement never suppresses it.
     */
    public static boolean asksOomRisk(
            boolean lowMemory,
            long availableRam,
            long minimumBytes,
            long peakBytes,
            boolean acknowledged
    ) {
        if (lowMemory) return true;
        if (acknowledged) return false;
        return availableRam < Math.max(minimumBytes, peakBytes);
    }
}
