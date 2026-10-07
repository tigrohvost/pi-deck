package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ServerProgressTest {
    /** Verbatim b10092 lines captured on SM-S918B (2026-10-07). */
    private static final String PROMPT_LINE = "0.23.710.426 I slot print_timing: id  0 | task 0 | "
            + "prompt processing, n_tokens =    917, progress = 0.64, t =   9.79 s / 93.66 tokens per second";
    /** A later batch of the same task, 4.13 s further into its prompt. */
    private static final String PROMPT_LATER = "0.27.840.000 I slot print_timing: id  0 | task 0 | "
            + "prompt processing, n_tokens =   1300, progress = 0.91, t =  13.92 s / 93.39 tokens per second";
    private static final String PROMPT_DONE = "0.29.672.294 I slot print_timing: id  0 | task 0 | "
            + "prompt processing, n_tokens =   1429, progress = 1.00, t =  15.75 s / 90.71 tokens per second";
    private static final String DECODE_LINE = "0.31.004.120 I slot print_timing: id  0 | task 0 | "
            + "n_decoded =    120, tg =  15.20 t/s, tg_3s =  15.80 t/s";

    @Before
    public void setUp() {
        ServerProgress.reset();
    }

    @Test
    public void parsesThePinnedServersPromptAndDecodeLines() {
        ServerProgress prompt = ServerProgress.parse(PROMPT_LINE, 1_000L);
        assertNotNull(prompt);
        assertEquals(ServerProgress.Kind.PROMPT, prompt.kind);
        assertEquals(0L, prompt.taskId);
        assertEquals(917L, prompt.tokens);
        assertEquals(0.64d, prompt.fraction, 1e-9);
        assertEquals(9.79d, prompt.serverSeconds, 1e-9);
        assertEquals(93.66d, prompt.tokensPerSecond, 1e-9);

        ServerProgress decode = ServerProgress.parse(DECODE_LINE, 2_000L);
        assertNotNull(decode);
        assertEquals(ServerProgress.Kind.DECODE, decode.kind);
        assertEquals(120L, decode.tokens);
        assertEquals(15.2d, decode.tokensPerSecond, 1e-9);

        assertNull(ServerProgress.parse("srv  llama_server: model loaded", 0L));
        assertNull(ServerProgress.parse(null, 0L));
    }

    @Test
    public void theRateOfOneTaskDrivesTheEstimate() {
        ServerProgress first = ServerProgress.parse(PROMPT_LINE, 10_000L);
        assertEquals(-1L, first.remainingSeconds(10_000L));
        assertEquals(0.64d, first.estimatedFraction(60_000L), 1e-9);
        ServerProgress.publish(first);
        ServerProgress.publish(ServerProgress.parse(PROMPT_LATER, 14_000L));
        ServerProgress later = ServerProgress.latestSince(0L);
        // (0.91 - 0.64) / (13.92 - 9.79) s is about 0.0654 per second.
        assertEquals(0.0654d, later.fractionPerSecond, 1e-3);
        assertEquals(0.91d, later.estimatedFraction(14_000L), 1e-9);
        assertEquals(1L, later.remainingSeconds(14_000L));
        assertTrue(later.estimatedFraction(15_000L) > 0.91d);
        assertEquals(0.99d, later.estimatedFraction(600_000L), 1e-9);
    }

    @Test
    public void anotherTaskDoesNotInheritTheRate() {
        ServerProgress.publish(ServerProgress.parse(PROMPT_LINE, 10_000L));
        ServerProgress.publish(ServerProgress.parse(PROMPT_LATER.replace("task 0", "task 7"), 14_000L));
        assertTrue(Double.isNaN(ServerProgress.latestSince(0L).fractionPerSecond));
    }

    @Test
    public void aFullyReadPromptIsCompleteRatherThanStuckJustBelowIt() {
        ServerProgress done = ServerProgress.parse(PROMPT_DONE, 1_000L);
        assertTrue(done.promptComplete());
        assertEquals(1d, done.estimatedFraction(1_000L), 0d);
        assertFalse(ServerProgress.parse(PROMPT_LINE, 0L).promptComplete());
        assertFalse(ServerProgress.parse(DECODE_LINE, 0L).promptComplete());
    }

    @Test
    public void labelsReadInBothLanguages() {
        ServerProgress prompt = ServerProgress.parse(PROMPT_LINE, 10_000L);
        assertEquals("Reading prompt 64% · 94 tok/s", prompt.label(10_000L, UiLanguage.ENGLISH));
        ServerProgress.publish(prompt);
        ServerProgress.publish(ServerProgress.parse(PROMPT_LATER, 14_000L));
        assertEquals("Читаю промпт 91% · 93 ток/с · ~1 с",
                ServerProgress.latestSince(0L).label(14_000L, UiLanguage.RUSSIAN));
        ServerProgress decode = ServerProgress.parse(DECODE_LINE, 0L);
        assertTrue(decode.label(0L, UiLanguage.ENGLISH).startsWith("Thinking · 120 tok"));
    }

    @Test
    public void onlyObservationsFromTheCurrentTurnAreReported() {
        ServerProgress.publish(ServerProgress.parse(PROMPT_LINE, 5_000L));
        assertNull(ServerProgress.latestSince(6_000L));
        assertNotNull(ServerProgress.latestSince(5_000L));
    }

    @Test
    public void theTapPassesBytesThroughUnchangedAndPublishesProgress() throws IOException {
        String log = "load ok\n" + PROMPT_LINE + "\n" + "x".repeat(5_000) + "\n" + DECODE_LINE + "\n";
        byte[] raw = log.getBytes(StandardCharsets.UTF_8);
        long[] clock = {42L};
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        try (InputStream tap = new ServerProgress.Tap(new ByteArrayInputStream(raw), () -> clock[0])) {
            byte[] buffer = new byte[333];
            int read;
            while ((read = tap.read(buffer, 0, buffer.length)) >= 0) copy.write(buffer, 0, read);
        }
        assertEquals(log, copy.toString(StandardCharsets.UTF_8));
        ServerProgress latest = ServerProgress.latestSince(0L);
        assertNotNull(latest);
        assertEquals(ServerProgress.Kind.DECODE, latest.kind);
        assertEquals(42L, latest.observedAtMs);
        assertSame(latest, ServerProgress.latestSince(42L));
    }
}
