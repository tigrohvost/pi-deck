package dev.pideck.app.core;

/** Pure policy for the deliberately short-lived high-risk access profile. */
public final class AutonomousGrant {
    public static final long DURATION_MS = 30L * 60L * 1000L;

    private AutonomousGrant() {
    }

    public static long newExpiry(long nowMs) {
        if (nowMs < 0L || nowMs > Long.MAX_VALUE - DURATION_MS) {
            throw new IllegalArgumentException("Invalid wall clock");
        }
        return nowMs + DURATION_MS;
    }

    public static boolean isActive(long expiresAtMs, long nowMs) {
        return expiresAtMs > nowMs && expiresAtMs <= nowMs + DURATION_MS;
    }

    public static long remainingMinutes(long expiresAtMs, long nowMs) {
        long remaining = Math.max(0L, expiresAtMs - nowMs);
        return remaining == 0L ? 0L : (remaining + 59_999L) / 60_000L;
    }
}
