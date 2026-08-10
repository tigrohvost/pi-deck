package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StallWatchdogTest {
    @Test
    public void progressExtendsStallDeadlineButNotOverallCap() {
        OperationId id = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L);
        long stall = OperationKind.AGENT_TURN.stallTimeoutMs();
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(stall - 1));
        assertTrue(watchdog.progress(id, stall - 1));
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(stall + stall - 2));
        assertEquals(StallWatchdog.Verdict.STALLED, watchdog.verdict(stall - 1 + stall));
        long overall = OperationKind.AGENT_TURN.timeoutMs();
        for (long now = 0; now < overall; now += stall / 2) {
            watchdog.progress(id, now);
        }
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(overall));
    }

    @Test
    public void foreignOperationEventsDoNotFeedTheWatchdog() {
        OperationId mine = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(mine, OperationKind.AGENT_TURN, 0L);
        assertFalse(watchdog.progress(OperationId.create(), 1_000L));
        assertEquals(
                StallWatchdog.Verdict.STALLED,
                watchdog.verdict(OperationKind.AGENT_TURN.stallTimeoutMs())
        );
    }

    @Test
    public void restoredOperationKeepsOverallDeadlineWithFreshStallWindow() {
        OperationId id = OperationId.create();
        long now = OperationKind.AGENT_TURN.timeoutMs() - 60_000L;
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L, now);
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(now + 30_000L));
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(now + 60_000L));
    }

    @Test
    public void nextCheckDelayTracksTheNearestDeadlineWithAFloor() {
        OperationId id = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L);
        assertEquals(
                OperationKind.AGENT_TURN.stallTimeoutMs(),
                watchdog.nextCheckDelayMs(0L)
        );
        assertEquals(
                1L,
                watchdog.nextCheckDelayMs(OperationKind.AGENT_TURN.stallTimeoutMs() + 5L)
        );
        assertEquals(0L, watchdog.silentForMs(0L));
        assertEquals(7L, watchdog.silentForMs(7L));
    }

    @Test
    public void stallWindowsCoverMeasuredSilentPrefill() {
        // docs/performance.md: 173 s of silent prompt replay is legitimate.
        // The stall window must not fire during it.
        assertEquals(480_000L, OperationKind.AGENT_TURN.stallTimeoutMs());
        assertEquals(480_000L, OperationKind.COMPACT_SESSION.stallTimeoutMs());
        for (OperationKind kind : OperationKind.values()) {
            assertTrue(kind.stallTimeoutMs() <= kind.timeoutMs());
            assertTrue(kind.stallTimeoutMs() > 0L);
        }
    }

    @Test
    public void restoredPastOverallDeadlineIsExpiredImmediately() {
        OperationId id = OperationId.create();
        long overall = OperationKind.AGENT_TURN.timeoutMs();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L, overall + 5L);
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(overall + 5L));
        assertEquals(1L, watchdog.nextCheckDelayMs(overall + 5L));
    }

    @Test
    public void expiredWinsWhenBothDeadlinesHavePassed() {
        OperationId id = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L);
        long past = OperationKind.AGENT_TURN.timeoutMs()
                + OperationKind.AGENT_TURN.stallTimeoutMs();
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(past));
    }
}
