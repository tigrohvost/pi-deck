package dev.pideck.app.core;

import java.util.Locale;

/** User-selected interface language. Agent/user message bodies are never translated. */
public enum UiLanguage {
    RUSSIAN("ru", "Русский", "Russian", new Locale("ru")),
    ENGLISH("en", "Английский", "English", Locale.ENGLISH);

    public final String wireName;
    public final String russianLabel;
    public final String englishLabel;
    public final Locale locale;

    UiLanguage(
            String wireName,
            String russianLabel,
            String englishLabel,
            Locale locale
    ) {
        this.wireName = wireName;
        this.russianLabel = russianLabel;
        this.englishLabel = englishLabel;
        this.locale = locale;
    }

    /**
     * The deck's current language for messages built far from any view (core errors, store and
     * download failures). The Activity sets it; it defaults to Russian like the preference does.
     */
    private static volatile UiLanguage active = RUSSIAN;

    public static void setActive(UiLanguage language) {
        active = language == null ? RUSSIAN : language;
    }

    public static UiLanguage active() {
        return active;
    }

    /** {@link #pick} in the active language. */
    public static String text(String russian, String english) {
        return active.pick(russian, english);
    }

    public String pick(String russian, String english) {
        return this == ENGLISH ? english : russian;
    }

    public String label() {
        return this == ENGLISH ? englishLabel : russianLabel;
    }

    public static UiLanguage fromWireName(String value) {
        if (value == null || value.isBlank()) return RUSSIAN;
        for (UiLanguage language : values()) {
            if (language.wireName.equals(value.toLowerCase(Locale.ROOT))) return language;
        }
        return RUSSIAN;
    }
}
