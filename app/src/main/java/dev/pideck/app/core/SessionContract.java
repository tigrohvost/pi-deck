package dev.pideck.app.core;

/**
 * Idempotency rules for the Android view of the authoritative Pi RPC session.
 *
 * <p>Bridge events are journaled and may be delivered again after a cursor reconnect. A consumed
 * SESSION_CREATED event must never overwrite a newer session, while one unconsumed terminal event
 * still has to repair state after process recreation.
 */
public final class SessionContract {
    private SessionContract() {
    }

    public static boolean mayApplyRecoveredSessionResult(
            boolean uiConsumed,
            OperationId activeOperationId
    ) {
        return !uiConsumed && activeOperationId == null;
    }

    /** State may declare a terminal missing only after Android consumed that state snapshot. */
    public static boolean mayDeclareTerminalEventMissing(
            long bridgeLastSequence,
            long consumedSequence
    ) {
        return bridgeLastSequence >= 0L && consumedSequence >= bridgeLastSequence;
    }

    /**
     * Returns a valid differing remote session to apply, otherwise {@code null}.
     *
     * <p>During NEW_SESSION the terminal SESSION_CREATED event owns the transition. Outside that
     * window the bridge state is authoritative and heals a lost/duplicated event without replaying
     * a prompt.
     */
    public static String authoritativeRemoteSession(
            String localSessionId,
            String remoteSessionId,
            boolean sessionTransitionPending
    ) {
        if (sessionTransitionPending
                || remoteSessionId == null
                || remoteSessionId.equals(localSessionId)) {
            return null;
        }
        try {
            SessionId.parse(remoteSessionId);
            return remoteSessionId;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
