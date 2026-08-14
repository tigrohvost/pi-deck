package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

public class BoundedLogFileTest {
    @Test
    public void oversizedStreamKeepsTheNewestBoundedTail() throws Exception {
        File directory = Files.createTempDirectory("pideck-log-test").toFile();
        File log = new File(directory, "native.log");
        byte[] content = ("old-" + "a".repeat(100) + "new-" + "b".repeat(300)).getBytes();
        BoundedLogFile.copy(new ByteArrayInputStream(content), log, 128, 32, true);
        byte[] stored = Files.readAllBytes(log.toPath());
        assertEquals(128, stored.length);
        assertTrue(new String(stored).endsWith("b".repeat(124)));
    }
}
