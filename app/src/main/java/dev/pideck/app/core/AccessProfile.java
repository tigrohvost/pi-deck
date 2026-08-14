package dev.pideck.app.core;

import java.util.List;
import java.util.Locale;

/** Agent capabilities are selected independently from model and session state. */
public enum AccessProfile {
    READ_ONLY(
            "READ ONLY",
            "Чтение и управляемый веб-поиск. Shell и запись отключены.",
            true
    ),
    CONFIRM_CHANGES(
            "CONFIRM CHANGES",
            "Веб-поиск доступен; bash/edit/write требуют одноразового подтверждения Android.",
            true
    ),
    AUTONOMOUS(
            "AUTONOMOUS",
            "Pi может искать в сети, выполнять shell-команды и менять доступные Termux файлы.",
            true
    );

    public final String label;
    public final String description;
    public final boolean toolNetworkPossible;

    AccessProfile(String label, String description, boolean toolNetworkPossible) {
        this.label = label;
        this.description = description;
        this.toolNetworkPossible = toolNetworkPossible;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AccessProfile shippedDefault() {
        return CONFIRM_CHANGES;
    }

    public static AccessProfile fromWireName(String value) {
        if (value == null || value.isBlank()) return READ_ONLY;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return READ_ONLY;
        }
    }

    public String description(UiLanguage language) {
        return switch (this) {
            case READ_ONLY -> language.pick(
                    "Чтение и управляемый веб-поиск. Shell и запись отключены.",
                    "Read access and managed web search. Shell and file writes are disabled."
            );
            case CONFIRM_CHANGES -> language.pick(
                    "Веб-поиск доступен; bash/edit/write требуют одноразового подтверждения Android.",
                    "Web search is available; bash/edit/write require one-time Android approval."
            );
            case AUTONOMOUS -> language.pick(
                    "Pi может искать в сети, выполнять shell-команды и менять доступные Termux файлы.",
                    "Pi may search the web, run shell commands, and change files visible to Termux."
            );
        };
    }

    /** Exact Pi 0.82.1 flags verified against its CLI parser. */
    public List<String> piArguments(String extensionPath) {
        return switch (this) {
            case READ_ONLY -> List.of(
                    "--tools", "read,code_nav,web_research,weather,"
                            + "pideck_load_tools"
            );
            case CONFIRM_CHANGES -> List.of(
                    "--no-builtin-tools",
                    "--tools", "read,code_nav,web_research,weather,"
                            + "pideck_bash,pideck_edit,pideck_write,pideck_replace_lines,"
                            + "pideck_load_tools",
                    "--extension", extensionPath
            );
            case AUTONOMOUS -> List.of(
                    "--tools", "read,bash,edit,write,code_nav,"
                            + "web_research,weather,pideck_replace_lines,run_tests,"
                            + "pideck_load_tools"
            );
        };
    }
}
