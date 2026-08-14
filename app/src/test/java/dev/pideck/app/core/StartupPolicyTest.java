package dev.pideck.app.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The launch path is where wasted seconds hide, so each decision that removes one is pinned here.
 */
public class StartupPolicyTest {
    private static final long GIB = 1_073_741_824L;

    @Test
    public void staleNativeReadinessDoesNotSurviveTheOwningProcess() {
        assertEquals("STOPPED", StartupPolicy.effectiveNativeState("READY", false));
        assertEquals("STOPPED", StartupPolicy.effectiveNativeState("STARTING", false));
        assertEquals("READY", StartupPolicy.effectiveNativeState("READY", true));
        assertEquals("FAILED", StartupPolicy.effectiveNativeState("FAILED", false));
    }

    @Test
    public void previousNativeIdentityGetsABoundedServiceHandoffWindow() {
        assertFalse(StartupPolicy.nativeOperationMismatchIsFatal("new", "old", 0L));
        assertFalse(StartupPolicy.nativeOperationMismatchIsFatal("new", "old", 9_999L));
        assertTrue(StartupPolicy.nativeOperationMismatchIsFatal("new", "old", 10_000L));
    }

    @Test
    public void matchingOrUnpublishedNativeIdentityIsNeverAConflict() {
        assertFalse(StartupPolicy.nativeOperationMismatchIsFatal("new", "new", 60_000L));
        assertFalse(StartupPolicy.nativeOperationMismatchIsFatal("new", "", 60_000L));
        assertFalse(StartupPolicy.nativeOperationMismatchIsFatal("new", null, 60_000L));
    }

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
    public void typingWarmsAColdCoreWithoutRequiringAutostart() {
        assertTrue(StartupPolicy.warmsOnComposerIntent(
                true, true, false, false, false, false
        ));
        assertFalse(StartupPolicy.warmsOnComposerIntent(
                false, true, false, false, false, false
        ));
    }

    @Test
    public void composerWarmupYieldsToPressureWorkAndReadiness() {
        assertFalse(StartupPolicy.warmsOnComposerIntent(
                true, true, false, false, false, true
        ));
        assertFalse(StartupPolicy.warmsOnComposerIntent(
                true, true, false, false, true, false
        ));
        assertFalse(StartupPolicy.warmsOnComposerIntent(
                true, false, false, false, false, false
        ));
        assertFalse(StartupPolicy.warmsOnComposerIntent(
                true, true, true, true, false, false
        ));
    }

    @Test
    public void composerCanFinishAnAlreadyLoadedBridgeUnderPressure() {
        assertTrue(StartupPolicy.warmsOnComposerIntent(
                true, true, true, false, false, true
        ));
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
    public void persistedQueueWaitsWhenBridgeContextIsUnknown() {
        assertTrue(StartupPolicy.asksQueuedContextChoice(
                true,
                SessionContextUsage.unknown(10_240)
        ));
        assertFalse(StartupPolicy.asksQueuedContextChoice(
                false,
                SessionContextUsage.unknown(10_240)
        ));
    }

    @Test
    public void persistedQueueUsesTheSameLargeContextThreshold() throws Exception {
        SessionContextUsage belowThreshold = SessionContextUsage.parse(
                new JSONObject().put("tokens", 6_143).put("contextWindow", 10_240),
                10_240
        );
        SessionContextUsage atThreshold = SessionContextUsage.parse(
                new JSONObject().put("tokens", 6_144).put("contextWindow", 10_240),
                10_240
        );

        assertFalse(StartupPolicy.asksQueuedContextChoice(true, belowThreshold));
        assertTrue(StartupPolicy.asksQueuedContextChoice(true, atThreshold));
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

    @Test
    public void termuxLinkProbeDoesNotDependOnRuntimeInstallation() {
        assertTrue(StartupPolicy.probesTermuxOnLaunch(0, false, true, true));
        assertFalse(StartupPolicy.probesTermuxOnLaunch(0, true, true, true));
        assertFalse(StartupPolicy.probesTermuxOnLaunch(0, false, false, true));
        assertFalse(StartupPolicy.probesTermuxOnLaunch(0, false, true, false));
    }

    @Test
    public void termuxColdStartGetsExactlyOneRetry() {
        assertTrue(StartupPolicy.retriesStartupLinkProbe(1));
        assertFalse(StartupPolicy.retriesStartupLinkProbe(2));
        assertFalse(StartupPolicy.probesTermuxOnLaunch(2, false, true, true));
    }
}
