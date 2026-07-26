package dev.pideck.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dev.pideck.app.ui.ConsoleEntry;

public final class DeckPreferences {
    private static final String NAME = "pi_deck";
    private static final String KEY_MODEL = "selected_model";
    private static final String KEY_CORE_READY = "core_ready";
    private static final String KEY_HAS_SESSION = "has_session";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final String KEY_PENDING_RESULT = "pending_result";

    private final SharedPreferences prefs;

    public DeckPreferences(Context context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String selectedModelId() {
        return prefs.getString(KEY_MODEL, null);
    }

    public void setSelectedModelId(String id) {
        prefs.edit().putString(KEY_MODEL, id).apply();
    }

    public boolean isCoreReady() {
        return prefs.getBoolean(KEY_CORE_READY, false);
    }

    public void setCoreReady(boolean ready) {
        prefs.edit().putBoolean(KEY_CORE_READY, ready).apply();
    }

    public boolean hasSession() {
        return prefs.getBoolean(KEY_HAS_SESSION, false);
    }

    public void setHasSession(boolean hasSession) {
        prefs.edit().putBoolean(KEY_HAS_SESSION, hasSession).apply();
    }

    public long downloadId(String modelId) {
        return prefs.getLong("download_" + modelId, -1L);
    }

    public void setDownloadId(String modelId, long id) {
        prefs.edit().putLong("download_" + modelId, id).apply();
    }

    public void clearDownloadId(String modelId) {
        prefs.edit().remove("download_" + modelId).apply();
    }

    public boolean isModelVerified(ModelSpec model) {
        return model.sha256.equalsIgnoreCase(
                prefs.getString("verified_" + model.id, "")
        );
    }

    public void setModelVerified(ModelSpec model, boolean verified) {
        SharedPreferences.Editor editor = prefs.edit();
        if (verified) {
            editor.putString("verified_" + model.id, model.sha256);
        } else {
            editor.remove("verified_" + model.id);
        }
        editor.apply();
    }

    public List<ConsoleEntry> loadTranscript() {
        ArrayList<ConsoleEntry> result = new ArrayList<>();
        String raw = prefs.getString(KEY_TRANSCRIPT, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new ConsoleEntry(
                        ConsoleEntry.Channel.valueOf(item.getString("channel")),
                        item.getString("text"),
                        item.optLong("time", System.currentTimeMillis())
                ));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            prefs.edit().remove(KEY_TRANSCRIPT).apply();
        }
        return result;
    }

    public void saveTranscript(List<ConsoleEntry> entries) {
        JSONArray array = new JSONArray();
        int start = Math.max(0, entries.size() - 60);
        for (int i = start; i < entries.size(); i++) {
            ConsoleEntry entry = entries.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("channel", entry.channel.name());
                item.put("text", entry.text);
                item.put("time", entry.time);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_TRANSCRIPT, array.toString()).apply();
    }

    public void savePendingResult(CommandResult result) {
        try {
            JSONObject value = new JSONObject();
            value.put("requestId", result.requestId);
            value.put("stdout", result.stdout);
            value.put("stderr", result.stderr);
            value.put("exitCode", result.exitCode);
            value.put("errorCode", result.errorCode);
            value.put("errorMessage", result.errorMessage);
            prefs.edit().putString(KEY_PENDING_RESULT, value.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public CommandResult consumePendingResult() {
        String raw = prefs.getString(KEY_PENDING_RESULT, null);
        if (raw == null) return null;
        prefs.edit().remove(KEY_PENDING_RESULT).apply();
        try {
            JSONObject value = new JSONObject(raw);
            return new CommandResult(
                    value.optString("requestId"),
                    value.optString("stdout"),
                    value.optString("stderr"),
                    value.optInt("exitCode", -1),
                    value.optInt("errorCode", 0),
                    value.optString("errorMessage")
            );
        } catch (JSONException ignored) {
            return null;
        }
    }
}
