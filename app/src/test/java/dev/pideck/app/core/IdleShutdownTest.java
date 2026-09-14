package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleShutdownTest {

    @Test
    public void neverDisablesTheTimer() {
        assertFalse(IdleShutdown.enabled(IdleShutdown.NEVER));
        assertTrue(IdleShutdown.enabled(IdleShutdown.DEFAULT_TIMEOUT));
    }

    @Test
    public void delayIsMinutesInMilliseconds() {
        assertEquals(600_000L, IdleShutdown.delayMs(10L));
        assertEquals(300_000L, IdleShutdown.delayMs(5L));
    }

    @Test
    public void onlyOfferedValuesAreNormalized() {
        assertTrue(IdleShutdown.normalized(IdleShutdown.ADAPTIVE));
        assertTrue(IdleShutdown.normalized(0L));
        assertTrue(IdleShutdown.normalized(5L));
        assertTrue(IdleShutdown.normalized(10L));
        assertTrue(IdleShutdown.normalized(30L));
        assertFalse(IdleShutdown.normalized(7L));
        assertFalse(IdleShutdown.normalized(-2L));
    }

    @Test
    public void adaptiveKeepsLargeReusableContextsLonger() {
        assertEquals(10L, IdleShutdown.effectiveMinutes(IdleShutdown.ADAPTIVE, -1L, 10_240));
        assertEquals(10L, IdleShutdown.effectiveMinutes(IdleShutdown.ADAPTIVE, 4_095L, 10_240));
        assertEquals(30L, IdleShutdown.effectiveMinutes(IdleShutdown.ADAPTIVE, 4_096L, 10_240));
        assertEquals(30L, IdleShutdown.effectiveMinutes(30L, 0L, 10_240));
        assertEquals(0L, IdleShutdown.effectiveMinutes(IdleShutdown.NEVER, 9_000L, 10_240));
    }
}
