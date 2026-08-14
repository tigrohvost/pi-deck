package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryPressurePolicyTest {
    @Test
    public void lifecycleBackgroundHintIsNotMistakenForPressure() {
        assertFalse(MemoryPressurePolicy.critical(20));
        assertFalse(MemoryPressurePolicy.critical(40));
        assertTrue(MemoryPressurePolicy.critical(15));
        assertTrue(MemoryPressurePolicy.critical(60));
    }

    @Test
    public void idleCoreStopsButActiveTurnIsNeverAborted() {
        assertEquals(
                MemoryPressurePolicy.Action.STOP_IDLE_CORE,
                MemoryPressurePolicy.decide(15, true, false)
        );
        assertEquals(
                MemoryPressurePolicy.Action.WARN_ACTIVE_TURN,
                MemoryPressurePolicy.decide(15, true, true)
        );
        assertEquals(
                MemoryPressurePolicy.Action.NONE,
                MemoryPressurePolicy.decide(15, false, false)
        );
    }
}
