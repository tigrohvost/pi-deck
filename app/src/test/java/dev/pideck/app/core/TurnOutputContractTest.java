package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class TurnOutputContractTest {
    @Test
    public void terminalAnswerReplacesAOneLetterSpeculativeStream() {
        assertEquals(
                "Ответ восстановлен из полного terminal event.",
                TurnOutputContract.reconcileTerminal(
                        "О",
                        "Ответ восстановлен из полного terminal event.",
                        "Нет текстового ответа."
                )
        );
    }

    @Test
    public void emptyTerminalDoesNotPromoteSpeculativeText() {
        assertEquals(
                "Нет текстового ответа.",
                TurnOutputContract.reconcileTerminal(
                        "частичный ответ",
                        "",
                        "Нет текстового ответа."
                )
        );
    }

    @Test
    public void durableSnapshotExcludesOnlyTheSpeculativeRow() {
        ArrayList<String> live = new ArrayList<>(List.of("user", "О", "tool trace"));

        List<String> durable = TurnOutputContract.durableSnapshot(live, 1);

        assertEquals(List.of("user", "tool trace"), durable);
        assertEquals(List.of("user", "О", "tool trace"), live);
    }

    @Test
    public void completedSnapshotPreservesEveryRow() {
        assertEquals(
                List.of("user", "answer"),
                TurnOutputContract.durableSnapshot(List.of("user", "answer"), -1)
        );
    }
}
