package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class SlotSnapshotDirectoryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void keepsOnlyTheNewestSnapshotsAndLeavesOtherFilesAlone() throws Exception {
        File cache = temporary.getRoot();
        File directory = new File(cache, SlotSnapshotDirectory.NAME);
        assertTrue(directory.mkdirs());
        for (int index = 0; index < SlotSnapshotDirectory.KEEP + 3; index++) {
            File snapshot = new File(directory, "pideck-model-" + index + ".bin");
            Files.write(snapshot.toPath(), new byte[]{1});
            assertTrue(snapshot.setLastModified(1_000_000L + index * 1_000L));
        }
        File foreign = new File(directory, "notes.txt");
        Files.write(foreign.toPath(), new byte[]{1});

        assertEquals(directory, SlotSnapshotDirectory.prepare(cache));

        for (int index = 0; index < 3; index++) {
            assertFalse(new File(directory, "pideck-model-" + index + ".bin").exists());
        }
        for (int index = 3; index < SlotSnapshotDirectory.KEEP + 3; index++) {
            assertTrue(new File(directory, "pideck-model-" + index + ".bin").exists());
        }
        assertTrue(foreign.exists());
    }

    @Test
    public void createsTheDirectoryOnFirstUse() {
        File directory = SlotSnapshotDirectory.prepare(temporary.getRoot());
        assertTrue(directory.isDirectory());
    }
}
