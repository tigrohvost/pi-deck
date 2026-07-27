package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Every pick used to leave a standing read permission behind, including the ones the deck refused,
 * so a device can be carrying grants for files no model will ever open. A grant is worth keeping
 * only while some model still points at it.
 */
public class StaleGrantsTest {
    private static final String IN_USE = "content://x/document/primary%3ADownload%2Fmodel.gguf";
    private static final String LEFTOVER = "content://x/document/primary%3ADownload%2Fnotes.json";

    @Test
    public void aGrantNoModelPointsAtIsReleased() {
        assertEquals(
                Collections.singletonList(LEFTOVER),
                ModelDownloadManager.staleGrants(
                        Arrays.asList(IN_USE, LEFTOVER),
                        Set.of(IN_USE)
                )
        );
    }

    @Test
    public void aGrantAModelStillPointsAtIsKept() {
        assertEquals(
                Collections.emptyList(),
                ModelDownloadManager.staleGrants(
                        Collections.singletonList(IN_USE),
                        Set.of(IN_USE)
                )
        );
    }

    @Test
    public void withNoSourcesStoredEveryGrantIsStale() {
        assertEquals(
                Arrays.asList(IN_USE, LEFTOVER),
                ModelDownloadManager.staleGrants(
                        Arrays.asList(IN_USE, LEFTOVER),
                        Collections.emptySet()
                )
        );
    }

    @Test
    public void holdingNothingReleasesNothing() {
        List<String> held = Collections.emptyList();
        assertEquals(
                Collections.emptyList(),
                ModelDownloadManager.staleGrants(held, Set.of(IN_USE))
        );
    }
}
