package dev.pideck.app.core;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** App-owned, read-only GGUF store used by the foreground native inference process. */
public final class NativeModelStore {
    public interface Listener {
        void onProgress(int percent);

        void onComplete(boolean valid, String error);
    }

    private final File root;
    private final DeckPreferences prefs;

    public NativeModelStore(Context context, DeckPreferences prefs) {
        root = new File(context.getFilesDir(), "models");
        this.prefs = prefs;
    }

    public boolean isInstalled(ModelSpec model) {
        File file = fileFor(model);
        return prefs.isPrivateModelInstalled(model)
                && file.isFile()
                && file.length() == model.bytes;
    }

    public File fileFor(ModelSpec model) {
        return new File(new File(root, model.id), model.fileName);
    }

    public long usableSpace() {
        File base = root.isDirectory() ? root : root.getParentFile();
        return base == null ? 0L : base.getUsableSpace();
    }

    public void installAsync(
            ModelSpec model,
            ModelDownloadManager downloads,
            Listener listener
    ) {
        Thread worker = new Thread(
                () -> install(model, downloads, listener),
                "pideck-native-model-install"
        );
        worker.setDaemon(false);
        worker.start();
    }

    private void install(
            ModelSpec model,
            ModelDownloadManager downloads,
            Listener listener
    ) {
        File directory = fileFor(model).getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            listener.onComplete(false, "не удалось создать приватную папку модели");
            return;
        }
        File destination = fileFor(model);
        File partial = new File(directory, model.fileName + ".partial");
        if (partial.exists() && !partial.delete()) {
            listener.onComplete(false, "не удалось заменить незавершённую приватную копию");
            return;
        }

        long copied = 0L;
        int lastPercent = -1;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[4 * 1024 * 1024];
            try (InputStream input = downloads.openForRead(model);
                 FileOutputStream output = new FileOutputStream(partial)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                    int percent = (int) Math.min(100, copied * 100L / model.bytes);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent);
                    }
                }
                output.getFD().sync();
            }
            if (copied != model.bytes) {
                throw new IOException(
                        "приватная копия неполная: " + copied + " из " + model.bytes + " байт"
                );
            }
            String actual = hex(digest.digest());
            if (!model.sha256.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 приватной копии не совпал");
            }
            try {
                Files.move(
                        partial.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("хранилище не поддерживает atomic rename", error);
            }
            try {
                Os.chmod(destination.getAbsolutePath(), 0400);
            } catch (ErrnoException error) {
                throw new IOException(
                        "не удалось сделать GGUF доступной только приложению",
                        error
                );
            }
            fsyncDirectory(directory);
            prefs.setPrivateModelInstalled(model, true);
            listener.onComplete(true, "");
        } catch (IOException | NoSuchAlgorithmException error) {
            prefs.setPrivateModelInstalled(model, false);
            listener.onComplete(false, safeMessage(error));
        } finally {
            if (partial.exists()) partial.delete();
        }
    }

    private static void fsyncDirectory(File directory) throws IOException {
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(
                    directory.getAbsolutePath(),
                    OsConstants.O_RDONLY,
                    0
            );
            Os.fsync(descriptor);
        } catch (ErrnoException error) {
            throw new IOException("не удалось синхронизировать каталог модели", error);
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
