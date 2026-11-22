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

        assertEquals(directory, SlotSnapshotDirectory.prepare(cache, "absent-model"));

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
        File directory = SlotSnapshotDirectory.prepare(temporary.getRoot(), "model");
        assertTrue(directory.isDirectory());
    }

    @Test
    public void switchingModelsRetainsTheNewestMatchingPrefixWithinTheSameDiskLimit() throws Exception {
        File cache = temporary.getRoot();
        File directory = SlotSnapshotDirectory.prepare(cache, "lfm");
        String hash = "0123456789abcdef01234567";
        File olderLfm = snapshot(directory, "pideck-lfm-aaaaaaaaaaaaaaaaaaaaaaaa.bin", 1);
        File newestLfm = snapshot(directory, "pideck-lfm-" + hash + ".bin", 2);
        File relatedModel = snapshot(directory, "pideck-lfm-other-" + hash + ".bin", 3);
        for (int i = 1; i < SlotSnapshotDirectory.KEEP; i++) {
            snapshot(directory, "pideck-qwen-" + hash.substring(1) + i + ".bin", 3 + i);
        }

        SlotSnapshotDirectory.prepare(cache, "lfm");

        assertTrue(newestLfm.isFile());
        assertFalse(olderLfm.exists());
        assertFalse(relatedModel.exists());
        assertEquals(SlotSnapshotDirectory.KEEP, directory.listFiles().length);
    }

    private static File snapshot(File directory, String name, int seconds) throws Exception {
        File snapshot = new File(directory, name);
        Files.write(snapshot.toPath(), new byte[]{1});
        assertTrue(snapshot.setLastModified(seconds * 1000L));
        return snapshot;
    }
}
