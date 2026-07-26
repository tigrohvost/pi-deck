package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import android.app.DownloadManager;

import org.junit.Test;

/**
 * DownloadManager pre-allocates the destination file to its final length before streaming any
 * bytes (DownloadThread -> StorageManager.allocateBytes -> posix_fallocate/ftruncate), so file
 * length alone cannot distinguish a finished transfer from a running one.
 */
public class ModelDownloadPhaseTest {
    @Test
    public void runningDownloadIsNotCompleteEvenWhenTheFileAlreadyHasItsFinalLength() {
        assertEquals(
                ModelDownloadManager.Phase.RUNNING,
                ModelDownloadManager.phaseOf(true, DownloadManager.STATUS_RUNNING, true)
        );
        assertEquals(
                ModelDownloadManager.Phase.QUEUED,
                ModelDownloadManager.phaseOf(true, DownloadManager.STATUS_PENDING, true)
        );
        assertEquals(
                ModelDownloadManager.Phase.PAUSED,
                ModelDownloadManager.phaseOf(true, DownloadManager.STATUS_PAUSED, true)
        );
    }

    @Test
    public void failedDownloadIsNotHiddenByThePreallocatedFile() {
        assertEquals(
                ModelDownloadManager.Phase.FAILED,
                ModelDownloadManager.phaseOf(true, DownloadManager.STATUS_FAILED, true)
        );
    }

    @Test
    public void successfulDownloadIsComplete() {
        assertEquals(
                ModelDownloadManager.Phase.COMPLETE,
                ModelDownloadManager.phaseOf(true, DownloadManager.STATUS_SUCCESSFUL, true)
        );
    }

    @Test
    public void fileFromAnEarlierInstallCountsWhenDownloadManagerHasNoRow() {
        assertEquals(
                ModelDownloadManager.Phase.COMPLETE,
                ModelDownloadManager.phaseOf(false, 0, true)
        );
        assertEquals(
                ModelDownloadManager.Phase.MISSING,
                ModelDownloadManager.phaseOf(false, 0, false)
        );
    }

    @Test
    public void unknownStatusFallsBackToTheFileItself() {
        assertEquals(
                ModelDownloadManager.Phase.COMPLETE,
                ModelDownloadManager.phaseOf(true, 9999, true)
        );
        assertEquals(
                ModelDownloadManager.Phase.MISSING,
                ModelDownloadManager.phaseOf(true, 9999, false)
        );
    }
}
