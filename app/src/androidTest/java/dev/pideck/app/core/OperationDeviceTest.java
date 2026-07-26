package dev.pideck.app.core;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * On-device persistence/race smoke tests with no emulator-only assumptions.
 *
 * The wider physical-device matrix remains a release gate; this suite gives adb a deterministic
 * check for the durable operation core before exercising Termux and a real GGUF.
 */
@RunWith(AndroidJUnit4.class)
public final class OperationDeviceTest {
    private Path directory;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory(
                InstrumentationRegistry.getInstrumentation()
                        .getTargetContext()
                        .getCacheDir()
                        .toPath(),
                "operation-device-"
        );
    }

    @After
    public void tearDown() throws Exception {
        if (directory != null) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void testProcessRecreationKeepsExactActiveTurnAcrossAbortControl() {
        OperationStore store = new OperationStore(directory.toFile());
        OperationCoordinator first = new OperationCoordinator(store);
        OperationRecord turn = first.begin(OperationKind.AGENT_TURN, new JSONObject());
        first.dispatched(turn.operationId);
        first.requestAbort(turn.operationId);
        OperationRecord abort = first.beginControl(
                OperationKind.ABORT_AGENT,
                new JSONObject()
        );
        first.dispatched(abort.operationId);

        OperationCoordinator recreated = new OperationCoordinator(
                new OperationStore(directory.toFile())
        );
        assertEquals(turn.operationId, recreated.activeOperationId());
        assertEquals(OperationState.ABORT_REQUESTED, recreated.active().state);
    }

    @Test
    public void testLateResultCannotCompleteNewerOperation() {
        OperationStore store = new OperationStore(directory.toFile());
        OperationCoordinator coordinator = new OperationCoordinator(store);
        OperationRecord first = coordinator.begin(
                OperationKind.PROBE_RUNTIME,
                new JSONObject()
        );
        coordinator.dispatched(first.operationId);
        CommandResult firstResult = new CommandResult(
                first.operationId,
                first.kind,
                "{}",
                "",
                0,
                0,
                ""
        );
        assertTrue(coordinator.onResult(firstResult));

        OperationRecord second = coordinator.begin(
                OperationKind.AGENT_TURN,
                new JSONObject()
        );
        coordinator.dispatched(second.operationId);
        assertFalse(coordinator.onResult(firstResult));
        assertEquals(second.operationId, coordinator.activeOperationId());
        assertEquals(OperationState.RUNNING, store.load(second.operationId).state);
    }
}
