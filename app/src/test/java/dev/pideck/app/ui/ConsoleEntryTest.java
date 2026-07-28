package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ConsoleEntryTest {
    @Test
    public void completedAgentAnswerKeepsExactSpeed() {
        ConsoleEntry entry = new ConsoleEntry(
                ConsoleEntry.Channel.AGENT,
                "done",
                123L,
                "",
                "",
                18.75d,
                42L
        );

        assertTrue(entry.hasExactSpeed());
        assertEquals(18.75d, entry.tokensPerSecond, 0.0001d);
        assertEquals(42L, entry.outputTokens);
    }

    @Test
    public void nonAgentAndInvalidMetricsAreRejected() {
        ConsoleEntry system = new ConsoleEntry(
                ConsoleEntry.Channel.SYSTEM,
                "notice",
                123L,
                "",
                "",
                18.75d,
                42L
        );
        ConsoleEntry invalid = new ConsoleEntry(
                ConsoleEntry.Channel.AGENT,
                "done",
                123L,
                "",
                "",
                Double.NaN,
                42L
        );

        assertFalse(system.hasExactSpeed());
        assertFalse(invalid.hasExactSpeed());
    }
}
