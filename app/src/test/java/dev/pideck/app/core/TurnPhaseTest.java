package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TurnPhaseTest {
    @Test
    public void namedPhasesReadTheSameInTheRowAndTheTicker() {
        assertEquals("Модель думает", TurnPhase.THINKING.label(UiLanguage.RUSSIAN, null));
        assertEquals("Model is thinking…", TurnPhase.THINKING.progressLabel(UiLanguage.ENGLISH, null));
        assertEquals("Preparing context", TurnPhase.PREPARING.progressLabel(UiLanguage.ENGLISH, null));
        assertEquals("Сжимаю историю…", TurnPhase.COMPACTING.progressLabel(UiLanguage.RUSSIAN, ""));
        assertEquals("Answer rejected", TurnPhase.REJECTED.progressLabel(UiLanguage.ENGLISH, null));
    }

    @Test
    public void subjectPhasesAreNamedByTheirDetail() {
        assertEquals("read src/app.py", TurnPhase.TOOL.label(UiLanguage.ENGLISH, "read src/app.py"));
        assertEquals("read src/app.py", TurnPhase.TOOL.progressLabel(UiLanguage.ENGLISH, "read src/app.py"));
        assertEquals("Повтор 1/2…", TurnPhase.RETRYING.progressLabel(UiLanguage.RUSSIAN, "Повтор 1/2"));
        assertEquals("", TurnPhase.TOOL.label(UiLanguage.RUSSIAN, null));
    }
}
