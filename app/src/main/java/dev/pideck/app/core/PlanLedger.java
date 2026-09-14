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
        VERIFIED,
        BLOCKED;

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

        private Item(int step, String text, Status status) {
            this.step = step;
            this.text = text;
            this.status = status;
        }
    }

    public final Phase phase;
    public final String goal;
    public final List<Item> items;

    private PlanLedger(Phase phase, String goal, List<Item> items) {
        this.phase = phase == null ? Phase.IDLE : phase;
        this.goal = bounded(goal, MAX_GOAL_CHARS);
        this.items = List.copyOf(items == null ? List.of() : items);
    }

    public static PlanLedger empty() {
        return new PlanLedger(Phase.IDLE, "", List.of());
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
            items.add(new Item(
                    items.size() + 1,
                    text,
                    Status.fromWire(item.optString("status", "pending"))
            ));
        }
        return new PlanLedger(
                Phase.fromWire(value.optString("phase", "idle")),
                value.optString("goal", ""),
                items
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
            for (Item item : items) {
                encodedItems.put(new JSONObject()
                        .put("step", item.step)
                        .put("text", item.text)
                        .put("status", item.status.wireName()));
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
        for (Item item : items) if (item.status == Status.PENDING) return item.text;
        return "";
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }
}
