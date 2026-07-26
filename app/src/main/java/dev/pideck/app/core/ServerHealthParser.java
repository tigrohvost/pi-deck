package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Exact llama.cpp health/model parsing; raw substring matches are forbidden. */
public final class ServerHealthParser {
    private ServerHealthParser() {
    }

    public static boolean isReady(String healthJson, String modelsJson, String expectedModelId) {
        if (expectedModelId == null || expectedModelId.isBlank()) return false;
        try {
            JSONObject health = new JSONObject(healthJson);
            if (!"ok".equals(health.getString("status"))) return false;
            JSONObject models = new JSONObject(modelsJson);
            JSONArray data = models.getJSONArray("data");
            int exact = 0;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item != null && expectedModelId.equals(item.optString("id"))) exact++;
            }
            return exact == 1;
        } catch (JSONException | RuntimeException ignored) {
            return false;
        }
    }
}
