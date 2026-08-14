package dev.pideck.app.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class DiagnosticReportTest {
    @Test
    public void reportCannotLeakRequestsResultsOrUnknownFacts() throws Exception {
        OperationId id = OperationId.create();
        OperationRecord record = OperationRecord.create(
                id,
                OperationKind.AGENT_TURN,
                new JSONObject().put("prompt", "TOP SECRET PROMPT"),
                1_000L
        ).transition(OperationState.DISPATCHED, 1_001L).withResult(
                new CommandResult(
                        id,
                        OperationKind.AGENT_TURN,
                        "SECRET OUTPUT", "SECRET ERROR", 1, 0, "SECRET MESSAGE"
                ),
                1_002L
        );

        JSONObject report = DiagnosticReport.create(
                new JSONObject()
                        .put("appVersion", "test")
                        .put("bridgeToken", "SECRET TOKEN"),
                new JSONObject()
                        .put("python", "3.14.0")
                        .put("privatePackage", "SECRET PACKAGE"),
                List.of(record),
                2_000L
        );
        String encoded = report.toString();
        assertTrue(encoded.contains("NONZERO_EXIT"));
        assertTrue(encoded.contains("3.14.0"));
        assertFalse(encoded.contains("TOP SECRET"));
        assertFalse(encoded.contains("SECRET OUTPUT"));
        assertFalse(encoded.contains("SECRET ERROR"));
        assertFalse(encoded.contains("SECRET MESSAGE"));
        assertFalse(encoded.contains("SECRET TOKEN"));
        assertFalse(encoded.contains("SECRET PACKAGE"));
    }
}
