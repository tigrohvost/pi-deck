package dev.pideck.app.core;

import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Stop completion has its own short deadline and never shares the startup probe queue. */
public final class NativeStopMonitor {
    public enum Result { STOPPED, FAILED }
    public record Observation(boolean sameGeneration, boolean processAlive, String state) {}
    private final NativeStartupMonitor.Scheduler scheduler;
    private final LongSupplier clock;
    private final Supplier<Observation> observe;
    private final Consumer<Result> complete;
    private final long deadline;
    private boolean started, finished;

    public NativeStopMonitor(NativeStartupMonitor.Scheduler scheduler, LongSupplier clock,
            Supplier<Observation> observe, Consumer<Result> complete, long timeoutMs) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.observe = observe;
        this.complete = complete;
        deadline = clock.getAsLong() + timeoutMs;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        scheduler.schedule(this::poll, 0L);
    }

    private synchronized void poll() {
        if (finished) return;
        Observation value = observe.get();
        Result result = !value.sameGeneration() ? Result.FAILED
                : !value.processAlive() && "STOPPED".equals(value.state()) ? Result.STOPPED
                : "FAILED".equals(value.state()) || clock.getAsLong() >= deadline ? Result.FAILED : null;
        if (result == null) scheduler.schedule(this::poll, 100L);
        else {
            finished = true;
            complete.accept(result);
        }
    }
}
