package dev.pideck.app.core;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class NativeStopMonitorTest {
    @Test public void timeoutAndGenerationChangeNeverReportStopped() {
        for (boolean changed : new boolean[]{false, true}) {
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            long[] now = {0};
            List<NativeStopMonitor.Result> results = new ArrayList<>();
            NativeStopMonitor monitor = new NativeStopMonitor((run, delay) -> {
                queue.add(run); return () -> queue.remove(run);
            }, () -> now[0], () -> new NativeStopMonitor.Observation(!changed, true, "STOPPING"), results::add, 5000);
            monitor.start(); queue.remove().run();
            if (!changed) { assertTrue(results.isEmpty()); now[0] = 5000; queue.remove().run(); }
            assertEquals(List.of(NativeStopMonitor.Result.FAILED), results);
            assertTrue(queue.isEmpty());
        }
    }

    @Test public void confirmedStopCompletesExactlyOnceWithoutWaitingForStartup() {
        List<NativeStopMonitor.Result> results = new ArrayList<>();
        NativeStopMonitor monitor = new NativeStopMonitor((run, delay) -> {
            run.run(); return () -> {};
        }, () -> 0, () -> new NativeStopMonitor.Observation(true, false, "STOPPED"), results::add, 5000);
        monitor.start(); monitor.start();
        assertEquals(List.of(NativeStopMonitor.Result.STOPPED), results);
    }
}
