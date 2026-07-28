package dev.pideck.app.core;

import java.util.Locale;

/** Whether Pi should answer conversationally or load the full local tool surface. */
public enum AgentMode {
    CHAT(
            "Чат",
            "Быстрый ответ без инструментов: меньше служебного контекста."
    ),
    AGENT(
            "Агент",
            "Работа с файлами и командами согласно выбранному профилю доступа."
    );

    public final String label;
    public final String description;

    AgentMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AgentMode fromWireName(String value) {
        if (value == null || value.isBlank()) return AGENT;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AGENT;
        }
    }

    public String label(UiLanguage language) {
        return switch (this) {
            case CHAT -> language.pick("Чат", "Chat");
            case AGENT -> language.pick("Агент", "Agent");
        };
    }

    public String description(UiLanguage language) {
        return switch (this) {
            case CHAT -> language.pick(
                    "Быстрый ответ без инструментов: меньше служебного контекста.",
                    "Fast answers without tools and with less system context."
            );
            case AGENT -> language.pick(
                    "Работа с файлами и командами согласно выбранному профилю доступа.",
                    "Files and commands are available according to the selected access profile."
            );
        };
    }
}
