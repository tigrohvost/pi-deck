package dev.pideck.app.core;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayDeque;
import org.junit.Test;

public class NativeStartupMonitorTest {
    private static final class Fixture implements NativeStartupMonitor.Driver, NativeStartupMonitor.Scheduler {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        long now;
        boolean current = true, healthy;
        String state = "STARTING";
        int adopted, failed, cancelled;
        Runnable duringHealth = () -> {};
        final NativeStartupMonitor monitor = new NativeStartupMonitor(this, () -> now, this, 240_000);
        @Override public NativeStartupMonitor.Handle schedule(Runnable run, long delay) {
            pending.add(run);
            return () -> pending.remove(run);
        }
        void tick() { pending.remove().run(); }
        @Override public boolean current() { return current; }
        @Override public String state() { return state; }
        @Override public boolean health() throws IOException { duringHealth.run(); return healthy; }
        @Override public void adopt() { adopted++; }
        @Override public void failed(String error) { failed++; }
        @Override public void cancelIo() { cancelled++; }
    }

    @Test public void neverReadyCanBeCancelledWithoutWaitingForStartupDeadline() {
        Fixture f = new Fixture(); f.monitor.start(); f.tick();
        assertEquals(1, f.pending.size());
        f.monitor.cancel(); f.monitor.cancel();
        assertTrue(f.pending.isEmpty()); assertEquals(0, f.now);
        assertEquals(1, f.cancelled); assertEquals(0, f.failed); assertEquals(0, f.adopted);
    }

    @Test public void cancellationDuringHealthCannotAdopt() {
        Fixture f = new Fixture(); f.healthy = true;
        f.duringHealth = f.monitor::cancel; f.monitor.start(); f.tick();
        assertEquals(0, f.adopted); assertTrue(f.pending.isEmpty());
    }

    @Test public void newGenerationCannotReceiveOldFailureOrAdoption() {
        Fixture f = new Fixture(); f.healthy = true;
        f.duringHealth = () -> { f.current = false; };
        f.monitor.start(); f.tick();
        assertEquals(0, f.adopted); assertEquals(0, f.failed); assertTrue(f.pending.isEmpty());
    }

    @Test public void stoppedAndDeadlineAreTerminalExactlyOnce() {
        for (boolean stopped : new boolean[]{true, false}) {
            Fixture f = new Fixture(); f.monitor.start();
            if (stopped) f.state = "STOPPED"; else f.now = 240_000;
            f.tick(); f.monitor.cancel();
            assertEquals(1, f.failed); assertEquals(0, f.adopted); assertTrue(f.pending.isEmpty());
        }
    }

    @Test public void healthyGenerationIsAdoptedExactlyOnce() {
        Fixture f = new Fixture(); f.healthy = true; f.monitor.start(); f.tick();
        f.monitor.start();
        assertEquals(1, f.adopted); assertTrue(f.pending.isEmpty());
    }
}
