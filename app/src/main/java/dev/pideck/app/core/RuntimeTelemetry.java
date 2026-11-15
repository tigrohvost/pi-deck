package dev.pideck.app.core;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Application-owned bounded history; the native foreground service keeps it alive in background. */
public final class RuntimeTelemetry {
    public record Sample(long timeMs, double speed, boolean estimated,
                         long usedRam, long totalRam, double batteryCelsius) { }
    public interface Listener { void onSample(Sample sample); }
    // Only the Application context is retained; Activity observers detach in onStop.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static RuntimeTelemetry instance;

    public static synchronized RuntimeTelemetry get(Context context) {
        if (instance == null) instance = new RuntimeTelemetry(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private boolean coreRunning;
    private boolean inferenceActive;
    private boolean scheduled;
    private long inferenceStartedMs;
    private long speedObservedMs;
    private GenerationSpeed streamedSpeed;
    private long batterySampleMs;
    private double batteryCelsius = Double.NaN;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!coreRunning && listeners.isEmpty()) {
                scheduled = false;
                return;
            }
            Sample sample = readSample();
            if (history.size() == 61) history.removeFirst();
            history.addLast(sample);
            for (Listener listener : List.copyOf(listeners)) listener.onSample(sample);
            if (coreRunning || !listeners.isEmpty()) handler.postDelayed(this, 2_000L);
            else scheduled = false;
        }
    };

    // All lifecycle and observation calls are made on the main thread.
    RuntimeTelemetry(Context context) { this.context = context; }

    public void observe(Listener listener) {
        if (!listeners.add(listener)) return;
        for (Sample sample : history) listener.onSample(sample);
        updateSchedule();
    }

    public void removeObserver(Listener listener) {
        listeners.remove(listener);
        updateSchedule();
    }

    public List<Sample> history() { return List.copyOf(history); }

    public void setCoreRunning(boolean running) {
        coreRunning = running;
        if (!running) setInferenceActive(false);
        updateSchedule();
    }

    public void setInferenceActive(boolean active) {
        if (active && !inferenceActive) {
            inferenceStartedMs = SystemClock.uptimeMillis();
            streamedSpeed = null;
        }
        inferenceActive = active;
        if (!active) streamedSpeed = null;
    }

    public void recordStreamingSpeed(GenerationSpeed speed) {
        streamedSpeed = speed;
        speedObservedMs = SystemClock.uptimeMillis();
    }

    private void updateSchedule() {
        if (!coreRunning && listeners.isEmpty()) {
            handler.removeCallbacks(tick);
            scheduled = false;
        } else if (!scheduled) {
            scheduled = true;
            handler.post(tick);
        }
    }

    private Sample readSample() {
        long now = SystemClock.uptimeMillis();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        if (batterySampleMs == 0 || now - batterySampleMs >= 10_000L) {
            batterySampleMs = now;
            try {
                Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int tenths = battery == null ? Integer.MIN_VALUE
                        : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                batteryCelsius = tenths >= -400 && tenths <= 1000 ? tenths / 10d : Double.NaN;
            } catch (SecurityException unavailable) {
                batteryCelsius = Double.NaN;
            }
        }
        double speed = inferenceActive ? Double.NaN : 0;
        boolean estimated = false;
        if (inferenceActive) {
            ServerProgress progress = ServerProgress.latestSince(Math.max(inferenceStartedMs, now - 8_000L));
            if (progress != null && progress.kind == ServerProgress.Kind.DECODE) {
                speed = progress.tokensPerSecond;
            } else if (streamedSpeed != null && now - speedObservedMs <= 6_000L) {
                speed = streamedSpeed.tokensPerSecond;
                estimated = streamedSpeed.estimated;
            }
        }
        return new Sample(now, speed, estimated,
                Math.max(0L, memory.totalMem - memory.availMem), memory.totalMem, batteryCelsius);
    }
}
