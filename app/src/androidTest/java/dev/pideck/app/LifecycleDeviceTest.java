package dev.pideck.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** Opt-in check that recreating the configured Activity does not accumulate background workers. */
@RunWith(AndroidJUnit4.class)
public final class LifecycleDeviceTest {
    @Test
    public void recreationReleasesOldTranscriptAndBridgeWorkers() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments()
                .getString("pideck.verifyLifecycle")));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Activity activity = monitor.waitForActivityWithTimeout(10_000L);
            assertNotNull("MainActivity did not start", activity);
            for (int attempt = 0; attempt < 4; attempt++) {
                Activity previous = activity;
                instrumentation.removeMonitor(monitor);
                AtomicReference<Instrumentation.ActivityMonitor> next = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> {
                    // A monitor also observes resumes. Arm a fresh one after the current
                    // lifecycle transaction so its saved Activity cannot be the old instance.
                    next.set(instrumentation.addMonitor(MainActivity.class.getName(), null, false));
                    previous.recreate();
                });
                monitor = next.get();
                activity = monitor.waitForActivityWithTimeout(10_000L);
                assertNotNull("MainActivity did not recreate", activity);
                assertTrue("The recreation monitor returned the old Activity", activity != previous);
            }
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < deadline && !workersReleased()) Thread.sleep(50L);
            assertTrue("Old transcript writers survived Activity destruction",
                    workers("pideck-transcript") <= 1);
            assertTrue("Old bridge pollers survived Activity destruction",
                    workers("pideck-rpc-events") <= 1);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private static boolean workersReleased() {
        return workers("pideck-transcript") <= 1 && workers("pideck-rpc-events") <= 1;
    }

    private static int workers(String name) {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && name.equals(thread.getName())) count++;
        }
        return count;
    }
}
