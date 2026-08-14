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
    private static final String KEY_TERMUX_LINK_CONFIRMED = "termux_link_confirmed_v1";
    private static final String KEY_HAS_SESSION = "has_session";
    private static final String KEY_TRANSCRIPT = "transcript";
    private static final String KEY_COLOR_SCHEME = "color_scheme";
    private static final String KEY_TEXT_SCALE = "text_scale";
    private static final String KEY_ACTIVE_TAB = "active_tab";
    private static final String KEY_CONSENT = "shell_consent_v1";
    private static final String KEY_ASK_BEFORE_OVERWRITE = "ask_before_overwrite";
    private static final String KEY_ACCESS_PROFILE = "access_profile_v1";
    private static final String KEY_AUTONOMOUS_UNTIL = "autonomous_until_v1";
    private static final String KEY_SESSION_ID = "session_id_v1";
    private static final String KEY_BRIDGE_INSTANCE = "bridge_instance";
    private static final String KEY_BRIDGE_SEQUENCE = "bridge_sequence";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt_v1";
    private static final String KEY_SYSTEM_PROMPT_MODE = "system_prompt_mode_v1";
    private static final String KEY_AGENT_MODE = "agent_mode_v1";
    private static final String KEY_MAXIMUM_SPEED = "maximum_speed_v1";
    private static final String KEY_UI_LANGUAGE = "ui_language_v1";
    private static final String KEY_AUTOSTART_CORE = "autostart_core_v1";
    private static final String KEY_CORE_IDLE_TIMEOUT = "core_idle_timeout_v1";
    private static final String KEY_THERMAL_PACING = "thermal_pacing_v1";
    private static final String KEY_SMART_COMPACTION = "smart_compaction_v1";
    private static final String KEY_OOM_ACK = "oom_risk_ack_v1";
    private static final String KEY_RUNTIME_PACKAGES = "runtime_packages_v1";
    private static final String KEY_COMPOSER_DRAFT = "composer_draft_v1";
    private static final String KEY_QUEUED_PROMPT = "queued_prompt_v1";
    private static final int MAX_PROMPT_BYTES = 64 * 1024;

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

    /** Whether the user has been shown, and accepted, what the agent can do with the shell. */
    public boolean consentGranted() {
        return prefs.getBoolean(KEY_CONSENT, false);
    }

    public void setConsentGranted(boolean granted) {
        prefs.edit().putBoolean(KEY_CONSENT, granted).apply();
    }

    /** Ask before overwriting a file the agent did not create. Defaults to asking. */
    public boolean askBeforeOverwrite() {
        return prefs.getBoolean(KEY_ASK_BEFORE_OVERWRITE, true);
    }

    public void setAskBeforeOverwrite(boolean ask) {
        prefs.edit().putBoolean(KEY_ASK_BEFORE_OVERWRITE, ask).apply();
    }

    /** Which root the deck reopens on. */
    public int activeTab() {
        return prefs.getInt(KEY_ACTIVE_TAB, 0);
    }

    public void setActiveTab(int tab) {
        prefs.edit().putInt(KEY_ACTIVE_TAB, tab).apply();
    }

    /**
     * The default lives here and not in {@link AccessProfile#fromWireName(String)}, so an unknown
     * or corrupt stored value still resolves to {@code READ_ONLY} and cannot escalate privilege by
     * being unreadable.
     */
    public AccessProfile accessProfile() {
        AccessProfile stored = AccessProfile.fromWireName(prefs.getString(
                KEY_ACCESS_PROFILE, AccessProfile.shippedDefault().wireName()
        ));
        if (stored != AccessProfile.AUTONOMOUS) return stored;
        if (AutonomousGrant.isActive(autonomousUntilMs(), System.currentTimeMillis())) {
            return stored;
        }
        // A legacy indefinite AUTONOMOUS value has no expiry and is downgraded on first read.
        prefs.edit()
                .putString(KEY_ACCESS_PROFILE, AccessProfile.CONFIRM_CHANGES.wireName())
                .remove(KEY_AUTONOMOUS_UNTIL)
                .apply();
        return AccessProfile.CONFIRM_CHANGES;
    }

    public void setAccessProfile(AccessProfile profile) {
        if (profile == AccessProfile.AUTONOMOUS) {
            throw new IllegalArgumentException("AUTONOMOUS requires a bounded grant");
        }
        prefs.edit()
                .putString(KEY_ACCESS_PROFILE, profile.wireName())
                .remove(KEY_AUTONOMOUS_UNTIL)
                .apply();
    }

    public void grantAutonomous(long nowMs) {
        prefs.edit()
                .putString(KEY_ACCESS_PROFILE, AccessProfile.AUTONOMOUS.wireName())
                .putLong(KEY_AUTONOMOUS_UNTIL, AutonomousGrant.newExpiry(nowMs))
                .apply();
    }

    public long autonomousUntilMs() {
        return prefs.getLong(KEY_AUTONOMOUS_UNTIL, 0L);
    }

    public AgentMode agentMode() {
        return AgentMode.fromWireName(prefs.getString(KEY_AGENT_MODE, null));
    }

    public void setAgentMode(AgentMode mode) {
        prefs.edit().putString(
                KEY_AGENT_MODE,
                (mode == null ? AgentMode.AGENT : mode).wireName()
        ).apply();
    }

    /**
     * Keeping the deck visible avoids Android demoting local inference. Enabled by default because
     * this is a local-compute application; the setting is exposed next to the model selector.
     */
    public boolean maximumSpeed() {
        return prefs.getBoolean(KEY_MAXIMUM_SPEED, true);
    }

    public void setMaximumSpeed(boolean enabled) {
        prefs.edit().putBoolean(KEY_MAXIMUM_SPEED, enabled).apply();
    }

    /**
     * Warming the core on launch costs battery and gigabytes of RAM, so it is opt-in. It is worth
     * offering because the alternative is a tap that can only ever mean yes.
     */
    public boolean autostartCore() {
        return prefs.getBoolean(KEY_AUTOSTART_CORE, false);
    }

    public void setAutostartCore(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTOSTART_CORE, enabled).apply();
    }

    /**
     * Minutes of idleness after which the resident core stops itself; 0 keeps the
     * pre-Stage-4 "runs until stopped by hand" behavior. Stored values outside the
     * offered set fall back to the default rather than arming a surprising timer.
     */
    public long coreIdleTimeoutMinutes() {
        long stored = prefs.getLong(KEY_CORE_IDLE_TIMEOUT, IdleShutdown.DEFAULT_MINUTES);
        return IdleShutdown.normalized(stored) ? stored : IdleShutdown.DEFAULT_MINUTES;
    }

    public void setCoreIdleTimeoutMinutes(long minutes) {
        prefs.edit().putLong(
                KEY_CORE_IDLE_TIMEOUT,
                IdleShutdown.normalized(minutes) ? minutes : IdleShutdown.DEFAULT_MINUTES
        ).apply();
    }

    /** Waiting for clock recovery trades latency for speed, so it is an explicit opt-in. */
    public boolean thermalPacing() {
        return prefs.getBoolean(KEY_THERMAL_PACING, false);
    }

    public void setThermalPacing(boolean enabled) {
        prefs.edit().putBoolean(KEY_THERMAL_PACING, enabled).apply();
    }

    /** Idle checkpointing is on by default because it trades idle compute for much faster replay. */
    public boolean smartCompaction() {
        return prefs.getBoolean(KEY_SMART_COMPACTION, true);
    }

    public void setSmartCompaction(boolean enabled) {
        prefs.edit().putBoolean(KEY_SMART_COMPACTION, enabled).apply();
    }

    /** The OOM warning is a fact about this model on this phone, not an event to repeat. */
    public boolean oomRiskAcknowledged(String modelId) {
        return modelId != null && modelId.equals(prefs.getString(KEY_OOM_ACK, null));
    }

    public void setOomRiskAcknowledged(String modelId) {
        prefs.edit().putString(KEY_OOM_ACK, modelId).apply();
    }

    public UiLanguage uiLanguage() {
        return UiLanguage.fromWireName(prefs.getString(KEY_UI_LANGUAGE, null));
    }

    public void setUiLanguage(UiLanguage language) {
        prefs.edit().putString(
                KEY_UI_LANGUAGE,
                (language == null ? UiLanguage.RUSSIAN : language).wireName
        ).apply();
    }

    /**
     * The composer is working state, not decoration. Persisting it prevents a theme change,
     * low-memory process death or accidental navigation from erasing text the user has not sent.
     */
    public String composerDraft() {
        return boundedPrompt(KEY_COMPOSER_DRAFT);
    }

    public void setComposerDraft(String draft) {
        String value = draft == null ? "" : draft;
        if (value.isEmpty()) {
            prefs.edit().remove(KEY_COMPOSER_DRAFT).apply();
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) return;
        prefs.edit().putString(KEY_COMPOSER_DRAFT, value).apply();
    }

    /**
     * A prompt waiting for a running turn or a cold core must outlive the Activity. This write is
     * synchronous because the next event may be Android killing the process to make room for the
     * model; losing a one-item queue after the UI already said «saved» would be dishonest.
     */
    public String queuedPrompt() {
        return boundedPrompt(KEY_QUEUED_PROMPT);
    }

    public boolean setQueuedPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()
                || prompt.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) {
            return false;
        }
        return prefs.edit().putString(KEY_QUEUED_PROMPT, prompt).commit();
    }

    public boolean clearQueuedPrompt() {
        return prefs.edit().remove(KEY_QUEUED_PROMPT).commit();
    }

    private String boundedPrompt(String key) {
        String value = prefs.getString(key, "");
        if (value == null || value.isEmpty()) return "";
        if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_PROMPT_BYTES) return value;
        prefs.edit().remove(key).apply();
        return "";
    }

    public String systemPrompt() {
        String stored = prefs.getString(KEY_SYSTEM_PROMPT, "");
        try {
            return SystemPromptSettings.normalize(stored);
        } catch (IllegalArgumentException invalid) {
            prefs.edit().remove(KEY_SYSTEM_PROMPT).apply();
            return "";
        }
    }

    public SystemPromptSettings.Mode systemPromptMode() {
        return SystemPromptSettings.Mode.fromWireName(
                prefs.getString(KEY_SYSTEM_PROMPT_MODE, null)
        );
    }

    public void setSystemPrompt(SystemPromptSettings.Mode mode, String prompt) {
        String normalized = SystemPromptSettings.normalize(prompt);
        prefs.edit()
                .putString(KEY_SYSTEM_PROMPT, normalized)
                .putString(
                        KEY_SYSTEM_PROMPT_MODE,
                        (mode == null ? SystemPromptSettings.Mode.APPEND : mode).wireName()
                )
                .apply();
    }

    public boolean isCoreReady() {
        return prefs.getBoolean(KEY_CORE_READY, false);
    }

    public void setCoreReady(boolean ready) {
        prefs.edit().putBoolean(KEY_CORE_READY, ready).apply();
    }

    /**
     * A working RUN_COMMAND handshake and an installed Pi runtime are independent facts. Older
     * releases stored only the latter, so the one-time fallback preserves an already linked deck
     * until a real probe writes the dedicated value.
     */
    public boolean isTermuxLinkConfirmed() {
        if (prefs.contains(KEY_TERMUX_LINK_CONFIRMED)) {
            return prefs.getBoolean(KEY_TERMUX_LINK_CONFIRMED, false);
        }
        return prefs.getBoolean(KEY_CORE_READY, false);
    }

    public void setTermuxLinkConfirmed(boolean confirmed) {
        prefs.edit().putBoolean(KEY_TERMUX_LINK_CONFIRMED, confirmed).apply();
    }

    public JSONObject runtimePackages() {
        try {
            return DiagnosticReport.sanitizePackages(new JSONObject(
                    prefs.getString(KEY_RUNTIME_PACKAGES, "{}")
            ));
        } catch (JSONException ignored) {
            prefs.edit().remove(KEY_RUNTIME_PACKAGES).apply();
            return new JSONObject();
        }
    }

    public void setRuntimePackages(JSONObject packages) {
        prefs.edit().putString(
                KEY_RUNTIME_PACKAGES,
                DiagnosticReport.sanitizePackages(packages).toString()
        ).apply();
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
        String created = SessionId.create().toString();
        prefs.edit().putString(KEY_SESSION_ID, created).apply();
        return created;
    }

    public void setSessionId(String id, boolean hasMessages) {
        SessionId.parse(id);
        prefs.edit()
                .putString(KEY_SESSION_ID, id)
                .putBoolean(KEY_HAS_SESSION, hasMessages)
                .apply();
    }

    public String startNewSession() {
        String id = SessionId.create().toString();
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

    public String externalModelUri(String modelId) {
        return prefs.getString("external_model_uri_" + modelId, null);
    }

    public void setExternalModelUri(String modelId, String uri) {
        if (uri == null || uri.isBlank()) return;
        prefs.edit().putString("external_model_uri_" + modelId, uri).apply();
    }

    public void clearExternalModelUri(String modelId) {
        prefs.edit().remove("external_model_uri_" + modelId).apply();
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
                        item.optString("detail", ""),
                        item.optDouble("tokensPerSecond", Double.NaN),
                        item.optLong("outputTokens", -1L)
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
                if (entry.hasExactSpeed()) {
                    item.put("tokensPerSecond", entry.tokensPerSecond);
                    item.put("outputTokens", entry.outputTokens);
                }
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
