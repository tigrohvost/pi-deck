package dev.pideck.app.core;

/**
 * Pure policy for the core idle timeout. 0 means "never stop", -1 selects the
 * adaptive policy, and positive offered values are explicit minutes of idleness.
 */
public final class IdleShutdown {

    public static final long ADAPTIVE = -1L;
    public static final long NEVER = 0L;
    public static final long DEFAULT_TIMEOUT = ADAPTIVE;
    public static final long ADAPTIVE_SHORT_MINUTES = 10L;
    public static final long ADAPTIVE_LONG_MINUTES = 30L;
    public static final int ADAPTIVE_LONG_CONTEXT_PERCENT = 40;

    private IdleShutdown() {}

    public static boolean enabled(long timeoutMinutes) {
        return timeoutMinutes != NEVER;
    }

    /**
     * Retains a valuable in-memory prefix longer once at least 40% of the context
     * has been built. Unknown or small contexts keep the former ten-minute default.
     */
    public static long effectiveMinutes(
            long timeoutSetting,
            long contextTokens,
            int contextWindow
    ) {
        if (timeoutSetting != ADAPTIVE) return timeoutSetting;
        if (contextTokens < 0L || contextWindow <= 0) return ADAPTIVE_SHORT_MINUTES;
        long longContextThreshold = (
                contextWindow * (long) ADAPTIVE_LONG_CONTEXT_PERCENT + 99L
        ) / 100L;
        return contextTokens >= longContextThreshold
                ? ADAPTIVE_LONG_MINUTES
                : ADAPTIVE_SHORT_MINUTES;
    }

    public static long delayMs(long timeoutMinutes) {
        return timeoutMinutes * 60_000L;
    }

    public static boolean normalized(long timeoutMinutes) {
        return timeoutMinutes == ADAPTIVE
                || timeoutMinutes == NEVER
                || timeoutMinutes == 5L
                || timeoutMinutes == 10L
                || timeoutMinutes == 30L;
    }
}
