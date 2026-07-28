package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class OperationCoreTest {
    private Path temporary;
    private OperationStore store;
    private OperationCoordinator coordinator;

    @Before
    public void setUp() throws Exception {
        temporary = Files.createTempDirectory("pideck-operation-test-");
        store = new OperationStore(temporary.toFile());
        coordinator = new OperationCoordinator(store);
    }

    @After
    public void tearDown() throws Exception {
        if (temporary == null) return;
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void operationIdRoundTripsOnlyAsCanonicalUuid4() {
        OperationId created = OperationId.create();
        assertEquals(created, OperationId.parse(created.toString()));
        assertEquals(36, created.toString().length());
        assertThrows(
                IllegalArgumentException.class,
                () -> OperationId.parse(created.toString().toUpperCase())
        );
        assertThrows(IllegalArgumentException.class, () -> OperationId.parse("start-" + created));
        assertNotEquals(OperationId.create(), created);
    }

    @Test
    public void stateMachineRejectsInvalidTerminalAndSkipTransitions() {
        assertTrue(OperationStateMachine.canTransition(
                OperationState.CREATED, OperationState.DISPATCHED
        ));
        assertTrue(OperationStateMachine.canTransition(
                OperationState.UNKNOWN, OperationState.RUNNING
        ));
        assertFalse(OperationStateMachine.canTransition(
                OperationState.CREATED, OperationState.COMPLETED
        ));
        assertFalse(OperationStateMachine.canTransition(
                OperationState.COMPLETED, OperationState.RUNNING
        ));
        assertThrows(
                IllegalStateException.class,
                () -> OperationStateMachine.requireTransition(
                        OperationState.FAILED, OperationState.COMPLETED
                )
        );
    }

    @Test
    public void lateResultAndOldWatchdogCannotAffectNewOperation() {
        OperationRecord first = coordinator.begin(OperationKind.PROBE_RUNTIME, new JSONObject());
        coordinator.dispatched(first.operationId);
        CommandResult firstResult = success(first);
        assertTrue(coordinator.onResult(firstResult));

        OperationRecord second = coordinator.begin(OperationKind.START_SERVER, new JSONObject());
        coordinator.dispatched(second.operationId);
        coordinator.timeout(first.operationId);
        assertEquals(second.operationId, coordinator.activeOperationId());
        assertFalse(coordinator.onResult(firstResult));
        assertEquals(second.operationId, coordinator.activeOperationId());
        assertEquals(OperationState.RUNNING, store.load(second.operationId).state);
    }

    @Test
    public void unknownRequiresReconcileBeforeNextMutatingOperation() {
        OperationRecord first = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        coordinator.dispatched(first.operationId);
        coordinator.timeout(first.operationId);
        assertEquals(OperationState.UNKNOWN, store.load(first.operationId).state);
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.begin(OperationKind.AGENT_TURN, new JSONObject())
        );
        coordinator.reconcileTerminalMissing(first.operationId, "terminal event unavailable");
        assertEquals(OperationState.FAILED, store.load(first.operationId).state);
        OperationRecord second = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        assertEquals(second.operationId, coordinator.activeOperationId());
    }

    @Test
    public void processRestartRestoresTargetTurnInsteadOfAbortControl() {
        OperationRecord turn = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        coordinator.dispatched(turn.operationId);
        coordinator.requestAbort(turn.operationId);
        OperationRecord control = coordinator.beginControl(
                OperationKind.ABORT_AGENT,
                new JSONObject()
        );
        coordinator.dispatched(control.operationId);

        OperationCoordinator restored = new OperationCoordinator(store);
        assertEquals(turn.operationId, restored.activeOperationId());
        assertEquals(OperationState.ABORT_REQUESTED, restored.active().state);
    }

    @Test
    public void processRestartClosesRecordThatNeverReachedExternalDispatch() {
        OperationRecord created = store.create(
                OperationKind.START_SERVER,
                new JSONObject()
        );

        OperationCoordinator restored = new OperationCoordinator(store);

        assertNull(restored.active());
        assertEquals(OperationState.FAILED, store.load(created.operationId).state);
        assertEquals(
                "Application stopped before external dispatch",
                store.load(created.operationId).error
        );
    }

    @Test
    public void packageUpdateClosesOnlyAnOlderRuntimeInstall() {
        OperationRecord install = coordinator.begin(
                OperationKind.INSTALL_RUNTIME,
                new JSONObject()
        );
        coordinator.dispatched(install.operationId);

        assertTrue(coordinator.failRuntimeInstallStartedBefore(install.createdAtMs + 1));
        assertNull(coordinator.active());
        assertEquals(OperationState.FAILED, store.load(install.operationId).state);

        OperationRecord turn = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        coordinator.dispatched(turn.operationId);
        assertFalse(coordinator.failRuntimeInstallStartedBefore(turn.createdAtMs + 1));
        assertEquals(turn.operationId, coordinator.activeOperationId());
    }

    @Test
    public void abortRequiresAnExplicitConfirmedTerminalState() {
        OperationRecord turn = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        coordinator.dispatched(turn.operationId);
        coordinator.requestAbort(turn.operationId);
        coordinator.onResult(new CommandResult(
                turn.operationId,
                turn.kind,
                "",
                "",
                1,
                1,
                "transport failed"
        ));
        assertEquals(OperationState.FAILED, store.load(turn.operationId).state);

        OperationRecord second = coordinator.begin(OperationKind.AGENT_TURN, new JSONObject());
        coordinator.dispatched(second.operationId);
        coordinator.requestAbort(second.operationId);
        coordinator.onResult(new CommandResult(
                second.operationId,
                second.kind,
                "",
                "",
                1,
                1,
                "confirmed abort",
                OperationState.ABORTED
        ));
        assertEquals(OperationState.ABORTED, store.load(second.operationId).state);
    }

    @Test
    public void damagedRecordIsIsolatedAndResultIdentityCannotCrossKinds() throws Exception {
        OperationRecord healthy = coordinator.begin(OperationKind.PROBE_RUNTIME, new JSONObject());
        coordinator.dispatched(healthy.operationId);
        Files.write(
                temporary.resolve(OperationId.create() + ".json"),
                "{broken".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(1, store.list().size());
        CommandResult wrongKind = new CommandResult(
                healthy.operationId,
                OperationKind.START_SERVER,
                "",
                "",
                0,
                0,
                ""
        );
        assertThrows(IllegalArgumentException.class, () -> store.recordResult(wrongKind));
        assertEquals(OperationState.RUNNING, store.load(healthy.operationId).state);
    }

    @Test
    public void recordSerializationIsBoundedAndUnconsumedUntilAcknowledged() {
        OperationRecord record = coordinator.begin(OperationKind.PROBE_RUNTIME, new JSONObject());
        coordinator.dispatched(record.operationId);
        String large = "x".repeat(OperationStore.MAX_OUTPUT_BYTES * 2);
        coordinator.onResult(new CommandResult(
                record.operationId,
                record.kind,
                large,
                "",
                0,
                0,
                ""
        ));
        OperationRecord loaded = store.load(record.operationId);
        assertTrue(loaded.result.stdout.endsWith("[output truncated]"));
        assertEquals(1, store.unconsumedTerminal().size());
        coordinator.markConsumed(record.operationId);
        assertTrue(store.unconsumedTerminal().isEmpty());
        assertNull(coordinator.active());
    }

    private static CommandResult success(OperationRecord record) {
        return new CommandResult(
                record.operationId, record.kind, "{}", "", 0, 0, ""
        );
    }
}
