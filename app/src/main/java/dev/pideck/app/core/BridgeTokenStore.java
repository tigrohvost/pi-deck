package dev.pideck.app.core;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/** 256-bit bridge credential stored only in Android app-private files. */
public final class BridgeTokenStore {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_FILE_BYTES = 256;
    private final File tokenFile;
    private final SecureRandom random = new SecureRandom();

    public BridgeTokenStore(Context context) {
        tokenFile = new File(context.getFilesDir(), "bridge-token");
    }

    public synchronized String getOrCreate() {
        String existing = read();
        if (existing != null) return existing;
        return rotate();
    }

    public synchronized String rotate() {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        File temporary = new File(tokenFile.getParentFile(), tokenFile.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            throw new IllegalStateException("Could not write bridge token", error);
        }
        chmodPrivate(temporary);
        try {
            // Both files live in the same app-private directory. POSIX rename atomically replaces
            // the old credential, so readers can never observe a missing or partial token.
            Os.rename(temporary.getAbsolutePath(), tokenFile.getAbsolutePath());
        } catch (ErrnoException error) {
            throw new IllegalStateException("Could not atomically commit bridge token", error);
        }
        chmodPrivate(tokenFile);
        return token;
    }

    private String read() {
        if (!tokenFile.isFile() || tokenFile.length() != 43 || tokenFile.length() > MAX_FILE_BYTES) {
            return null;
        }
        byte[] raw = new byte[(int) tokenFile.length()];
        try (FileInputStream input = new FileInputStream(tokenFile)) {
            int offset = 0;
            while (offset < raw.length) {
                int count = input.read(raw, offset, raw.length - offset);
                if (count < 0) return null;
                offset += count;
            }
        } catch (IOException ignored) {
            return null;
        }
        String value = new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != TOKEN_BYTES) return null;
            String canonical = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(decoded);
            if (!canonical.equals(value)) return null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        chmodPrivate(tokenFile);
        return value;
    }

    private static void chmodPrivate(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0600);
        } catch (ErrnoException error) {
            throw new IllegalStateException("Could not protect bridge token", error);
        }
    }
}
