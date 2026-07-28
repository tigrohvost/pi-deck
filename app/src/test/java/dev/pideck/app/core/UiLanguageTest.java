package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UiLanguageTest {
    @Test
    public void unknownValuesFailClosedToRussianAndEnglishPicksEnglish() {
        assertEquals(UiLanguage.RUSSIAN, UiLanguage.fromWireName(null));
        assertEquals(UiLanguage.RUSSIAN, UiLanguage.fromWireName("future"));
        assertEquals(UiLanguage.ENGLISH, UiLanguage.fromWireName("EN"));
        assertEquals("Settings", UiLanguage.ENGLISH.pick("Настройки", "Settings"));
        assertEquals("Настройки", UiLanguage.RUSSIAN.pick("Настройки", "Settings"));
    }
}
