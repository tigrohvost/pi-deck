package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The deck re-reads model state on every heartbeat, so a source URI that cannot be resolved would
 * otherwise be handed to the DocumentsProvider several times a second forever. What matters here
 * is that a failed measurement is remembered as firmly as a successful one.
 */
public class SourceLengthsTest {
    private static final String URI = "content://x/document/primary%3ADownload%2Fa.gguf";
    private static final String OTHER = "content://x/document/primary%3ADownload%2Fb.gguf";

    @Test
    public void anUnseenModelHasToBeMeasured() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        assertEquals(
                ModelDownloadManager.SourceLengths.UNMEASURED,
                lengths.lookup("qwen3.5-4b", URI)
        );
    }

    @Test
    public void aMeasuredLengthIsAnsweredWithoutAskingAgain() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        lengths.record("qwen3.5-4b", URI, 3_013_027_808L);
        assertEquals(3_013_027_808L, lengths.lookup("qwen3.5-4b", URI));
    }

    @Test
    public void aDanglingSourceIsRememberedSoTheProviderIsAskedOnce() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        lengths.record("qwen3.5-2b", URI, -1L);
        assertEquals(-1L, lengths.lookup("qwen3.5-2b", URI));
    }

    @Test
    public void aDifferentUriForTheSameModelIsMeasuredAfresh() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        lengths.record("qwen3.5-4b", URI, 3_013_027_808L);
        assertEquals(
                ModelDownloadManager.SourceLengths.UNMEASURED,
                lengths.lookup("qwen3.5-4b", OTHER)
        );
    }

    @Test
    public void modelsDoNotAnswerForEachOther() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        lengths.record("qwen3.5-4b", URI, 3_013_027_808L);
        assertEquals(
                ModelDownloadManager.SourceLengths.UNMEASURED,
                lengths.lookup("qwen3.5-2b", URI)
        );
    }

    @Test
    public void forgettingAModelSendsTheNextLookupBackToTheProvider() {
        ModelDownloadManager.SourceLengths lengths = new ModelDownloadManager.SourceLengths();
        lengths.record("qwen3.5-4b", URI, 3_013_027_808L);
        lengths.forget("qwen3.5-4b");
        assertEquals(
                ModelDownloadManager.SourceLengths.UNMEASURED,
                lengths.lookup("qwen3.5-4b", URI)
        );
    }
}
