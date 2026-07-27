package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The pinned byte count is the only thing standing between a hand-picked file and a SHA-256 pass
 * that would otherwise run over gigabytes of the wrong artifact, so the rule that accepts a pick
 * is kept pure and covered here rather than reachable only through a live ContentResolver.
 */
public class ModelAttachTest {
    private static final long EXPECTED = 3_013_027_808L;

    @Test
    public void exactPinnedLengthIsAccepted() {
        assertEquals(
                ModelDownloadManager.AttachFailure.NONE,
                ModelDownloadManager.attachFailureOf(true, true, EXPECTED, EXPECTED)
        );
    }

    @Test
    public void oneByteEitherSideOfThePinnedLengthIsRefused() {
        assertEquals(
                ModelDownloadManager.AttachFailure.SIZE_MISMATCH,
                ModelDownloadManager.attachFailureOf(true, true, EXPECTED - 1, EXPECTED)
        );
        assertEquals(
                ModelDownloadManager.AttachFailure.SIZE_MISMATCH,
                ModelDownloadManager.attachFailureOf(true, true, EXPECTED + 1, EXPECTED)
        );
    }

    @Test
    public void anEmptyFileIsARefusedSizeAndNotAnEmptyPass() {
        assertEquals(
                ModelDownloadManager.AttachFailure.SIZE_MISMATCH,
                ModelDownloadManager.attachFailureOf(true, true, 0L, EXPECTED)
        );
    }

    @Test
    public void aFilePickedForTheWrongModelIsRefused() {
        // The 2B artifact offered for the 4B row: both are real models, only the row is wrong.
        assertEquals(
                ModelDownloadManager.AttachFailure.SIZE_MISMATCH,
                ModelDownloadManager.attachFailureOf(true, true, 1_396_198_496L, EXPECTED)
        );
    }

    @Test
    public void anUnknownSizeIsUnreadableRatherThanAMismatch() {
        // A provider that cannot answer the size query points at a different fix than a file of
        // the wrong length, so the two must not collapse into one message.
        assertEquals(
                ModelDownloadManager.AttachFailure.UNREADABLE,
                ModelDownloadManager.attachFailureOf(true, false, 0L, EXPECTED)
        );
    }

    @Test
    public void aUriThatIsNotADocumentIsRefusedBeforeTheSizeIsConsulted() {
        assertEquals(
                ModelDownloadManager.AttachFailure.NOT_A_DOCUMENT,
                ModelDownloadManager.attachFailureOf(false, true, EXPECTED, EXPECTED)
        );
        assertEquals(
                ModelDownloadManager.AttachFailure.NOT_A_DOCUMENT,
                ModelDownloadManager.attachFailureOf(false, false, 0L, EXPECTED)
        );
    }
}
