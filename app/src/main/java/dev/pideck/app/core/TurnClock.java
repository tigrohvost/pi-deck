package dev.pideck.app.core;

import java.util.Locale;

/**
 * The timing of one running turn: when it began, when the first visible output arrived, and how
 * much has streamed since, so the deck can show elapsed time before output and a live rate after
 * it. A tool call or a rejected answer restarts the output clock without restarting the turn.
 */
public final class TurnClock {
    /** Too early a rate is noise from the first token's latency. */
    static final long FIRST_RATE_AFTER_MS = 250L;
    /** The live rate is redrawn at most this often. */
    static final long RATE_INTERVAL_MS = 750L;

    private long startedAtMs;
    private long firstOutputAtMs;
    private long streamedCharacters;
    private long lastRateUpdateMs;

    public void begin(long nowMs) {
        startedAtMs = nowMs;
        firstOutputAtMs = 0L;
        streamedCharacters = 0L;
        lastRateUpdateMs = nowMs;
    }

    public void end() {
        startedAtMs = 0L;
        firstOutputAtMs = 0L;
        streamedCharacters = 0L;
    }

    /** Records streamed output; true exactly when this is the first output of the segment. */
    public boolean output(long nowMs, int characters) {
        boolean first = firstOutputAtMs == 0L;
        if (first) firstOutputAtMs = nowMs;
        streamedCharacters += Math.max(0, characters);
        return first;
    }

    /** A tool call or a discarded answer: the next output starts a fresh rate. */
    public void restartOutput(long nowMs) {
        firstOutputAtMs = 0L;
        streamedCharacters = 0L;
        lastRateUpdateMs = nowMs;
    }

    public boolean running() {
        return startedAtMs > 0L;
    }

    /** Before the first output the deck shows the phase and elapsed time instead of a rate. */
    public boolean awaitingFirstOutput() {
        return startedAtMs > 0L && firstOutputAtMs == 0L;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public long elapsedSeconds(long nowMs) {
        return startedAtMs <= 0L ? 0L : Math.max(0L, (nowMs - startedAtMs) / 1_000L);
    }

    /** Whether a new live rate is due; consumes the slot when it is. */
    public boolean rateDue(long nowMs) {
        if (firstOutputAtMs <= 0L
                || nowMs - firstOutputAtMs < FIRST_RATE_AFTER_MS
                || nowMs - lastRateUpdateMs < RATE_INTERVAL_MS) {
            return false;
        }
        lastRateUpdateMs = nowMs;
        return true;
    }

    public GenerationSpeed speed(long nowMs) {
        return GenerationSpeed.fromStreaming(streamedCharacters, nowMs - firstOutputAtMs);
    }

    public static String formatElapsed(long seconds) {
        long minutes = Math.min(99L, seconds / 60L);
        long remainder = seconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }
}
