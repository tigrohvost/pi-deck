package dev.pideck.app.core;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class ModelDownloadManager {
    public enum Phase {
        MISSING,
        QUEUED,
        RUNNING,
        PAUSED,
        COMPLETE,
        VERIFY_REQUIRED,
        FAILED
    }

    public static final class State {
        public final Phase phase;
        public final long downloadedBytes;
        public final long totalBytes;
        public final int reason;

        public State(Phase phase, long downloadedBytes, long totalBytes, int reason) {
            this.phase = phase;
            this.downloadedBytes = Math.max(0, downloadedBytes);
            this.totalBytes = totalBytes;
            this.reason = reason;
        }

        public int percent() {
            if (totalBytes <= 0) return 0;
            return (int) Math.min(100, downloadedBytes * 100L / totalBytes);
        }

        public boolean isActive() {
            return phase == Phase.QUEUED || phase == Phase.RUNNING || phase == Phase.PAUSED;
        }
    }

    public interface VerifyListener {
        void onProgress(int percent);

        void onComplete(VerifyResult result);
    }

    public enum VerificationFailure {
        NONE,
        MISSING,
        ACCESS_DENIED,
        INCOMPLETE,
        HASH_MISMATCH,
        IO
    }

    public static final class VerifyResult {
        public final boolean valid;
        public final String actualHash;
        public final String error;
        public final VerificationFailure failure;

        private VerifyResult(
                boolean valid,
                String actualHash,
                String error,
                VerificationFailure failure
        ) {
            this.valid = valid;
            this.actualHash = actualHash == null ? "" : actualHash;
            this.error = error == null ? "" : error;
            this.failure = failure;
        }

        static VerifyResult success(String actualHash) {
            return new VerifyResult(true, actualHash, "", VerificationFailure.NONE);
        }

        static VerifyResult failure(
                VerificationFailure failure,
                String actualHash,
                String error
        ) {
            return new VerifyResult(false, actualHash, error, failure);
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final DownloadManager downloads;
    private final DeckPreferences prefs;

    public ModelDownloadManager(Context context, DeckPreferences prefs) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.downloads = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = prefs;
    }

    public long start(ModelSpec model, boolean allowMetered) {
        State current = state(model);
        if (current.isActive()) return prefs.downloadId(model.id);
        long previousId = prefs.downloadId(model.id);
        if (previousId >= 0) {
            downloads.remove(previousId);
            prefs.clearDownloadId(model.id);
        }
        prefs.clearExternalModelUri(model.id);

        File target = fileFor(model);
        String relativeTarget = "PiDeck/incoming/" + incomingFileName(model);
        if (target.exists()) {
            // Exact app-managed target only, and only reached once the user confirmed a fresh
            // download. A partial transfer keeps its pre-allocated final length, so size cannot
            // tell the two apart; leaving the file behind would make DownloadManager write to
            // "<name>-1.gguf", a path llama-server never looks at.
            if (!target.delete()) {
                // A reinstall gets a new Linux UID. Scoped storage can leave the old public file
                // visible but undeletable, so use a new exact destination instead of blaming the
                // bytes or letting DownloadManager silently invent a "-1" suffix.
                relativeTarget = "PiDeck/incoming/"
                        + model.id + "-" + model.sha256.substring(0, 12)
                        + "-fresh-" + System.currentTimeMillis() + ".gguf";
                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                );
                target = new File(downloadsDir, relativeTarget);
            }
        }
        prefs.setDownloadUri(model.id, Uri.fromFile(target).toString());

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(model.downloadUrl()))
                .setTitle("PI//DECK · " + model.title)
                .setDescription(model.tier + " GGUF · " + model.humanSize())
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(allowMetered)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        relativeTarget
                );
        long id = downloads.enqueue(request);
        prefs.setDownloadId(model.id, id);
        return id;
    }

    public void cancel(ModelSpec model) {
        long id = prefs.downloadId(model.id);
        if (id >= 0) downloads.remove(id);
        prefs.clearDownloadId(model.id);
    }

    public boolean delete(ModelSpec model) {
        if (hasExternalDocument(model)) {
            detachExternalDocument(model);
            return false;
        }
        cancel(model);
        File file = fileFor(model);
        return !file.exists() || file.delete();
    }

    /**
     * DownloadManager pre-allocates the destination to its final length before it streams a single
     * byte (DownloadThread calls StorageManager#allocateBytes, which does posix_fallocate or
     * ftruncate), so the file size says nothing about progress. Its status column is the only
     * authority while a transfer is known; the file is a fallback for downloads it no longer
     * tracks, such as a model kept across a reinstall.
     */
    static Phase phaseOf(boolean hasDownloadRow, int rawStatus, boolean fileHasFinalLength) {
        if (hasDownloadRow) {
            switch (rawStatus) {
                case DownloadManager.STATUS_PENDING:
                    return Phase.QUEUED;
                case DownloadManager.STATUS_RUNNING:
                    return Phase.RUNNING;
                case DownloadManager.STATUS_PAUSED:
                    return Phase.PAUSED;
                case DownloadManager.STATUS_SUCCESSFUL:
                    return Phase.COMPLETE;
                case DownloadManager.STATUS_FAILED:
                    return Phase.FAILED;
                default:
                    break;
            }
        }
        return fileHasFinalLength ? Phase.VERIFY_REQUIRED : Phase.MISSING;
    }

    public State state(ModelSpec model) {
        File target = fileFor(model);
        boolean fileHasFinalLength = target.isFile() && target.length() == model.bytes;
        boolean documentHasFinalLength = externalDocumentLength(model) == model.bytes;
        boolean sourceHasFinalLength = fileHasFinalLength || documentHasFinalLength;
        long id = prefs.downloadId(model.id);
        if (id < 0) return untrackedState(model, sourceHasFinalLength);

        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return untrackedState(model, sourceHasFinalLength);
            }
            int rawStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            );
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            );
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            Phase phase = phaseOf(true, rawStatus, sourceHasFinalLength);
            if (phase == Phase.COMPLETE) {
                int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                if (uriIndex >= 0) prefs.setDownloadUri(model.id, cursor.getString(uriIndex));
            }
            return new State(
                    phase,
                    phase == Phase.COMPLETE ? model.bytes : downloaded,
                    total > 0 ? total : model.bytes,
                    reason
            );
        } catch (RuntimeException ignored) {
            return untrackedState(model, sourceHasFinalLength);
        }
    }

    private State untrackedState(ModelSpec model, boolean fileHasFinalLength) {
        Phase phase = phaseOf(false, 0, fileHasFinalLength);
        return new State(phase, phase == Phase.COMPLETE ? model.bytes : 0, model.bytes, 0);
    }

    public boolean isDownloaded(ModelSpec model) {
        Phase phase = state(model).phase;
        return phase == Phase.COMPLETE || phase == Phase.VERIFY_REQUIRED;
    }

    /**
     * Bytes a restarted download gets back, because {@link #start} drops the current target first.
     * A pre-allocated partial file already occupies the model's full size on disk.
     */
    public long reclaimableBytes(ModelSpec model) {
        if (hasExternalDocument(model)) return 0L;
        File target = fileFor(model);
        return prefs.downloadId(model.id) >= 0 && target.isFile() ? target.length() : 0L;
    }

    public File fileFor(ModelSpec model) {
        String stored = prefs.downloadUri(model.id);
        if (stored != null) {
            Uri uri = Uri.parse(stored);
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                return new File(uri.getPath());
            }
        }
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloadsDir, "PiDeck/incoming/" + incomingFileName(model));
    }

    public String sourcePath(ModelSpec model) {
        String external = prefs.externalModelUri(model.id);
        if (external != null) return external;
        return fileFor(model).getAbsolutePath();
    }

    public void attachExternalDocument(ModelSpec model, Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw new IOException("Android не выдал document URI");
        }
        long size = documentLength(uri);
        if (size != model.bytes) {
            throw new IOException("размер выбранного файла " + size
                    + " байт, ожидается " + model.bytes);
        }
        prefs.clearDownloadId(model.id);
        prefs.setExternalModelUri(model.id, uri.toString());
    }

    public boolean hasExternalDocument(ModelSpec model) {
        return prefs.externalModelUri(model.id) != null;
    }

    public void detachExternalDocument(ModelSpec model) {
        prefs.clearExternalModelUri(model.id);
    }

    /**
     * Opens the exact DownloadManager-owned bytes even under scoped storage.
     *
     * <p>The caller owns the returned stream. Falling back to a direct file is only for a
     * previously completed download whose DownloadManager row no longer exists.
     */
    public InputStream openForRead(ModelSpec model) throws IOException {
        String external = prefs.externalModelUri(model.id);
        if (external != null) {
            try {
                InputStream input = resolver.openInputStream(Uri.parse(external));
                if (input != null) return input;
                throw new FileNotFoundException("Android не открыл выбранный GGUF");
            } catch (SecurityException error) {
                throw accessDenied(error);
            }
        }
        long downloadId = prefs.downloadId(model.id);
        if (downloadId >= 0) {
            try {
                ParcelFileDescriptor descriptor = downloads.openDownloadedFile(downloadId);
                if (descriptor != null) {
                    return new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                }
            } catch (SecurityException error) {
                throw accessDenied(error);
            } catch (RuntimeException ignored) {
                // The durable incoming file below is still an eligible source.
            }
        }
        try {
            return new FileInputStream(fileFor(model));
        } catch (FileNotFoundException error) {
            if (isPermissionFailure(error)) throw accessDenied(error);
            throw error;
        } catch (SecurityException error) {
            throw accessDenied(error);
        }
    }

    public void verifyAsync(ModelSpec model, VerifyListener listener) {
        Thread thread = new Thread(() -> {
            File file = fileFor(model);
            long downloadId = prefs.downloadId(model.id);
            if (state(model).isActive()) {
                listener.onComplete(VerifyResult.failure(
                        VerificationFailure.IO, "", "загрузка ещё идёт"
                ));
                return;
            }
            if (!file.isFile() && downloadId < 0 && !hasExternalDocument(model)) {
                listener.onComplete(VerifyResult.failure(
                        VerificationFailure.MISSING, "", "GGUF-файл не найден"
                ));
                return;
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = Math.max(1, model.bytes);
                long read = 0;
                int lastPercent = -1;
                byte[] buffer = new byte[4 * 1024 * 1024];
                try (InputStream input = openForRead(model)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        digest.update(buffer, 0, count);
                        read += count;
                        int percent = (int) Math.min(100, read * 100L / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
                if (read != model.bytes) {
                    // A short read means an interrupted transfer, not a corrupted one: telling the
                    // user the checksum failed would send them off deleting a healthy file.
                    listener.onComplete(VerifyResult.failure(
                            VerificationFailure.INCOMPLETE,
                            "",
                            "файл неполный: " + read / 1_048_576L
                                    + " MB из " + model.bytes / 1_048_576L + " MB"
                    ));
                    return;
                }
                String actual = hex(digest.digest());
                if (model.sha256.equalsIgnoreCase(actual)) {
                    listener.onComplete(VerifyResult.success(actual));
                } else {
                    listener.onComplete(VerifyResult.failure(
                            VerificationFailure.HASH_MISMATCH, actual, ""
                    ));
                }
            } catch (IOException | NoSuchAlgorithmException error) {
                VerificationFailure failure = isPermissionFailure(error)
                        ? VerificationFailure.ACCESS_DENIED
                        : VerificationFailure.IO;
                listener.onComplete(VerifyResult.failure(failure, "", error.getMessage()));
            } catch (SecurityException error) {
                listener.onComplete(VerifyResult.failure(
                        VerificationFailure.ACCESS_DENIED, "", accessDenied(error).getMessage()
                ));
            }
        }, "pideck-gguf-verify");
        thread.setDaemon(false);
        thread.start();
    }

    private long externalDocumentLength(ModelSpec model) {
        String value = prefs.externalModelUri(model.id);
        if (value == null) return -1L;
        try {
            return documentLength(Uri.parse(value));
        } catch (IOException | SecurityException ignored) {
            return -1L;
        }
    }

    private long documentLength(Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Android не вернул метаданные выбранного GGUF");
            }
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeIndex < 0 || cursor.isNull(sizeIndex)) {
                throw new IOException("Android не сообщил размер выбранного GGUF");
            }
            return cursor.getLong(sizeIndex);
        } catch (SecurityException error) {
            throw accessDenied(error);
        }
    }

    private static boolean isPermissionFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("eacces")
                        || normalized.contains("permission denied")
                        || normalized.contains("access denied")
                        || normalized.contains("недостаточно прав")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return error instanceof SecurityException;
    }

    private static IOException accessDenied(Throwable cause) {
        return new IOException(
                "Android потерял доступ к общей копии после переустановки; "
                        + "выберите существующий GGUF через системный проводник",
                cause
        );
    }

    private static String incomingFileName(ModelSpec model) {
        return model.id + "-" + model.sha256.substring(0, 12) + ".gguf";
    }

    public static String failureLabel(int reason) {
        return switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE -> "недостаточно места";
            case DownloadManager.ERROR_CANNOT_RESUME -> "сервер не разрешил продолжение";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND -> "хранилище недоступно";
            case DownloadManager.ERROR_HTTP_DATA_ERROR -> "ошибка HTTP";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "слишком много перенаправлений";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "файл уже существует";
            default -> String.format(Locale.US, "код %d", reason);
        };
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }
}
