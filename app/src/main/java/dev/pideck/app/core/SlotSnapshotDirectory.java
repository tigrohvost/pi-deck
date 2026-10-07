package dev.pideck.app.core;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Where llama-server keeps saved system+tools prefix slots ({@code --slot-save-path}).
 *
 * <p>A snapshot lets the first request after a cold model load restore its prefix in tens of
 * milliseconds instead of evaluating ~1.4k-2k tokens again. Each snapshot is tens of MB and is
 * keyed by model, request contract and system prompt, so the directory lives in the app cache
 * and keeps only the most recently used few.
 */
public final class SlotSnapshotDirectory {
    public static final String NAME = "llama-slots";
    public static final int KEEP = 4;

    private SlotSnapshotDirectory() {
    }

    /** Creates the directory, prunes it to {@link #KEEP} newest snapshots, returns its path. */
    public static File prepare(File cacheDir) {
        File directory = new File(cacheDir, NAME);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            return null;
        }
        File[] snapshots = directory.listFiles((dir, name) -> name.startsWith("pideck-")
                && name.endsWith(".bin"));
        if (snapshots != null && snapshots.length > KEEP) {
            Arrays.sort(snapshots, Comparator.comparingLong(File::lastModified).reversed());
            for (int index = KEEP; index < snapshots.length; index++) {
                //noinspection ResultOfMethodCallIgnored
                snapshots[index].delete();
            }
        }
        return directory;
    }
}
