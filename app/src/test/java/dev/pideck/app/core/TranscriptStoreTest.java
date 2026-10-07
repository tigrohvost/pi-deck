package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.pideck.app.ui.ConsoleEntry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TranscriptStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static List<ConsoleEntry> entries(int count) {
        ArrayList<ConsoleEntry> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new ConsoleEntry(ConsoleEntry.Channel.USER, "message " + i, 1_000L + i));
        }
        return result;
    }

    @Test
    public void savesAreCoalescedAndOnlyTheLatestSnapshotReachesDisk() throws Exception {
        File file = new File(temporary.getRoot(), "transcript.json");
        TranscriptStore store = new TranscriptStore(file, null);
        for (int i = 1; i <= 50; i++) store.save(entries(i));
        assertFalse("a save wrote synchronously on the caller", file.exists());
        assertTrue(store.awaitIdle(TranscriptStore.WRITE_DELAY_MS + 2_000L));
        Thread.sleep(TranscriptStore.WRITE_DELAY_MS + 200L);
        assertTrue(store.awaitIdle(2_000L));
        List<ConsoleEntry> restored = new TranscriptStore(file, null).load();
        assertEquals(50, restored.size());
        assertEquals("message 49", restored.get(49).text);
    }

    @Test
    public void flushWritesWithoutWaitingForTheDelay() throws Exception {
        File file = new File(temporary.getRoot(), "transcript.json");
        TranscriptStore store = new TranscriptStore(file, null);
        store.save(entries(3));
        store.flush();
        assertTrue(store.awaitIdle(2_000L));
        assertEquals(3, new TranscriptStore(file, null).load().size());
    }

    @Test
    public void encodingKeepsTheNewestEntriesWithinBothBounds() throws Exception {
        String encoded = TranscriptStore.encode(entries(TranscriptStore.MAX_ENTRIES + 25));
        List<ConsoleEntry> decoded = TranscriptStore.decode(encoded);
        assertEquals(TranscriptStore.MAX_ENTRIES, decoded.size());
        assertEquals("message 84", decoded.get(decoded.size() - 1).text);

        ArrayList<ConsoleEntry> large = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            large.add(new ConsoleEntry(ConsoleEntry.Channel.AGENT, "x".repeat(20_000) + i, i));
        }
        String bounded = TranscriptStore.encode(large);
        assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= TranscriptStore.MAX_BYTES);
        List<ConsoleEntry> kept = TranscriptStore.decode(bounded);
        assertTrue(kept.get(kept.size() - 1).text.endsWith("39"));
    }

    @Test
    public void exactSpeedSurvivesTheRoundTrip() throws Exception {
        ConsoleEntry answer = new ConsoleEntry(
                ConsoleEntry.Channel.AGENT, "done", 5L, "", "", 16.47d, 128L
        );
        ConsoleEntry restored = TranscriptStore.decode(TranscriptStore.encode(List.of(answer))).get(0);
        assertEquals(16.47d, restored.tokensPerSecond, 1e-9);
        assertEquals(128L, restored.outputTokens);
    }

    @Test
    public void aLegacyTranscriptIsMigratedOnceAndThenReadFromTheFile() throws Exception {
        File file = new File(temporary.getRoot(), "transcript.json");
        AtomicInteger legacyReads = new AtomicInteger();
        String legacy = TranscriptStore.encode(entries(4));
        TranscriptStore store = new TranscriptStore(file, () -> {
            legacyReads.incrementAndGet();
            return legacyReads.get() == 1 ? legacy : null;
        });
        assertEquals(4, store.load().size());
        assertTrue(store.awaitIdle(2_000L));
        assertTrue("the migrated transcript was not written to its own file", file.isFile());
        assertEquals(4, store.load().size());
        assertEquals(1, legacyReads.get());
    }

    @Test
    public void aCorruptFileIsDiscardedInsteadOfCrashingTheLaunch() throws Exception {
        File file = new File(temporary.getRoot(), "transcript.json");
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(new TranscriptStore(file, () -> "unused").load().isEmpty());
        assertFalse(file.exists());
    }
}
