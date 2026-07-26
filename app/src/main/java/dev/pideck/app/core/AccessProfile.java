package dev.pideck.app.core;

import java.util.List;
import java.util.Locale;

/** Agent capabilities are selected independently from model and session state. */
public enum AccessProfile {
    READ_ONLY(
            "READ ONLY",
            "Только чтение: read, grep, find и ls. Shell и запись отключены.",
            false
    ),
    CONFIRM_CHANGES(
            "CONFIRM CHANGES",
            "Каждая bash/edit/write операция требует одноразового подтверждения Android.",
            true
    ),
    AUTONOMOUS(
            "AUTONOMOUS",
            "Pi может выполнять shell-команды и менять любые файлы, доступные Termux.",
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

    public static AccessProfile fromWireName(String value) {
        if (value == null || value.isBlank()) return READ_ONLY;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return READ_ONLY;
        }
    }

    /** Exact Pi 0.82.1 flags verified against its CLI parser. */
    public List<String> piArguments(String extensionPath) {
        return switch (this) {
            case READ_ONLY -> List.of(
                    "--tools", "read,grep,find,ls"
            );
            case CONFIRM_CHANGES -> List.of(
                    "--no-builtin-tools",
                    "--tools", "read,grep,find,ls,pideck_bash,pideck_edit,pideck_write",
                    "--extension", extensionPath
            );
            case AUTONOMOUS -> List.of(
                    "--tools", "read,bash,edit,write,grep,find,ls"
            );
        };
    }
}
