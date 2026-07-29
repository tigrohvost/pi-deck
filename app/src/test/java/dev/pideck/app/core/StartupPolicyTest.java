package dev.pideck.app.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The launch path is where wasted seconds hide, so each decision that removes one is pinned here.
 */
public class StartupPolicyTest {
    private static final long GIB = 1_073_741_824L;

    @Test
    public void nothingToStopMeansNoTermuxRoundTrip() {
        assertTrue(StartupPolicy.skipsRuntimeStop("STOPPED", false, false));
        assertTrue(StartupPolicy.skipsRuntimeStop("FAILED", false, false));
    }

    @Test
    public void aClaimedServerIsStillRetiredThroughTermux() {
        assertFalse(StartupPolicy.skipsRuntimeStop("STOPPED", true, false));
        assertFalse(StartupPolicy.skipsRuntimeStop("STOPPED", false, true));
        assertFalse(StartupPolicy.skipsRuntimeStop("READY", false, false));
        assertFalse(StartupPolicy.skipsRuntimeStop("STARTING", false, false));
    }

    @Test
    public void aRunningServerWithoutABridgeFinishesItself() {
        assertTrue(StartupPolicy.warmsOnLaunch(false, true, true, false, false, false));
    }

    @Test
    public void aFullyReadyCoreIsLeftAlone() {
        assertFalse(StartupPolicy.warmsOnLaunch(true, true, true, true, false, false));
    }

    @Test
    public void loadingTheModelWithoutATapNeedsTheToggle() {
        assertFalse(StartupPolicy.warmsOnLaunch(false, true, false, false, false, false));
        assertTrue(StartupPolicy.warmsOnLaunch(true, true, false, false, false, false));
    }

    @Test
    public void autostartYieldsToMemoryPressureAndToWorkInFlight() {
        assertFalse(StartupPolicy.warmsOnLaunch(true, true, false, false, false, true));
        assertFalse(StartupPolicy.warmsOnLaunch(true, true, false, false, true, false));
        assertFalse(StartupPolicy.warmsOnLaunch(true, false, false, false, false, false));
    }

    @Test
    public void aPromptAtAColdCoreWaitsInsteadOfBouncing() {
        assertTrue(StartupPolicy.queuesUntilReady(false, true, false));
    }

    @Test
    public void aPromptIsNotQueuedTwiceOrWithoutAWayToWarm() {
        assertFalse(StartupPolicy.queuesUntilReady(false, true, true));
        assertFalse(StartupPolicy.queuesUntilReady(false, false, false));
        assertFalse(StartupPolicy.queuesUntilReady(true, true, false));
    }

    @Test
    public void theOomWarningIsAskedOncePerModel() {
        assertTrue(StartupPolicy.asksOomRisk(false, 2 * GIB, GIB, 3 * GIB, false));
        assertFalse(StartupPolicy.asksOomRisk(false, 2 * GIB, GIB, 3 * GIB, true));
    }

    @Test
    public void liveMemoryPressureIsAlwaysReported() {
        assertTrue(StartupPolicy.asksOomRisk(true, 8 * GIB, GIB, 3 * GIB, true));
    }

    @Test
    public void aPhoneWithRoomIsNotWarned() {
        assertFalse(StartupPolicy.asksOomRisk(false, 8 * GIB, GIB, 3 * GIB, false));
    }
}
