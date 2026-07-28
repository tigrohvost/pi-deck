package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class SystemPromptSettingsTest {
    @Test
    public void defaultsToAppendAndEmptyMeansBuiltInPrompt() {
        assertEquals(
                SystemPromptSettings.Mode.APPEND,
                SystemPromptSettings.Mode.fromWireName(null)
        );
        assertEquals("", SystemPromptSettings.normalize(" \r\n\t"));
        assertEquals(
                SystemPromptSettings.DEFAULT_WIRE_MODE,
                SystemPromptSettings.effectiveWireMode(
                        SystemPromptSettings.Mode.REPLACE,
                        ""
                )
        );
    }

    @Test
    public void normalizesNewlinesButPreservesMeaningfulWhitespace() {
        assertEquals(
                "  первая\nвторая  ",
                SystemPromptSettings.normalize("  первая\r\nвторая  ")
        );
    }

    @Test
    public void limitIsMeasuredInUtf8Bytes() {
        String accepted = "я".repeat(SystemPromptSettings.MAX_BYTES / 2);
        assertEquals(SystemPromptSettings.MAX_BYTES, SystemPromptSettings.byteCount(accepted));
        assertEquals(accepted, SystemPromptSettings.normalize(accepted));
        try {
            SystemPromptSettings.normalize(accepted + "я");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Oversized UTF-8 prompt was accepted");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nulIsRejected() {
        SystemPromptSettings.normalize("до\0после");
    }

    @Test
    public void statusHashContainsNoPromptAndChangesWithContent() {
        String first = SystemPromptSettings.sha256Hex("секрет");
        String second = SystemPromptSettings.sha256Hex("другой");
        assertEquals(64, first.length());
        assertNotEquals(first, second);
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855",
                SystemPromptSettings.sha256Hex("")
        );
    }
}
