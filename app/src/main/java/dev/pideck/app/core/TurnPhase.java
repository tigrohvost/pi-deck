package dev.pideck.app.core;

/**
 * The single place a running turn's phase lives. The execution row, the status label and the
 * progress ticker derive their text from it, so a periodic state refresh or a late event can no
 * longer leave two of them saying different things.
 */
public enum TurnPhase {
    PREPARING("Готовлю контекст", "Preparing context", false),
    THINKING("Модель думает", "Model is thinking", true),
    WRITING("Печатает ответ", "Writing answer", true),
    /** A tool call; the call itself (verb and argument) is the label. */
    TOOL("", "", false),
    /** A bounded answer retry; the retry state is the label. */
    RETRYING("", "", true),
    REJECTED("Ответ отклонён", "Answer rejected", false),
    COMPACTING("Сжимаю историю", "Compacting history", true),
    /** A turn found already running after the Activity was recreated. */
    RESUMED("Задача продолжается", "Task in progress", true);

    private final String russian;
    private final String english;
    private final boolean ongoing;

    TurnPhase(String russian, String english, boolean ongoing) {
        this.russian = russian;
        this.english = english;
        this.ongoing = ongoing;
    }

    /** The busy-row label: the phase name, or the detail for phases named by their subject. */
    public String label(UiLanguage language, String detail) {
        if (russian.isEmpty()) return detail == null ? "" : detail;
        return language.pick(russian, english);
    }

    /** The progress label shown before output: open-ended phases read as still running. */
    public String progressLabel(UiLanguage language, String detail) {
        String label = label(language, detail);
        return ongoing && !label.isEmpty() && !label.endsWith("…") ? label + "…" : label;
    }
}
