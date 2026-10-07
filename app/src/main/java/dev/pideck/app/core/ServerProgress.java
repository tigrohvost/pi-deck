package dev.pideck.app.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live prompt-ingestion and decode progress read from llama-server's own log lines.
 *
 * <p>The server reports prefill as {@code prompt processing, n_tokens = 917, progress = 0.64,
 * t = 9.79 s / 93.66 tokens per second} at batch boundaries, and decode as {@code n_decoded =
 * 120, tg = 15.20 t/s} every few seconds. The app already pumps that stream into its private log,
 * so tapping it costs nothing on the inference path and needs no extra HTTP request that would
 * itself wait between batches.
 */
public final class ServerProgress {
    public enum Kind {
        PROMPT,
        DECODE
    }

    private static final Pattern PROMPT = Pattern.compile(
            "prompt processing, n_tokens =\\s*(\\d+), progress =\\s*([0-9.]+), "
                    + "t =\\s*([0-9.]+) s / ([0-9.]+) tokens per second"
    );
    private static final Pattern DECODE = Pattern.compile(
            "n_decoded =\\s*(\\d+), tg =\\s*([0-9.]+) t/s"
    );
    private static final AtomicReference<ServerProgress> LATEST = new AtomicReference<>();
    private static final int MAX_LINE_BYTES = 1024;

    public final Kind kind;
    /** Server task the line belongs to, or -1 when the line carries none. */
    public final long taskId;
    /** Prompt tokens evaluated in this request, or tokens decoded so far. */
    public final long tokens;
    /** Prompt only: fraction of the whole prompt now in the slot, cached tokens included. */
    public final double fraction;
    /** Prompt only: seconds of prompt processing so far, as the server measured them. */
    public final double serverSeconds;
    public final double tokensPerSecond;
    /** Prompt only: fraction gained per second within this task; NaN until two reports. */
    public final double fractionPerSecond;
    /** Monotonic clock of the observation, comparable with {@code SystemClock.uptimeMillis()}. */
    public final long observedAtMs;

    private static final Pattern TASK = Pattern.compile("\\| task (\\d+) \\|");

    private ServerProgress(Kind kind, long taskId, long tokens, double fraction, double serverSeconds,
                           double tokensPerSecond, double fractionPerSecond, long observedAtMs) {
        this.kind = kind;
        this.taskId = taskId;
        this.tokens = tokens;
        this.fraction = fraction;
        this.serverSeconds = serverSeconds;
        this.tokensPerSecond = tokensPerSecond;
        this.fractionPerSecond = fractionPerSecond;
        this.observedAtMs = observedAtMs;
    }

    /** One server log line, or null when it carries no progress. */
    public static ServerProgress parse(String line, long nowMs) {
        if (line == null) return null;
        try {
            Matcher task = TASK.matcher(line);
            long taskId = task.find() ? Long.parseLong(task.group(1)) : -1L;
            Matcher prompt = PROMPT.matcher(line);
            if (prompt.find()) {
                double fraction = Math.max(0d, Math.min(1d, Double.parseDouble(prompt.group(2))));
                return new ServerProgress(
                        Kind.PROMPT,
                        taskId,
                        Long.parseLong(prompt.group(1)),
                        fraction,
                        Double.parseDouble(prompt.group(3)),
                        Double.parseDouble(prompt.group(4)),
                        Double.NaN,
                        nowMs
                );
            }
            Matcher decode = DECODE.matcher(line);
            if (decode.find()) {
                return new ServerProgress(
                        Kind.DECODE,
                        taskId,
                        Long.parseLong(decode.group(1)),
                        Double.NaN,
                        Double.NaN,
                        Double.parseDouble(decode.group(2)),
                        Double.NaN,
                        nowMs
                );
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /**
     * Records an observation. A second prompt report of the same server task yields its real
     * rate of progress, so the estimate needs no outside guess of the prompt size: a request
     * may be the snapshot warm-up of the system prefix or the turn itself.
     */
    public static void publish(ServerProgress progress) {
        if (progress == null) return;
        ServerProgress previous = LATEST.get();
        if (progress.kind == Kind.PROMPT && previous != null && previous.kind == Kind.PROMPT
                && previous.taskId == progress.taskId && progress.taskId >= 0
                && progress.serverSeconds > previous.serverSeconds
                && progress.fraction > previous.fraction) {
            double velocity = (progress.fraction - previous.fraction)
                    / (progress.serverSeconds - previous.serverSeconds);
            progress = new ServerProgress(progress.kind, progress.taskId, progress.tokens,
                    progress.fraction, progress.serverSeconds, progress.tokensPerSecond,
                    velocity, progress.observedAtMs);
        }
        LATEST.set(progress);
    }

    /** The newest observation made at or after {@code sinceMs}, or null. */
    public static ServerProgress latestSince(long sinceMs) {
        ServerProgress value = LATEST.get();
        return value != null && value.observedAtMs >= sinceMs ? value : null;
    }

    static void reset() {
        LATEST.set(null);
    }

    /** Whether the server has reported the whole prompt as read. */
    public boolean promptComplete() {
        return kind == Kind.PROMPT && fraction >= 1d;
    }

    /**
     * Prefill completion estimated for {@code nowMs}: the last reported fraction advanced at the
     * measured rate of this task, never claiming completion before the server does.
     */
    public double estimatedFraction(long nowMs) {
        if (kind != Kind.PROMPT) return Double.NaN;
        if (fraction >= 1d) return 1d;
        if (!(fractionPerSecond > 0d)) return fraction;
        double advanced = fractionPerSecond * Math.max(0L, nowMs - observedAtMs) / 1000d;
        return Math.min(0.99d, fraction + advanced);
    }

    /** Seconds of prefill left, or -1 until the rate of this task is known. */
    public long remainingSeconds(long nowMs) {
        if (kind != Kind.PROMPT || !(fractionPerSecond > 0d)) return -1L;
        if (fraction >= 1d) return 0L;
        return Math.max(1L, Math.round((1d - estimatedFraction(nowMs)) / fractionPerSecond));
    }

    /** Short label for the progress metric, in the deck's language. */
    public String label(long nowMs, UiLanguage language) {
        Locale locale = language.locale;
        if (kind == Kind.DECODE) {
            return String.format(
                    locale,
                    language.pick("Думаю · %d ток. · %.1f ток/с", "Thinking · %d tok · %.1f tok/s"),
                    tokens, tokensPerSecond
            );
        }
        int percent = (int) Math.floor(estimatedFraction(nowMs) * 100d);
        String base = String.format(
                locale,
                language.pick("Читаю промпт %d%% · %.0f ток/с", "Reading prompt %d%% · %.0f tok/s"),
                percent, tokensPerSecond
        );
        long remaining = remainingSeconds(nowMs);
        if (remaining < 0L) return base;
        return base + String.format(locale, language.pick(" · ~%d с", " · ~%d s"), remaining);
    }

    /**
     * Passes the server's output through unchanged while publishing every progress line it
     * carries. Lines are bounded so a pathological line can never grow the buffer.
     */
    public static final class Tap extends FilterInputStream {
        private final byte[] line = new byte[MAX_LINE_BYTES];
        private final LongClock clock;
        private int length;

        public Tap(InputStream input, LongClock clock) {
            super(input);
            this.clock = clock;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) observe((byte) value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            int read = super.read(buffer, offset, count);
            for (int index = 0; index < read; index++) observe(buffer[offset + index]);
            return read;
        }

        private void observe(byte value) {
            if (value == '\n') {
                publish(parse(new String(line, 0, length, StandardCharsets.UTF_8), clock.now()));
                length = 0;
            } else if (length < line.length) {
                line[length++] = value;
            }
        }
    }

    /** Injectable monotonic clock. */
    public interface LongClock {
        long now();
    }
}
