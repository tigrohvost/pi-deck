package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded, non-secret plan progress mirrored from the authenticated bridge. */
public final class PlanLedger {
    public static final int MAX_ITEMS = 7;
    public static final int MAX_ITEM_CHARS = 200;
    public static final int MAX_GOAL_CHARS = 2048;

    public enum Phase {
        IDLE,
        PLANNING,
        PLANNED,
        EXECUTING,
        UNVERIFIED,
        BLOCKED,
        COMPLETE,
        CANCELLED;

        static Phase fromWire(String value) {
            if (value == null || value.isBlank()) return IDLE;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return IDLE;
            }
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Status {
        PENDING,
        ACTIVE,
        DONE_UNVERIFIED,
        VERIFIED,
        BLOCKED,
        CANCELLED;

        static Status fromWire(String value) {
            if (value == null || value.isBlank()) return PENDING;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return PENDING;
            }
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final class Item {
        public final int step;
        public final String text;
        public final Status status;
        public final String verificationKind;
        public final List<String> evidenceIds;
        public final String snapshotId;

        private Item(int step, String text, Status status, String verificationKind, List<String> evidenceIds, String snapshotId) {
            this.step = step;
            this.text = text;
            this.status = status;
            this.verificationKind = verificationKind;
            this.evidenceIds = List.copyOf(evidenceIds);
            this.snapshotId = snapshotId;
        }
    }

    public final Phase phase;
    public final String goal;
    public final List<Item> items;
    public final String reason;
    public final String revision;

    private PlanLedger(Phase phase, String goal, List<Item> items, String reason, String revision) {
        this.phase = phase == null ? Phase.IDLE : phase;
        this.goal = bounded(goal, MAX_GOAL_CHARS);
        this.items = List.copyOf(items == null ? List.of() : items);
        this.reason = bounded(reason, MAX_GOAL_CHARS);
        this.revision = bounded(revision, 128);
    }

    public static PlanLedger empty() {
        return new PlanLedger(Phase.IDLE, "", List.of(), "", "");
    }

    public static PlanLedger parse(JSONObject value) {
        if (value == null || value.optInt("schemaVersion", -1) != 1) return empty();
        ArrayList<Item> items = new ArrayList<>();
        JSONArray source = value.optJSONArray("items");
        for (int index = 0; source != null && index < source.length()
                && items.size() < MAX_ITEMS; index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            String text = bounded(item.optString("text", ""), MAX_ITEM_CHARS).trim();
            if (text.length() < 4) continue;
            Status status = Status.fromWire(item.optString("status", "pending"));
            ArrayList<String> evidence = new ArrayList<>();
            JSONArray ids = item.optJSONArray("evidenceIds");
            for (int i = 0; ids != null && i < Math.min(8, ids.length()); i++) {
                String id = ids.optString(i, "");
                if (id.matches("[0-9a-f-]{36}")) evidence.add(id);
            }
            String kind = item.optString("verificationKind", "");
            String snapshot = item.optString("snapshotId", "");
            if (!snapshot.matches("[0-9a-f]{64}")) snapshot = "";
            if (status == Status.VERIFIED && (evidence.isEmpty()
                    || snapshot.isEmpty()
                    || !List.of("test", "inspection", "artifact_check", "user_acceptance").contains(kind))) {
                status = Status.DONE_UNVERIFIED;
            }
            items.add(new Item(
                    items.size() + 1,
                    text,
                    status, kind, evidence, snapshot
            ));
        }
        Phase phase = Phase.fromWire(value.optString("phase", "idle"));
        if (phase == Phase.COMPLETE && (items.isEmpty()
                || items.stream().anyMatch(item -> item.status != Status.VERIFIED))) phase = Phase.UNVERIFIED;
        return new PlanLedger(
                phase,
                value.optString("goal", ""),
                items, value.optString("reason", ""), value.optString("revision", "")
        );
    }

    public static PlanLedger parse(String value) {
        if (value == null || value.isBlank()) return empty();
        try {
            return parse(new JSONObject(value));
        } catch (JSONException ignored) {
            return empty();
        }
    }

    public JSONObject toJson() {
        JSONObject result = new JSONObject();
        JSONArray encodedItems = new JSONArray();
        try {
            result.put("schemaVersion", 1);
            result.put("phase", phase.wireName());
            result.put("goal", goal);
            result.put("reason", reason);
            result.put("revision", revision);
            for (Item item : items) {
                encodedItems.put(new JSONObject()
                        .put("step", item.step)
                        .put("text", item.text)
                        .put("status", item.status.wireName())
                        .put("verificationKind", item.verificationKind)
                        .put("snapshotId", item.snapshotId)
                        .put("evidenceIds", new JSONArray(item.evidenceIds)));
            }
            result.put("items", encodedItems);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
        return result;
    }

    public boolean visible() {
        return phase != Phase.IDLE && (!goal.isBlank() || !items.isEmpty());
    }

    public int verifiedCount() {
        int result = 0;
        for (Item item : items) if (item.status == Status.VERIFIED) result++;
        return result;
    }

    public String currentItem() {
        for (Item item : items) {
            if (item.status == Status.ACTIVE || item.status == Status.BLOCKED) return item.text;
        }
        for (Item item : items) if (item.status == Status.PENDING || item.status == Status.DONE_UNVERIFIED) return item.text;
        return "";
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }
}
