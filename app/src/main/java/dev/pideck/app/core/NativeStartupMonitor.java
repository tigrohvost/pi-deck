package dev.pideck.app.core;

import java.io.IOException;
import java.util.function.LongSupplier;

/** One cancellable startup, with no executor occupied between health probes. */
public final class NativeStartupMonitor {
    public interface Handle { void cancel(); }
    public interface Scheduler { Handle schedule(Runnable action, long delayMs); }
    public interface Driver {
        boolean current();
        String state();
        boolean health() throws IOException;
        void adopt();
        void failed(String message);
        void cancelIo();
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Driver driver;
    private final long deadline;
    private Handle pending;
    private boolean finished;
    private String lastError = "Native server is still loading";

    public NativeStartupMonitor(Scheduler scheduler, LongSupplier clock, Driver driver, long timeoutMs) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.driver = driver;
        deadline = clock.getAsLong() + timeoutMs;
    }

    public synchronized void start() { schedule(0L); }

    public void cancel() {
        synchronized (this) {
            if (finished) return;
            finished = true;
            if (pending != null) pending.cancel();
        }
        driver.cancelIo();
    }

    private synchronized void schedule(long delay) {
        if (!finished) pending = scheduler.schedule(this::probe, delay);
    }

    private synchronized boolean active() { return !finished && driver.current(); }

    private void probe() {
        if (!active()) { cancel(); return; }
        String state = driver.state();
        if ("STOPPED".equals(state) || "FAILED".equals(state) || "STOPPING".equals(state)) {
            fail("Native server stopped during startup");
            return;
        }
        if (clock.getAsLong() >= deadline) {
            fail("Native server startup timed out: " + lastError);
            return;
        }
        try {
            boolean healthy = driver.health();
            // Cancellation and generation changes during the HTTP call are authoritative.
            synchronized (this) {
                if (!active()) return;
                if (healthy) {
                    String after = driver.state();
                    if (!"STARTING".equals(after) && !"READY".equals(after)) {
                        fail("Native server stopped during health check");
                        return;
                    }
                    finished = true;
                    try { driver.adopt(); }
                    catch (RuntimeException error) {
                        if (driver.current()) driver.failed("Native server adoption failed: " + error.getMessage());
                    }
                    return;
                }
            }
        } catch (IOException | RuntimeException error) {
            lastError = error.getMessage();
        }
        if (active()) schedule(250L);
    }

    private synchronized void fail(String message) {
        if (!active()) return;
        finished = true;
        driver.failed(message);
    }
}
