package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InferenceDisplayPolicyTest {
    @Test
    public void inferenceCapsThePanelAndIdleReleasesIt() {
        assertEquals(60f, InferenceDisplayPolicy.preferredRefreshRate(true), 0f);
        assertEquals(0f, InferenceDisplayPolicy.preferredRefreshRate(false), 0f);
    }

    @Test
    public void theScreenStaysOnOnlyForMaximumSpeedDuringInference() {
        assertTrue(InferenceDisplayPolicy.keepsScreenOn(true, true));
        assertFalse(InferenceDisplayPolicy.keepsScreenOn(true, false));
        assertFalse(InferenceDisplayPolicy.keepsScreenOn(false, true));
    }
}
