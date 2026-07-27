package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A picked file can be any size, so the figure the deck quotes back has to identify it. Rounding a
 * small file to "0 MiB" tells the user nothing about what they actually chose.
 */
public class HumanBytesTest {
    @Test
    public void modelSizedValuesKeepTheUnitsTheModelRowAlreadyUses() {
        assertEquals("2.81 GiB", ModelSpec.humanBytes(3_013_027_808L));
        assertEquals("537 MiB", ModelSpec.humanBytes(563_036_064L));
    }

    @Test
    public void aSmallFileIsNamedRatherThanRoundedAwayToZero() {
        assertEquals("329 B", ModelSpec.humanBytes(329L));
        assertEquals("0 B", ModelSpec.humanBytes(0L));
    }

    @Test
    public void kilobyteSizedFilesReadInKibibytes() {
        assertEquals("2.9 KiB", ModelSpec.humanBytes(2_990L));
        assertEquals("1.0 KiB", ModelSpec.humanBytes(1_024L));
    }

    @Test
    public void anUnknownLengthIsNotRenderedAsASize() {
        assertEquals("?", ModelSpec.humanBytes(-1L));
    }

    @Test
    public void eachUnitStartsExactlyWhereThePreviousOneEnds() {
        assertEquals("1023 B", ModelSpec.humanBytes(1_023L));
        assertEquals("1.0 KiB", ModelSpec.humanBytes(1_024L));
        assertEquals("1 MiB", ModelSpec.humanBytes(1_048_576L));
        assertEquals("1.00 GiB", ModelSpec.humanBytes(1_073_741_824L));
    }
}
