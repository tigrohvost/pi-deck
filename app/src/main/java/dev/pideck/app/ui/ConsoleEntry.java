package dev.pideck.app.ui;

/**
 * One item in the console stream.
 *
 * <p>Tool calls carry their three columns separately — verb, argument, result — because the trace
 * feed lays them out as columns rather than as a sentence, and because a stored transcript has to
 * come back as a feed rather than as flattened text.
 */
public final class ConsoleEntry {
    public enum Channel {
        USER,
        AGENT,
        SYSTEM,
        TOOL,
        ERROR
    }

    public final Channel channel;
    /** Message body; for a tool call, the argument column. */
    public final String text;
    public final long time;
    /** Tool calls only: read, grep, bash, write… Empty on every other channel. */
    public final String verb;
    /** Tool calls only: result or duration, drawn on the trailing edge. */
    public final String detail;

    public ConsoleEntry(Channel channel, String text) {
        this(channel, text, System.currentTimeMillis());
    }

    public ConsoleEntry(Channel channel, String text, long time) {
        this(channel, text, time, "", "");
    }

    public ConsoleEntry(Channel channel, String text, long time, String verb, String detail) {
        this.channel = channel;
        this.text = text == null ? "" : text;
        this.time = time;
        this.verb = verb == null ? "" : verb;
        this.detail = detail == null ? "" : detail;
    }

    /** A tool call as the trace feed draws it. */
    public static ConsoleEntry trace(String verb, String argument, String detail) {
        return new ConsoleEntry(
                Channel.TOOL, argument, System.currentTimeMillis(), verb, detail
        );
    }

    public boolean isTrace() {
        return channel == Channel.TOOL && !verb.isEmpty();
    }
}
