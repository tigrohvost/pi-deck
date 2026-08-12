package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleShutdownTest {

    @Test
    public void neverDisablesTheTimer() {
        assertFalse(IdleShutdown.enabled(IdleShutdown.NEVER));
        assertTrue(IdleShutdown.enabled(IdleShutdown.DEFAULT_MINUTES));
    }

    @Test
    public void delayIsMinutesInMilliseconds() {
        assertEquals(600_000L, IdleShutdown.delayMs(10L));
        assertEquals(300_000L, IdleShutdown.delayMs(5L));
    }

    @Test
    public void onlyOfferedValuesAreNormalized() {
        assertTrue(IdleShutdown.normalized(0L));
        assertTrue(IdleShutdown.normalized(5L));
        assertTrue(IdleShutdown.normalized(10L));
        assertTrue(IdleShutdown.normalized(30L));
        assertFalse(IdleShutdown.normalized(7L));
        assertFalse(IdleShutdown.normalized(-1L));
    }
}
