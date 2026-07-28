package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AgentModeTest {
    @Test
    public void wireValuesAreStableAndUnknownValuesStaySafe() {
        assertEquals("chat", AgentMode.CHAT.wireName());
        assertEquals("agent", AgentMode.AGENT.wireName());
        assertEquals(AgentMode.CHAT, AgentMode.fromWireName("CHAT"));
        assertEquals(AgentMode.AGENT, AgentMode.fromWireName("future-mode"));
        assertEquals(AgentMode.AGENT, AgentMode.fromWireName(null));
    }
}
