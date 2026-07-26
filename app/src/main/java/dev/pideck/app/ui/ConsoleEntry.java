package dev.pideck.app.ui;

public final class ConsoleEntry {
    public enum Channel {
        USER,
        AGENT,
        SYSTEM,
        TOOL,
        ERROR
    }

    public final Channel channel;
    public final String text;
    public final long time;

    public ConsoleEntry(Channel channel, String text) {
        this(channel, text, System.currentTimeMillis());
    }

    public ConsoleEntry(Channel channel, String text, long time) {
        this.channel = channel;
        this.text = text == null ? "" : text;
        this.time = time;
    }
}
