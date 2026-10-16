package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlanLedgerTest {
    @Test
    public void malformedOrUnknownLedgerFailsClosedToIdle() throws Exception {
        assertEquals(PlanLedger.Phase.IDLE, PlanLedger.parse("not json").phase);
        assertEquals(
                PlanLedger.Phase.IDLE,
                PlanLedger.parse(new JSONObject().put("schemaVersion", 2)).phase
        );
        assertFalse(PlanLedger.empty().visible());
    }

    @Test
    public void parserBoundsAndRenumbersUntrustedItems() throws Exception {
        JSONArray items = new JSONArray();
        for (int index = 0; index < 12; index++) {
            items.put(new JSONObject()
                    .put("step", 99)
                    .put("text", "step " + index + " " + "x".repeat(300))
                    .put("status", index == 0 ? "verified" : "unknown"));
        }
        PlanLedger ledger = PlanLedger.parse(new JSONObject()
                .put("schemaVersion", 1)
                .put("phase", "executing")
                .put("goal", "repair parser")
                .put("items", items));

        assertEquals(PlanLedger.MAX_ITEMS, ledger.items.size());
        assertEquals(1, ledger.items.get(0).step);
        assertEquals(PlanLedger.MAX_ITEMS, ledger.items.get(6).step);
        assertEquals(PlanLedger.MAX_ITEM_CHARS, ledger.items.get(0).text.length());
        assertEquals(PlanLedger.Status.DONE_UNVERIFIED, ledger.items.get(0).status);
        assertEquals(PlanLedger.Status.PENDING, ledger.items.get(1).status);
        assertEquals(0, ledger.verifiedCount());
        assertTrue(ledger.visible());
    }

    @Test
    public void verifiedEvidenceSurvivesRoundTripButRequiresSnapshot() throws Exception {
        JSONObject item = new JSONObject().put("text", "run parser tests").put("status", "verified")
                .put("verificationKind", "test").put("snapshotId", "a".repeat(64))
                .put("evidenceIds", new JSONArray().put("11111111-2222-3333-4444-555555555555"));
        JSONObject value = new JSONObject().put("schemaVersion", 1).put("phase", "complete")
                .put("revision", "11111111-2222-3333-4444-555555555555")
                .put("items", new JSONArray().put(item));
        PlanLedger ledger = PlanLedger.parse(value);
        assertEquals(PlanLedger.Phase.COMPLETE, ledger.phase);
        assertEquals(1, ledger.verifiedCount());
        assertEquals(ledger.toJson().toString(), PlanLedger.parse(ledger.toJson()).toJson().toString());
        item.remove("snapshotId");
        assertEquals(PlanLedger.Phase.UNVERIFIED, PlanLedger.parse(value).phase);
        item.put("status", "done_unverified");
        assertEquals(0, PlanLedger.parse(value).verifiedCount());
    }

    @Test
    public void activeOrBlockedItemIsTheCurrentPhoneSummary() throws Exception {
        PlanLedger ledger = PlanLedger.parse(new JSONObject()
                .put("schemaVersion", 1)
                .put("phase", "blocked")
                .put("goal", "repair")
                .put("items", new JSONArray()
                        .put(new JSONObject().put("text", "inspect code").put("status", "verified"))
                        .put(new JSONObject().put("text", "repair code").put("status", "blocked"))));
        assertEquals("repair code", ledger.currentItem());
        assertEquals(ledger.toJson().toString(), PlanLedger.parse(ledger.toJson()).toJson().toString());
    }
}
