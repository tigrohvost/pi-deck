package dev.pideck.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.pideck.app.ui.ConsoleEntry;

public final class DeckPreferences {
    static final int MAX_TRANSCRIPT_ENTRIES = 60;
    static final int MAX_TRANSCRIPT_BYTES = 256 * 1024;
    static final int MAX_TRANSCRIPT_ENTRY_BYTES = 32 * 1024;
    private static final String NAME = "pi_deck";
    private static final String KEY_MODEL = "selected_model";
    private static final String KEY_CORE_READY = "core_ready";
    private static final String KEY_HAS_SESSION = "has_session";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final String KEY_COLOR_SCHEME = "color_scheme";
    private static final String KEY_TEXT_SCALE = "text_scale";
    private static final String KEY_ACTIVE_TAB = "active_tab";
    private static final String KEY_ACCESS_PROFILE = "access_profile_v1";
    private static final String KEY_SESSION_ID = "session_id_v1";
    private static final String KEY_BRIDGE_INSTANCE = "bridge_instance";
    private static final String KEY_BRIDGE_SEQUENCE = "bridge_sequence";

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

    public void clearSelectedModelId() {
        prefs.edit().remove(KEY_MODEL).apply();
    }

    public String colorScheme() {
        return prefs.getString(KEY_COLOR_SCHEME, null);
    }

    public void setColorScheme(String scheme) {
        prefs.edit().putString(KEY_COLOR_SCHEME, scheme).apply();
    }

    /** Multiplier applied to every text size in the deck; 1.0 unless CORE changed it. */
    public float textScale() {
        return prefs.getFloat(KEY_TEXT_SCALE, 1f);
    }

    public void setTextScale(float scale) {
        prefs.edit().putFloat(KEY_TEXT_SCALE, scale).apply();
    }

    /** Which root the deck reopens on. */
    public int activeTab() {
        return prefs.getInt(KEY_ACTIVE_TAB, 0);
    }

    public void setActiveTab(int tab) {
        prefs.edit().putInt(KEY_ACTIVE_TAB, tab).apply();
    }

    public AccessProfile accessProfile() {
        return AccessProfile.fromWireName(prefs.getString(KEY_ACCESS_PROFILE, null));
    }

    public void setAccessProfile(AccessProfile profile) {
        prefs.edit().putString(KEY_ACCESS_PROFILE, profile.wireName()).apply();
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

    public String sessionId() {
        return prefs.getString(KEY_SESSION_ID, null);
    }

    public String ensureSessionId() {
        String existing = sessionId();
        if (existing != null) return existing;
        String created = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(KEY_SESSION_ID, created).apply();
        return created;
    }

    public void setSessionId(String id, boolean hasMessages) {
        OperationId.parse(id);
        prefs.edit()
                .putString(KEY_SESSION_ID, id)
                .putBoolean(KEY_HAS_SESSION, hasMessages)
                .apply();
    }

    public String startNewSession() {
        String id = java.util.UUID.randomUUID().toString();
        prefs.edit()
                .putString(KEY_SESSION_ID, id)
                .putBoolean(KEY_HAS_SESSION, false)
                .apply();
        return id;
    }

    public String bridgeInstanceId() {
        return prefs.getString(KEY_BRIDGE_INSTANCE, null);
    }

    public long bridgeSequence() {
        return prefs.getLong(KEY_BRIDGE_SEQUENCE, 0L);
    }

    public void setBridgeCursor(String instanceId, long sequence) {
        prefs.edit()
                .putString(KEY_BRIDGE_INSTANCE, instanceId)
                .putLong(KEY_BRIDGE_SEQUENCE, Math.max(0L, sequence))
                .apply();
    }

    public void clearBridgeCursor() {
        prefs.edit().remove(KEY_BRIDGE_INSTANCE).remove(KEY_BRIDGE_SEQUENCE).apply();
    }

    public long downloadId(String modelId) {
        return prefs.getLong("download_" + modelId, -1L);
    }

    public void setDownloadId(String modelId, long id) {
        prefs.edit().putLong("download_" + modelId, id).apply();
    }

    public void clearDownloadId(String modelId) {
        prefs.edit()
                .remove("download_" + modelId)
                .remove("download_uri_" + modelId)
                .apply();
    }

    public String downloadUri(String modelId) {
        return prefs.getString("download_uri_" + modelId, null);
    }

    public void setDownloadUri(String modelId, String uri) {
        if (uri == null || uri.isBlank()) return;
        prefs.edit().putString("download_uri_" + modelId, uri).apply();
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

    public boolean isPrivateModelInstalled(ModelSpec model) {
        return model.sha256.equalsIgnoreCase(
                prefs.getString("private_model_" + model.id, "")
        );
    }

    public void setPrivateModelInstalled(ModelSpec model, boolean installed) {
        SharedPreferences.Editor editor = prefs.edit();
        if (installed) editor.putString("private_model_" + model.id, model.sha256);
        else editor.remove("private_model_" + model.id);
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
                        item.optLong("time", System.currentTimeMillis()),
                        item.optString("verb", ""),
                        item.optString("detail", "")
                ));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            prefs.edit().remove(KEY_TRANSCRIPT).apply();
        }
        return result;
    }

    public void saveTranscript(List<ConsoleEntry> entries) {
        JSONArray array = new JSONArray();
        int start = Math.max(0, entries.size() - MAX_TRANSCRIPT_ENTRIES);
        ArrayList<JSONObject> bounded = new ArrayList<>();
        int totalBytes = 2;
        for (int i = entries.size() - 1; i >= start; i--) {
            ConsoleEntry entry = entries.get(i);
            try {
                JSONObject item = new JSONObject();
                item.put("channel", entry.channel.name());
                item.put("text", truncateUtf8(entry.text, MAX_TRANSCRIPT_ENTRY_BYTES));
                item.put("time", entry.time);
                if (!entry.verb.isEmpty()) item.put("verb", truncateUtf8(entry.verb, 64));
                if (!entry.detail.isEmpty()) item.put("detail", truncateUtf8(entry.detail, 256));
                int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (!bounded.isEmpty() && totalBytes + itemBytes > MAX_TRANSCRIPT_BYTES) break;
                bounded.add(item);
                totalBytes += itemBytes;
            } catch (JSONException ignored) {
            }
        }
        for (int i = bounded.size() - 1; i >= 0; i--) array.put(bounded.get(i));
        prefs.edit().putString(KEY_TRANSCRIPT, array.toString()).apply();
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty()) return "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int size = value.substring(0, middle).getBytes(StandardCharsets.UTF_8).length;
            if (size <= maxBytes - 24) low = middle;
            else high = middle - 1;
        }
        return value.substring(0, low) + "\n[entry truncated]";
    }
}
