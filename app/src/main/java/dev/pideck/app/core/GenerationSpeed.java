package dev.pideck.app.core;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/** Decode throughput shown by the console: estimated while streaming, exact when Pi settles. */
public final class GenerationSpeed {
    private static final double APPROXIMATE_CHARACTERS_PER_TOKEN = 3.8d;

    public final double tokensPerSecond;
    public final long outputTokens;
    public final boolean estimated;

    private GenerationSpeed(double tokensPerSecond, long outputTokens, boolean estimated) {
        this.tokensPerSecond = tokensPerSecond;
        this.outputTokens = outputTokens;
        this.estimated = estimated;
    }

    public static GenerationSpeed fromStreaming(long characters, long elapsedMs) {
        if (characters <= 0L || elapsedMs < 250L) return null;
        double seconds = elapsedMs / 1_000.0d;
        double rate = characters / APPROXIMATE_CHARACTERS_PER_TOKEN / seconds;
        long outputTokens = Math.max(1L, Math.round(characters / APPROXIMATE_CHARACTERS_PER_TOKEN));
        return valid(rate) ? new GenerationSpeed(rate, outputTokens, true) : null;
    }

    public static GenerationSpeed fromTerminal(JSONObject payload) {
        if (payload == null) return null;
        double rate = payload.optDouble("tokensPerSecond", Double.NaN);
        long outputTokens = payload.optLong("outputTokens", -1L);
        boolean estimated = payload.optBoolean("speedEstimated", false);
        if (!valid(rate) || outputTokens <= 0L || outputTokens > 100_000_000L) return null;
        return new GenerationSpeed(rate, outputTokens, estimated);
    }

    public static GenerationSpeed exact(double tokensPerSecond, long outputTokens) {
        if (!valid(tokensPerSecond) || outputTokens <= 0L || outputTokens > 100_000_000L) {
            return null;
        }
        return new GenerationSpeed(tokensPerSecond, outputTokens, false);
    }

    public String label(Locale locale) {
        return label(locale, UiLanguage.RUSSIAN);
    }

    public String label(Locale locale, UiLanguage language) {
        NumberFormat number = NumberFormat.getNumberInstance(locale);
        number.setGroupingUsed(false);
        number.setMinimumFractionDigits(1);
        number.setMaximumFractionDigits(1);
        String tokens = (estimated ? "≈" : "") + outputTokens
                + language.pick(" ток.", " tok");
        String rate = (estimated ? "≈" : "") + number.format(tokensPerSecond)
                + language.pick(" ток/с", " tok/s");
        return tokens + " · " + rate;
    }

    public String contentDescription(Locale locale) {
        return contentDescription(locale, UiLanguage.RUSSIAN);
    }

    public String contentDescription(Locale locale, UiLanguage language) {
        return language.pick(
                estimated ? "Примерно " : "Итог: ",
                estimated ? "Estimated " : "Final: "
        ) + label(locale, language).replace("≈", "");
    }

    private static boolean valid(double rate) {
        return Double.isFinite(rate) && rate >= 0.01d && rate <= 100_000.0d;
    }
}
