package dev.pideck.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class SessionContractTest {
    @Test
    public void terminalMissingRequiresJournalCatchUpToStateSnapshot() {
        assertFalse(SessionContract.mayDeclareTerminalEventMissing(12L, 11L));
        assertFalse(SessionContract.mayDeclareTerminalEventMissing(-1L, 99L));
        assertTrue(SessionContract.mayDeclareTerminalEventMissing(12L, 12L));
        assertTrue(SessionContract.mayDeclareTerminalEventMissing(12L, 13L));
    }

    @Test
    public void consumedSessionEventCannotOverwriteNewerOperation() {
        assertFalse(SessionContract.mayApplyRecoveredSessionResult(
                true,
                OperationId.create()
        ));
    }

    @Test
    public void unconsumedTerminalSessionCanRecoverOnlyWhileIdle() {
        assertFalse(SessionContract.mayApplyRecoveredSessionResult(
                false,
                OperationId.create()
        ));
        assertTrue(SessionContract.mayApplyRecoveredSessionResult(false, null));
    }

    @Test
    public void bridgeStateHealsMismatchOutsideSessionTransition() {
        String remote = "01890f76-e8b2-7cc2-98c8-8c4a7ef8d123";
        assertEquals(
                remote,
                SessionContract.authoritativeRemoteSession(
                        OperationId.create().toString(),
                        remote,
                        false
                )
        );
        assertNull(SessionContract.authoritativeRemoteSession(null, remote, true));
        assertNull(SessionContract.authoritativeRemoteSession(null, "not-a-session", false));
    }
}
