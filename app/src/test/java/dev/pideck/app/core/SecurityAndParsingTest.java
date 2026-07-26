package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class SecurityAndParsingTest {
    @Test
    public void accessProfilesAreDenyByDefaultAndDoNotDiscoverExtensions() {
        assertEquals(AccessProfile.READ_ONLY, AccessProfile.fromWireName(null));
        assertEquals(AccessProfile.READ_ONLY, AccessProfile.fromWireName("future-profile"));
        assertEquals(
                List.of("--tools", "read,grep,find,ls"),
                AccessProfile.READ_ONLY.piArguments("/permission.ts")
        );
        assertTrue(AccessProfile.CONFIRM_CHANGES.piArguments("/permission.ts")
                .contains("/permission.ts"));
        assertFalse(AccessProfile.READ_ONLY.piArguments("/permission.ts").contains("bash"));
    }

    @Test
    public void exactHealthRejectsSubstringDuplicateAndMalformedResponses() {
        String healthy = "{\"status\":\"ok\"}";
        assertTrue(ServerHealthParser.isReady(
                healthy,
                "{\"data\":[{\"id\":\"qwen3.5-4b\"}]}",
                "qwen3.5-4b"
        ));
        assertFalse(ServerHealthParser.isReady(
                healthy,
                "{\"data\":[{\"id\":\"qwen3.5-4b-evil\"}]}",
                "qwen3.5-4b"
        ));
        assertFalse(ServerHealthParser.isReady(
                healthy,
                "{\"data\":[{\"id\":\"qwen3.5-4b\"},{\"id\":\"qwen3.5-4b\"}]}",
                "qwen3.5-4b"
        ));
        assertFalse(ServerHealthParser.isReady(
                "{\"status\":\"loading\"}",
                "{\"data\":[{\"id\":\"qwen3.5-4b\"}]}",
                "qwen3.5-4b"
        ));
        assertFalse(ServerHealthParser.isReady(healthy, "not-json", "qwen3.5-4b"));
    }

    @Test
    public void bridgeEventRequiresExactOperationIdAndMonotonicSequence() throws Exception {
        OperationId id = OperationId.create();
        JSONObject event = new JSONObject()
                .put("schemaVersion", 1)
                .put("sequence", 7)
                .put("bridgeInstanceId", "bridge-fixture")
                .put("operationId", id.toString())
                .put("sessionId", JSONObject.NULL)
                .put("type", "TURN_COMPLETED")
                .put("timestamp", "2026-07-26T00:00:00Z")
                .put("payload", new JSONObject());
        BridgeEvent parsed = BridgeEvent.parse(event);
        assertEquals(id, parsed.operationId);
        assertEquals(7, parsed.sequence);
        assertThrows(JSONException.class, () -> BridgeEvent.parse(
                new JSONObject(event.toString()).put("sequence", 0)
        ));
        assertThrows(IllegalArgumentException.class, () -> BridgeEvent.parse(
                new JSONObject(event.toString())
                        .put("operationId", id.toString().toUpperCase())
        ));
    }

    @Test
    public void transcriptTruncationHonorsUtf8Boundary() {
        String input = "🙂".repeat(100);
        String bounded = DeckPreferences.truncateUtf8(input, 100);
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 100);
        assertTrue(bounded.endsWith("[entry truncated]"));
        assertFalse(bounded.contains("\uFFFD"));
    }

    @Test
    public void rpcClientRejectsNonCanonicalBridgeTokens() {
        assertThrows(IllegalArgumentException.class, () -> new RpcBridgeClient("short"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcBridgeClient("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcBridgeClient("+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        );
    }
}
