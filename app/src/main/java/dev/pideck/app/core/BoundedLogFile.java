package dev.pideck.app.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/** Streaming tail log used for native subprocesses that may stay alive for hours. */
public final class BoundedLogFile {
    private BoundedLogFile() {
    }

    public static void copy(
            InputStream input,
            File target,
            int maximumBytes,
            int retainBytes,
            boolean overwrite
    ) throws IOException {
        if (maximumBytes <= 0 || retainBytes < 0 || retainBytes >= maximumBytes) {
            throw new IllegalArgumentException("Invalid bounded log limits");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create log directory");
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream source = input; RandomAccessFile output = new RandomAccessFile(target, "rw")) {
            if (overwrite) output.setLength(0L);
            long size = output.length();
            output.seek(size);
            int count;
            while ((count = source.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (size + count > maximumBytes) {
                    int keep = (int) Math.min(retainBytes, output.length());
                    byte[] tail = new byte[keep];
                    output.seek(output.length() - keep);
                    output.readFully(tail);
                    output.seek(0L);
                    output.setLength(0L);
                    output.write(tail);
                    size = keep;
                    if (count > maximumBytes - size) {
                        int offset = count - (maximumBytes - (int) size);
                        output.write(buffer, offset, count - offset);
                        size += count - offset;
                        continue;
                    }
                }
                output.write(buffer, 0, count);
                size += count;
            }
            output.getFD().sync();
        }
    }
}
