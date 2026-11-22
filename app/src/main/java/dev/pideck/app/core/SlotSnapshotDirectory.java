package dev.pideck.app.core;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Where llama-server keeps saved system+tools prefix slots ({@code --slot-save-path}).
 *
 * <p>A snapshot lets the first request after a cold model load restore its prefix in tens of
 * milliseconds instead of evaluating ~1.4k-2k tokens again. Each snapshot is tens of MB and is
 * keyed by model, request contract and system prompt, so the directory lives in the app cache
 * and keeps a bounded set of recent snapshots.
 */
public final class SlotSnapshotDirectory {
    public static final String NAME = "llama-slots";
    public static final int KEEP = 4;

    private SlotSnapshotDirectory() {
    }

    /** Reserves one of the retained snapshots for the model that is about to load. */
    public static File prepare(File cacheDir, String modelId) {
        File directory = new File(cacheDir, NAME);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            return null;
        }
        File[] snapshots = directory.listFiles((dir, name) -> name.startsWith("pideck-")
                && name.endsWith(".bin"));
        if (snapshots != null && snapshots.length > KEEP) {
            Arrays.sort(snapshots, Comparator.comparingLong(File::lastModified).reversed()
                    .thenComparing(File::getName));
            // snapshotName() in pideck-local-cache.ts truncates the validated catalog ID to
            // 64 characters and follows it with the exact contract's 24-hex-digit hash.
            String id = modelId.substring(0, Math.min(64, modelId.length()));
            Pattern forModel = Pattern.compile("pideck-" + Pattern.quote(id) + "-[a-f0-9]{24}\\.bin");
            for (int index = 0; index < snapshots.length; index++) {
                if (forModel.matcher(snapshots[index].getName()).matches()) {
                    // Give the model's newest prefix the last retained slot if it needs one.
                    if (index >= KEEP) Collections.swap(Arrays.asList(snapshots), index, KEEP - 1);
                    break;
                }
            }
            for (int index = KEEP; index < snapshots.length; index++) {
                //noinspection ResultOfMethodCallIgnored
                snapshots[index].delete();
            }
        }
        return directory;
    }
}
