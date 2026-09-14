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
        assertEquals(PlanLedger.Status.VERIFIED, ledger.items.get(0).status);
        assertEquals(PlanLedger.Status.PENDING, ledger.items.get(1).status);
        assertEquals(1, ledger.verifiedCount());
        assertTrue(ledger.visible());
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
