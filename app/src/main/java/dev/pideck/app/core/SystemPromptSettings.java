package dev.pideck.app.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Validated, privacy-preserving settings for Pi's optional custom system prompt. */
public final class SystemPromptSettings {
    public static final int MAX_BYTES = 16 * 1024;
    public static final String DEFAULT_WIRE_MODE = "default";

    public enum Mode {
        APPEND("append"),
        REPLACE("replace");

        private final String wireName;

        Mode(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Mode fromWireName(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.wireName.equals(value.toLowerCase(Locale.ROOT))) return mode;
                }
            }
            return APPEND;
        }
    }

    private SystemPromptSettings() {
    }

    /**
     * Normalizes platform newlines without otherwise rewriting the user's instructions.
     *
     * <p>A whitespace-only value means “use Pi's built-in prompt”. NUL is rejected because the
     * runtime persists the value as a UTF-8 text file consumed by the Pi CLI.
     */
    public static String normalize(String value) {
        String normalized = value == null
                ? ""
                : value.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Системный промпт содержит недопустимый NUL");
        }
        if (normalized.isBlank()) return "";
        int bytes = byteCount(normalized);
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Системный промпт занимает " + bytes + " байт; максимум " + MAX_BYTES
            );
        }
        return normalized;
    }

    public static int byteCount(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String effectiveWireMode(Mode mode, String normalizedPrompt) {
        return normalizedPrompt == null || normalizedPrompt.isEmpty()
                ? DEFAULT_WIRE_MODE
                : (mode == null ? Mode.APPEND : mode).wireName();
    }

    /** Hash sent through status/metadata so Android can detect stale bridge settings without text. */
    public static String sha256Hex(String normalizedPrompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (normalizedPrompt == null ? "" : normalizedPrompt)
                            .getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Android runtime has no SHA-256", impossible);
        }
    }
}
