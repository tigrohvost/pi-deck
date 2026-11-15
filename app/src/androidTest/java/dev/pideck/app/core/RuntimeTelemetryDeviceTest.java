package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class RuntimeTelemetryDeviceTest {
    @Test
    public void serviceKeepsHistoryAfterUiLeavesAndStopsWhenBothOwnersLeave() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<RuntimeTelemetry> ref = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        AtomicLong lastTime = new AtomicLong();
        RuntimeTelemetry.Listener listener = sample -> deliveries.incrementAndGet();
        instrumentation.runOnMainSync(() -> {
            RuntimeTelemetry telemetry = new RuntimeTelemetry(
                    instrumentation.getTargetContext().getApplicationContext());
            ref.set(telemetry);
            telemetry.setCoreRunning(true);
            telemetry.observe(listener);
        });
        try {
            Thread.sleep(2_200L);
            instrumentation.runOnMainSync(() -> {
                RuntimeTelemetry telemetry = ref.get();
                assertTrue(telemetry.history().size() >= 1);
                telemetry.removeObserver(listener);
                lastTime.set(telemetry.history().get(telemetry.history().size() - 1).timeMs());
            });
            int before = deliveries.get();
            Thread.sleep(2_200L);
            instrumentation.runOnMainSync(() -> {
                RuntimeTelemetry telemetry = ref.get();
                assertEquals("Stopped Activity still receives samples", before, deliveries.get());
                long newest = telemetry.history().get(telemetry.history().size() - 1).timeMs();
                assertTrue("Background service lost telemetry", newest > lastTime.get());
                telemetry.observe(listener);
                assertTrue("Returning UI did not receive history", deliveries.get() > before);
                telemetry.removeObserver(listener);
                telemetry.setCoreRunning(false);
                lastTime.set(newest);
            });
            Thread.sleep(2_200L);
            instrumentation.runOnMainSync(() -> {
                RuntimeTelemetry telemetry = ref.get();
                assertEquals("Sampler survived both owners", lastTime.get(),
                        telemetry.history().get(telemetry.history().size() - 1).timeMs());
            });
        } finally {
            instrumentation.runOnMainSync(() -> {
                ref.get().removeObserver(listener);
                ref.get().setCoreRunning(false);
            });
        }
    }
}
