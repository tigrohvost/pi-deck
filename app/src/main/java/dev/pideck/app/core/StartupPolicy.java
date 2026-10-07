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
    public static final int MAX_STARTUP_LINK_PROBES = 2;

    private StartupPolicy() {
    }

    /**
     * A fresh launch opens the conversation. Activity recreation restores its tab separately,
     * so a previous visit to settings never adds a navigation step to the next request.
     */
    public static int initialTab(int savedTab, boolean workNeedsAttention) {
        return 0;
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
     * The launch link probe is a Termux round trip that answers only facts the deck already
     * persisted: a confirmed link, an installed runtime of this APK's bundle, and a stopped core.
     * When autostart would load the model anyway, the load starts at once instead of after the
     * probe. The following server-adopt and bridge-start steps re-verify Termux before Pi runs, so
     * a link that broke since the last launch still fails visibly, only after the model loaded.
     * A core that may still be running is never cold-started blind: the probe owns that case.
     */
    public static boolean warmsBeforeLinkProbe(
            boolean autostart,
            boolean canWarm,
            boolean runtimeCurrent,
            String nativeState,
            boolean serverReady,
            boolean bridgeLive,
            boolean busy,
            boolean lowMemory
    ) {
        if (!autostart || !canWarm || !runtimeCurrent || busy || lowMemory) return false;
        if (serverReady || bridgeLive) return false;
        return "STOPPED".equals(nativeState) || "FAILED".equals(nativeState);
    }

    /**
     * Non-empty composer text is a stronger intent signal than merely opening the Activity.
     * Start the expensive model while the user is still typing, but retain the launch policy's
     * memory guard. Finishing a bridge for an already loaded model remains cheap under pressure.
     */
    public static boolean warmsOnComposerIntent(
            boolean hasText,
            boolean canWarm,
            boolean serverReady,
            boolean bridgeReady,
            boolean busy,
            boolean lowMemory
    ) {
        if (!hasText || !canWarm || busy) return false;
        if (serverReady) return !bridgeReady;
        return !lowMemory;
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

    /**
     * Link discovery is allowed even when the Pi runtime is missing. Coupling the probe to the
     * old core-ready bit made repair impossible after a partial Termux update and caused the link
     * card to flash while a healthy deck was merely being verified.
     */
    public static boolean probesTermuxOnLaunch(
            int attempts,
            boolean busy,
            boolean termuxInstalled,
            boolean runCommandGranted
    ) {
        return attempts < MAX_STARTUP_LINK_PROBES
                && !busy
                && termuxInstalled
                && runCommandGranted;
    }

    /**
     * The execution label while a model loads. A remembered duration of the previous load turns a
     * silent 20-30 s wait into an expectation the elapsed clock can be read against.
     */
    public static String loadingLabel(String modelTitle, long typicalLoadMs, UiLanguage language) {
        String base = language.pick("Загружаю ", "Loading ") + modelTitle;
        if (typicalLoadMs < 1_000L) return base;
        long seconds = Math.round(typicalLoadMs / 1_000d);
        return base + language.pick(" · обычно ~" + seconds + " с", " · usually ~" + seconds + " s");
    }

    /** One bounded retry absorbs Termux's cold receiver startup without hiding a real failure. */
    public static boolean retriesStartupLinkProbe(int completedAttempt) {
        return completedAttempt > 0 && completedAttempt < MAX_STARTUP_LINK_PROBES;
    }
}
