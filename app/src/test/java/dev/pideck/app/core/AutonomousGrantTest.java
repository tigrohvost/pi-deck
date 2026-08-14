package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutonomousGrantTest {
    @Test
    public void grantIsBoundedToThirtyMinutes() {
        long now = 1_000_000L;
        long expiry = AutonomousGrant.newExpiry(now);
        assertEquals(now + 30L * 60L * 1000L, expiry);
        assertTrue(AutonomousGrant.isActive(expiry, now));
        assertFalse(AutonomousGrant.isActive(expiry, expiry));
    }

    @Test
    public void forgedLongGrantFailsClosed() {
        long now = 1_000_000L;
        assertFalse(AutonomousGrant.isActive(now + AutonomousGrant.DURATION_MS + 1L, now));
    }

    @Test
    public void remainingMinutesRoundsUpForHonestDisplay() {
        assertEquals(1L, AutonomousGrant.remainingMinutes(60_001L, 1L));
        assertEquals(0L, AutonomousGrant.remainingMinutes(1L, 1L));
    }
}
