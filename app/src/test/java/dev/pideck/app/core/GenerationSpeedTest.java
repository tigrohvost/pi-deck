package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Locale;

public class GenerationSpeedTest {
    @Test
    public void estimatesLiveRateFromCharactersAndMarksIt() {
        GenerationSpeed speed = GenerationSpeed.fromStreaming(380L, 5_000L);

        assertEquals(20.0d, speed.tokensPerSecond, 0.001d);
        assertTrue(speed.estimated);
        assertEquals("≈20.0 ток/с", speed.label(Locale.US));
    }

    @Test
    public void parsesExactTerminalProviderUsage() throws Exception {
        GenerationSpeed speed = GenerationSpeed.fromTerminal(
                new JSONObject()
                        .put("tokensPerSecond", 18.75d)
                        .put("outputTokens", 75L)
                        .put("speedEstimated", false)
        );

        assertFalse(speed.estimated);
        assertEquals(75L, speed.outputTokens);
        assertEquals("18.8 ток/с", speed.label(Locale.US));
        assertEquals(
                "18.8 tok/s",
                speed.label(Locale.US, UiLanguage.ENGLISH)
        );
        assertEquals(
                "Final speed 18.8 tok/s, 75 output tokens",
                speed.contentDescription(Locale.US, UiLanguage.ENGLISH)
        );
    }

    @Test
    public void rejectsMissingOrInvalidMetrics() {
        assertNull(GenerationSpeed.fromStreaming(0L, 1_000L));
        assertNull(GenerationSpeed.fromTerminal(new JSONObject()));
    }
}
