package dev.pideck.app.core;

import java.util.EnumSet;
import java.util.Map;

public final class OperationStateMachine {
    private static final Map<OperationState, EnumSet<OperationState>> ALLOWED = Map.of(
            OperationState.CREATED, EnumSet.of(OperationState.DISPATCHED, OperationState.FAILED),
            OperationState.DISPATCHED, EnumSet.of(
                    OperationState.RUNNING,
                    OperationState.UNKNOWN,
                    OperationState.COMPLETED,
                    OperationState.FAILED
            ),
            OperationState.RUNNING, EnumSet.of(
                    OperationState.ABORT_REQUESTED,
                    OperationState.UNKNOWN,
                    OperationState.COMPLETED,
                    OperationState.FAILED,
                    OperationState.ABORTED
            ),
            OperationState.ABORT_REQUESTED, EnumSet.of(
                    OperationState.UNKNOWN,
                    OperationState.ABORTED,
                    OperationState.FAILED,
                    OperationState.COMPLETED
            ),
            OperationState.UNKNOWN, EnumSet.of(
                    OperationState.RUNNING,
                    OperationState.COMPLETED,
                    OperationState.FAILED,
                    OperationState.ABORTED
            ),
            OperationState.COMPLETED, EnumSet.noneOf(OperationState.class),
            OperationState.FAILED, EnumSet.noneOf(OperationState.class),
            OperationState.ABORTED, EnumSet.noneOf(OperationState.class)
    );

    private OperationStateMachine() {
    }

    public static boolean canTransition(OperationState from, OperationState to) {
        return from == to || ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(OperationState from, OperationState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid operation transition: " + from + " -> " + to);
        }
    }
}
