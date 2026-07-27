package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CpuProfileTest {
    @Test
    public void snapdragonEightCoreProfileUsesFivePerformanceCores() {
        CpuProfile profile = CpuProfile.fromMaxFrequencies(new long[]{
                2_016_000, 2_016_000, 2_016_000,
                2_803_000, 2_803_000, 2_803_000, 2_803_000,
                3_360_000
        });
        assertEquals(5, profile.decodeThreads);
        assertEquals(8, profile.batchThreads);
        assertEquals("3-7", profile.decodeCpuSet);
        assertEquals("0-7", profile.batchCpuSet);
    }

    @Test
    public void smallerPhoneNeverReferencesMissingCpus() {
        CpuProfile profile = CpuProfile.fromMaxFrequencies(new long[]{
                1_800_000, 1_800_000, 2_400_000, 2_400_000
        });
        assertEquals(4, profile.decodeThreads);
        assertEquals(4, profile.batchThreads);
        assertEquals("0-3", profile.decodeCpuSet);
        assertEquals("0-3", profile.batchCpuSet);
    }

    @Test
    public void hiddenFrequenciesStillYieldDeterministicBoundedProfile() {
        CpuProfile profile = CpuProfile.fromMaxFrequencies(new long[8]);
        assertEquals(5, profile.decodeThreads);
        assertEquals("3-7", profile.decodeCpuSet);
    }
}
