package dev.pideck.app.core;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OperationStore {
    static final int MAX_COMPLETED = 100;
    static final long MAX_COMPLETED_BYTES = 20L * 1024L * 1024L;
    static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int MAX_RECORD_BYTES = 1024 * 1024;
    private static final Object FILE_LOCK = new Object();

    private final File directory;

    public OperationStore(Context context) {
        this(new File(context.getFilesDir(), "operations"));
    }

    public OperationStore(File directory) {
        this.directory = directory;
        ensureDirectory();
    }

    public OperationRecord create(OperationKind kind, JSONObject request) {
        synchronized (FILE_LOCK) {
            OperationRecord record = OperationRecord.create(
                    OperationId.create(), kind, request, System.currentTimeMillis()
            );
            write(record);
            return record;
        }
    }

    public OperationRecord save(OperationRecord record) {
        synchronized (FILE_LOCK) {
            write(record);
            return record;
        }
    }

    public OperationRecord load(OperationId id) {
        synchronized (FILE_LOCK) {
            return read(fileFor(id));
        }
    }

    public OperationRecord transition(OperationId id, OperationState next) {
        synchronized (FILE_LOCK) {
            OperationRecord current = require(id);
            OperationRecord updated = current.transition(next, System.currentTimeMillis());
            write(updated);
            return updated;
        }
    }

    public OperationRecord fail(OperationId id, String error) {
        synchronized (FILE_LOCK) {
            OperationRecord current = require(id);
            OperationRecord updated = current.withError(error, System.currentTimeMillis());
            write(updated);
            return updated;
        }
    }

    public OperationRecord recordResult(CommandResult result) {
        synchronized (FILE_LOCK) {
            OperationRecord current = read(fileFor(result.operationId));
            if (current == null) {
                current = OperationRecord.create(
                        result.operationId,
                        result.kind,
                        new JSONObject(),
                        System.currentTimeMillis()
                ).transition(OperationState.DISPATCHED, System.currentTimeMillis());
            }
            if (current.state.isTerminal()) return current;
            OperationRecord updated = current.withResult(result, System.currentTimeMillis());
            write(updated);
            prune();
            return updated;
        }
    }

    public void markConsumed(OperationId id) {
        synchronized (FILE_LOCK) {
            OperationRecord current = read(fileFor(id));
            if (current == null || current.uiConsumed) return;
            write(current.consumed(System.currentTimeMillis()));
            prune();
        }
    }

    public List<OperationRecord> list() {
        synchronized (FILE_LOCK) {
            ArrayList<OperationRecord> records = new ArrayList<>();
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return records;
            for (File file : files) {
                OperationRecord record = read(file);
                if (record != null) records.add(record);
            }
            records.sort(Comparator.comparingLong(item -> item.createdAtMs));
            return records;
        }
    }

    public List<OperationRecord> unconsumedTerminal() {
        ArrayList<OperationRecord> result = new ArrayList<>();
        for (OperationRecord record : list()) {
            if (record.state.isTerminal() && !record.uiConsumed && record.result != null) {
                result.add(record);
            }
        }
        return result;
    }

    public OperationRecord latestActive() {
        OperationRecord latest = null;
        for (OperationRecord record : list()) {
            if (record.state.isTerminal()) continue;
            if (record.kind == OperationKind.ABORT_AGENT
                    || record.kind == OperationKind.RECONCILE) {
                continue;
            }
            if (latest == null || record.updatedAtMs > latest.updatedAtMs) latest = record;
        }
        return latest;
    }

    private OperationRecord require(OperationId id) {
        OperationRecord value = read(fileFor(id));
        if (value == null) throw new IllegalStateException("Operation not found: " + id);
        return value;
    }

    private void write(OperationRecord record) {
        ensureDirectory();
        File target = fileFor(record.operationId);
        File temp = new File(directory, target.getName() + ".tmp");
        byte[] content;
        try {
            content = record.toJson(MAX_OUTPUT_BYTES).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not serialize operation", error);
        }
        if (content.length > MAX_RECORD_BYTES) {
            throw new IllegalStateException("Operation record exceeds bounded size");
        }
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(content);
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            throw new IllegalStateException("Could not write operation", error);
        }
        try {
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not commit operation", error);
        }
    }

    private OperationRecord read(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_RECORD_BYTES) return null;
        byte[] raw = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < raw.length) {
                int count = input.read(raw, offset, raw.length - offset);
                if (count < 0) return null;
                offset += count;
            }
            return OperationRecord.fromJson(
                    new JSONObject(new String(raw, StandardCharsets.UTF_8))
            );
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            // A damaged operation is isolated to its own file and never hides the other history.
            return null;
        }
    }

    private void prune() {
        List<OperationRecord> terminal = new ArrayList<>();
        long totalBytes = 0L;
        for (OperationRecord record : list()) {
            if (!record.state.isTerminal()) continue;
            terminal.add(record);
            totalBytes += fileFor(record.operationId).length();
        }
        terminal.sort(Comparator.comparingLong(item -> item.updatedAtMs));
        int index = 0;
        while ((terminal.size() - index > MAX_COMPLETED || totalBytes > MAX_COMPLETED_BYTES)
                && index < terminal.size()) {
            File target = fileFor(terminal.get(index++).operationId);
            long length = target.length();
            if (target.delete()) totalBytes -= length;
        }
    }

    private File fileFor(OperationId id) {
        return new File(directory, id + ".json");
    }

    private void ensureDirectory() {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Could not create operation store");
        }
    }
}
