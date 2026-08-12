package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ThermalHeadroomTest {

    @Test
    public void parsesFractionOfNominalClock() {
        assertEquals(0.55f, ThermalHeadroom.parse("1848000\n", "3360000\n"), 0.001f);
        assertEquals(1.0f, ThermalHeadroom.parse("3360000", "3360000"), 0.001f);
    }

    @Test
    public void refusesGarbageFailClosed() {
        assertNull(ThermalHeadroom.parse("", "3360000"));
        assertNull(ThermalHeadroom.parse("abc", "3360000"));
        assertNull(ThermalHeadroom.parse("1848000", "0"));
        assertNull(ThermalHeadroom.parse(null, null));
    }
}
