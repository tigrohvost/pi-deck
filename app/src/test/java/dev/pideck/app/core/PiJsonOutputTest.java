package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PiJsonOutputTest {
    @Test
    public void extractsToolTraceAndFinalAssistantText() {
        String stream = """
                {"type":"session","version":3}
                {"type":"tool_execution_start","toolName":"write","args":{"path":"hello.py"}}
                {"type":"tool_execution_end","toolName":"write","result":"ok","isError":false}
                {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Готово."}]}}
                """;

        PiJsonOutput.Parsed parsed = PiJsonOutput.parse(stream);
        assertTrue(parsed.recognized);
        assertEquals("Готово.", parsed.answer);
        assertEquals(1, parsed.traces.size());
        assertTrue(parsed.traces.get(0).text.contains("write"));
        assertFalse(parsed.traces.get(0).error);
    }

    @Test
    public void reportsFailedToolAndUsesAgentEndFallback() {
        String stream = """
                {"type":"tool_execution_end","toolName":"bash","result":"permission denied","isError":true}
                {"type":"agent_end","messages":[{"role":"user","content":"x"},{"role":"assistant","content":"Не удалось."}]}
                """;

        PiJsonOutput.Parsed parsed = PiJsonOutput.parse(stream);
        assertEquals("Не удалось.", parsed.answer);
        assertEquals(1, parsed.traces.size());
        assertTrue(parsed.traces.get(0).error);
    }

    @Test
    public void toleratesNoiseAndTruncatedLeadingLine() {
        String stream = """
                not-json
                truncated-prefix {"type":"agent_start"}
                {"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Финал"}]}}
                """;

        PiJsonOutput.Parsed parsed = PiJsonOutput.parse(stream);
        assertTrue(parsed.recognized);
        assertEquals("Финал", parsed.answer);
        assertEquals(2, parsed.protocolErrors.size());
    }

    @Test
    public void preservesLiteralBackslashNInToolArguments() {
        String stream = """
                {"type":"tool_execution_start","toolName":"grep","args":"literal\\\\nmarker"}
                """;
        PiJsonOutput.Parsed parsed = PiJsonOutput.parse(stream);
        assertEquals(1, parsed.traces.size());
        assertTrue(parsed.traces.get(0).text.contains("literal\\nmarker"));
        assertFalse(parsed.traces.get(0).text.contains("literal\nmarker"));
    }
}
