package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SessionContextUsageTest {
    @Test
    public void calculatesWarningAndCompactionThresholds() throws Exception {
        SessionContextUsage usage = SessionContextUsage.parse(
                new JSONObject()
                        .put("tokens", 7_680)
                        .put("contextWindow", 10_240),
                4_096
        );

        assertTrue(usage.known());
        assertEquals(75, usage.percent);
        assertTrue(usage.shouldWarn());
        assertTrue(usage.shouldCompactSoon());
    }

    @Test
    public void promptChoiceStartsAtExactSixtyPercent() throws Exception {
        SessionContextUsage below = SessionContextUsage.parse(
                new JSONObject()
                        .put("tokens", 6_143)
                        .put("contextWindow", 10_240),
                10_240
        );
        SessionContextUsage atThreshold = SessionContextUsage.parse(
                new JSONObject()
                        .put("tokens", 6_144)
                        .put("contextWindow", 10_240),
                10_240
        );

        // Rounded display telemetry may already say 60%; the decision uses exact token capacity.
        assertEquals(60, below.percent);
        assertFalse(below.shouldCompactSoon());
        assertTrue(atThreshold.shouldCompactSoon());
    }

    @Test
    public void missingTelemetryRemainsUnknown() {
        SessionContextUsage usage = SessionContextUsage.parse(null, 8_192);

        assertFalse(usage.known());
        assertEquals(8_192, usage.contextWindow);
        assertFalse(usage.shouldCompactSoon());
    }
}
