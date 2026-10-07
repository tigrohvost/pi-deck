package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NativeLlamaServiceLabelsTest {

    @Test
    public void backgroundSuffixIsHonestAndOnlyInBackground() {
        assertEquals("Локальная модель готова",
                NativeLlamaService.notificationText("Локальная модель готова", false));
        assertEquals("Локальная модель готова · фон: медленно",
                NativeLlamaService.notificationText("Локальная модель готова", true));
    }

    @Test
    public void backgroundSuffixFollowsTheDeckLanguage() {
        assertEquals("Local model ready · background: slow",
                NativeLlamaService.notificationText("Local model ready", true, UiLanguage.ENGLISH));
        assertEquals("Local model ready",
                NativeLlamaService.notificationText("Local model ready", false, UiLanguage.ENGLISH));
    }
}
