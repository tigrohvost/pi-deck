package dev.pideck.app.core;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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

        void onComplete(boolean valid, String actualHash, String error);
    }

    private final Context context;
    private final DownloadManager downloads;
    private final DeckPreferences prefs;

    public ModelDownloadManager(Context context, DeckPreferences prefs) {
        this.context = context.getApplicationContext();
        this.downloads = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = prefs;
    }

    public long start(ModelSpec model) {
        State current = state(model);
        if (current.isActive()) return prefs.downloadId(model.id);
        long previousId = prefs.downloadId(model.id);
        if (previousId >= 0) {
            downloads.remove(previousId);
            prefs.clearDownloadId(model.id);
        }

        File target = fileFor(model);
        if (target.exists() && target.length() != model.bytes) {
            // Exact app-managed target only; a failed DownloadManager transfer can leave a partial file.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(model.downloadUrl()))
                .setTitle("PI//DECK · " + model.title)
                .setDescription(model.tier + " GGUF · " + model.humanSize())
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "PiDeck/models/" + model.fileName
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
        cancel(model);
        File file = fileFor(model);
        return !file.exists() || file.delete();
    }

    public State state(ModelSpec model) {
        File target = fileFor(model);
        if (target.isFile() && target.length() == model.bytes) {
            return new State(Phase.COMPLETE, model.bytes, model.bytes, 0);
        }

        long id = prefs.downloadId(model.id);
        if (id < 0) return new State(Phase.MISSING, 0, model.bytes, 0);
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return new State(Phase.MISSING, 0, model.bytes, 0);
            }
            int rawStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            );
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            );
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            Phase phase = switch (rawStatus) {
                case DownloadManager.STATUS_PENDING -> Phase.QUEUED;
                case DownloadManager.STATUS_RUNNING -> Phase.RUNNING;
                case DownloadManager.STATUS_PAUSED -> Phase.PAUSED;
                case DownloadManager.STATUS_SUCCESSFUL -> Phase.COMPLETE;
                case DownloadManager.STATUS_FAILED -> Phase.FAILED;
                default -> Phase.MISSING;
            };
            return new State(phase, downloaded, total > 0 ? total : model.bytes, reason);
        } catch (RuntimeException ignored) {
            return new State(Phase.MISSING, 0, model.bytes, 0);
        }
    }

    public boolean isDownloaded(ModelSpec model) {
        return state(model).phase == Phase.COMPLETE;
    }

    public File fileFor(ModelSpec model) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloadsDir, "PiDeck/models/" + model.fileName);
    }

    public void verifyAsync(ModelSpec model, VerifyListener listener) {
        Thread thread = new Thread(() -> {
            File file = fileFor(model);
            long downloadId = prefs.downloadId(model.id);
            if (!file.isFile() && downloadId < 0) {
                listener.onComplete(false, "", "GGUF-файл не найден");
                return;
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = Math.max(1, model.bytes);
                long read = 0;
                int lastPercent = -1;
                byte[] buffer = new byte[4 * 1024 * 1024];
                ParcelFileDescriptor descriptor = downloadId >= 0
                        ? downloads.openDownloadedFile(downloadId)
                        : null;
                try (FileInputStream input = descriptor != null
                        ? new ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        : new FileInputStream(file)) {
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
                String actual = hex(digest.digest());
                listener.onComplete(model.sha256.equalsIgnoreCase(actual), actual, "");
            } catch (IOException | NoSuchAlgorithmException error) {
                listener.onComplete(false, "", error.getMessage());
            }
        }, "pideck-gguf-verify");
        thread.setDaemon(true);
        thread.start();
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
