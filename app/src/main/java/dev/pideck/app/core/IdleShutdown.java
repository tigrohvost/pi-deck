package dev.pideck.app.core;

/**
 * Pure policy for the core idle timeout. 0 means "never stop", which is exactly
 * the pre-Stage-4 behavior; any other offered value is minutes of idleness after
 * which the foreground service stops the resident llama-server.
 */
public final class IdleShutdown {

    public static final long NEVER = 0L;
    public static final long DEFAULT_MINUTES = 10L;

    private IdleShutdown() {}

    public static boolean enabled(long timeoutMinutes) {
        return timeoutMinutes != NEVER;
    }

    public static long delayMs(long timeoutMinutes) {
        return timeoutMinutes * 60_000L;
    }

    public static boolean normalized(long timeoutMinutes) {
        return timeoutMinutes == NEVER
                || timeoutMinutes == 5L
                || timeoutMinutes == 10L
                || timeoutMinutes == 30L;
    }
}
