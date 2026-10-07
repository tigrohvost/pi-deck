package dev.pideck.app.core;

import dev.pideck.app.ui.ConsoleEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The deck's presentation transcript in its own private file, encoded and written off the UI
 * thread.
 *
 * <p>It used to live inside the main SharedPreferences file, so every streamed bridge event that
 * advanced the event cursor rewrote up to 256 KB of transcript XML, and every append rebuilt the
 * JSON on the UI thread. Saves are now coalesced: the latest snapshot wins and is written once per
 * {@link #WRITE_DELAY_MS}. Pi's session file stays the durable conversation authority, so a crash
 * inside that window can lose at most the last fraction of a second of presentation.
 */
public final class TranscriptStore {
    public static final int MAX_ENTRIES = 60;
    public static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ENTRY_BYTES = 32 * 1024;
    static final long WRITE_DELAY_MS = 750L;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    private final File file;
    private final Supplier<String> legacyReader;
    private final ScheduledExecutorService writer;
    private final AtomicReference<List<ConsoleEntry>> pending = new AtomicReference<>();
    private ScheduledFuture<?> scheduled;

    /**
     * @param legacyReader returns and forgets a transcript stored by an older build, or null
     */
    public TranscriptStore(File file, Supplier<String> legacyReader) {
        this.file = file;
        this.legacyReader = legacyReader;
        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pideck-transcript");
            thread.setDaemon(true);
            return thread;
        });
    }

    public List<ConsoleEntry> load() {
        if (file.isFile()) {
            try {
                if (file.length() <= MAX_FILE_BYTES) {
                    return decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException | JSONException | IllegalArgumentException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return new ArrayList<>();
        }
        String legacy = legacyReader == null ? null : legacyReader.get();
        if (legacy == null) return new ArrayList<>();
        List<ConsoleEntry> migrated;
        try {
            migrated = decode(legacy);
        } catch (JSONException | IllegalArgumentException ignored) {
            return new ArrayList<>();
        }
        save(migrated);
        flush();
        return migrated;
    }

    /** Records the latest snapshot; it reaches disk once, after the coalescing delay. */
    public synchronized void save(List<ConsoleEntry> entries) {
        pending.set(List.copyOf(entries));
        if (scheduled == null || scheduled.isDone()) {
            scheduled = writer.schedule(this::writePending, WRITE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Writes any pending snapshot now, still off the calling thread. */
    public synchronized void flush() {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
        writer.execute(this::writePending);
    }

    /** Blocks until queued writes finish; for tests and orderly shutdown only. */
    boolean awaitIdle(long timeoutMs) throws InterruptedException {
        ScheduledFuture<?> marker = writer.schedule(() -> { }, 0, TimeUnit.MILLISECONDS);
        try {
            marker.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private void writePending() {
        List<ConsoleEntry> snapshot = pending.getAndSet(null);
        if (snapshot == null) return;
        byte[] encoded = encode(snapshot).getBytes(StandardCharsets.UTF_8);
        File parent = file.getParentFile();
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(encoded);
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        } catch (IOException ignored) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    static List<ConsoleEntry> decode(String raw) throws JSONException {
        ArrayList<ConsoleEntry> result = new ArrayList<>();
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            result.add(new ConsoleEntry(
                    ConsoleEntry.Channel.valueOf(item.getString("channel")),
                    item.getString("text"),
                    item.optLong("time", System.currentTimeMillis()),
                    item.optString("verb", ""),
                    item.optString("detail", ""),
                    item.optDouble("tokensPerSecond", Double.NaN),
                    item.optLong("outputTokens", -1L)
            ));
        }
        return result;
    }

    /** Newest entries first until the count or byte bound is met, written oldest first. */
    static String encode(List<ConsoleEntry> entries) {
        JSONArray array = new JSONArray();
        int start = Math.max(0, entries.size() - MAX_ENTRIES);
        ArrayList<JSONObject> bounded = new ArrayList<>();
        int totalBytes = 2;
        for (int i = entries.size() - 1; i >= start; i--) {
            ConsoleEntry entry = entries.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("channel", entry.channel.name());
                item.put("text", DeckPreferences.truncateUtf8(entry.text, MAX_ENTRY_BYTES));
                item.put("time", entry.time);
                if (!entry.verb.isEmpty()) item.put("verb", DeckPreferences.truncateUtf8(entry.verb, 64));
                if (!entry.detail.isEmpty()) {
                    item.put("detail", DeckPreferences.truncateUtf8(entry.detail, 256));
                }
                if (entry.hasExactSpeed()) {
                    item.put("tokensPerSecond", entry.tokensPerSecond);
                    item.put("outputTokens", entry.outputTokens);
                }
                int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (!bounded.isEmpty() && totalBytes + itemBytes > MAX_BYTES) break;
                bounded.add(item);
                totalBytes += itemBytes;
            } catch (JSONException ignored) {
            }
        }
        for (int i = bounded.size() - 1; i >= 0; i--) array.put(bounded.get(i));
        return array.toString();
    }
}
