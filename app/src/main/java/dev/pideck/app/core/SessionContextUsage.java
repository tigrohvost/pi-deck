package dev.pideck.app.core;

import org.json.JSONObject;

/** Bounded current-context telemetry reported by Pi's get_session_stats RPC command. */
public final class SessionContextUsage {
    private static final int PROMPT_CHOICE_PERCENT = 60;
    public final long tokens;
    public final int contextWindow;
    public final int percent;
    public final boolean estimated;

    private SessionContextUsage(long tokens, int contextWindow, int percent, boolean estimated) {
        this.tokens = tokens;
        this.contextWindow = Math.max(0, contextWindow);
        this.percent = Math.max(0, Math.min(999, percent));
        this.estimated = estimated;
    }

    public static SessionContextUsage unknown(int fallbackWindow) {
        return new SessionContextUsage(-1L, fallbackWindow, -1, false);
    }

    public static SessionContextUsage empty(int contextWindow) {
        return new SessionContextUsage(0L, contextWindow, 0, false);
    }

    public static SessionContextUsage parse(JSONObject value, int fallbackWindow) {
        if (value == null) return unknown(fallbackWindow);
        int window = positiveInt(value, "contextWindow", fallbackWindow);
        if (value.isNull("tokens")) return unknown(window);
        long tokens = Math.max(0L, value.optLong("tokens", -1L));
        if (tokens < 0L) return unknown(window);
        int calculated = window > 0
                ? (int) Math.round(tokens * 100.0d / window)
                : 0;
        int percent = value.isNull("percent")
                ? calculated
                : Math.max(0, value.optInt("percent", calculated));
        return new SessionContextUsage(
                tokens,
                window,
                percent,
                value.optBoolean("estimated", false)
        );
    }

    public boolean known() {
        return tokens >= 0L && contextWindow > 0 && percent >= 0;
    }

    public boolean shouldWarn() {
        return known() && percent >= 70;
    }

    public boolean shouldCompactSoon() {
        if (!known()) return false;
        long threshold = (contextWindow * (long) PROMPT_CHOICE_PERCENT + 99L) / 100L;
        return tokens >= threshold;
    }

    private static int positiveInt(JSONObject value, String key, int fallback) {
        int parsed = value.optInt(key, fallback);
        return parsed > 0 ? parsed : Math.max(0, fallback);
    }
}
