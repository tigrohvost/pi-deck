package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TurnClockTest {
    @Test
    public void elapsedTimeRunsUntilTheFirstOutput() {
        TurnClock clock = new TurnClock();
        assertFalse(clock.running());
        clock.begin(10_000L);
        assertTrue(clock.awaitingFirstOutput());
        assertEquals(65L, clock.elapsedSeconds(75_000L));
        assertTrue(clock.output(80_000L, 5));
        assertFalse("only the first output starts the rate", clock.output(80_100L, 5));
        assertFalse(clock.awaitingFirstOutput());
        clock.end();
        assertFalse(clock.running());
        assertEquals(0L, clock.elapsedSeconds(90_000L));
    }

    @Test
    public void theLiveRateIsThrottledAndRestartsAfterATool() {
        TurnClock clock = new TurnClock();
        clock.begin(500L);
        clock.output(1_000L, 40);
        assertFalse("too soon after the first token", clock.rateDue(1_100L));
        assertTrue(clock.rateDue(1_300L));
        assertFalse("redrawn at most every 750 ms", clock.rateDue(1_600L));
        clock.output(1_900L, 400);
        assertTrue(clock.rateDue(2_100L));
        assertNotNull(clock.speed(2_100L));
        clock.restartOutput(3_000L);
        assertTrue(clock.awaitingFirstOutput());
        assertFalse(clock.rateDue(3_500L));
    }

    @Test
    public void elapsedIsFormattedAsMinutesAndSeconds() {
        assertEquals("01:05", TurnClock.formatElapsed(65L));
        assertEquals("99:59", TurnClock.formatElapsed(99L * 60L + 59L + 3_600L * 5L));
    }
}
