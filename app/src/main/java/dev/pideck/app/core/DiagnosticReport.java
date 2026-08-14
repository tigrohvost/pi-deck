package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Builds an explicitly allowlisted report: no prompts, output, paths, tokens, or stack traces. */
public final class DiagnosticReport {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_OPERATIONS = 20;

    private static final Set<String> FACT_KEYS = Set.of(
            "appVersion", "versionCode", "device", "sdk", "modelId",
            "accessProfile", "autonomousUntilMs", "termuxVersion", "termuxSource",
            "termuxApiVersion", "termuxLink", "runtimeInstalled", "serverState",
            "bridgeReady", "bridgeConnected", "busy", "lowMemory",
            "availableRamBytes", "freeStorageBytes", "thermalStatus", "thermalHeadroom"
    );
    private static final Set<String> PACKAGE_KEYS = Set.of(
            "bash", "curl", "git", "jq", "nodejs", "procps", "python",
            "ripgrep", "termux-api", "termux-exec"
    );

    private DiagnosticReport() {
    }

    public static JSONObject create(
            JSONObject candidateFacts,
            JSONObject candidatePackages,
            List<OperationRecord> records,
            long nowMs
    ) {
        JSONObject report = new JSONObject();
        put(report, "schemaVersion", SCHEMA_VERSION);
        put(report, "generatedAt", Instant.ofEpochMilli(nowMs).toString());
        put(report, "facts", allowlisted(candidateFacts, FACT_KEYS));
        put(report, "termuxPackages", sanitizePackages(candidatePackages));

        JSONArray operations = new JSONArray();
        int first = Math.max(0, records.size() - MAX_OPERATIONS);
        for (int index = records.size() - 1; index >= first; index--) {
            OperationRecord record = records.get(index);
            JSONObject item = new JSONObject();
            put(item, "operationId", record.operationId.toString());
            put(item, "kind", record.kind.name());
            put(item, "state", record.state.name());
            put(item, "createdAt", Instant.ofEpochMilli(record.createdAtMs).toString());
            put(item, "updatedAt", Instant.ofEpochMilli(record.updatedAtMs).toString());
            put(item, "hasResult", record.result != null);
            String category = errorCategory(record);
            put(item, "errorCategory", category == null ? JSONObject.NULL : category);
            operations.put(item);
        }
        put(report, "recentOperations", operations);
        return report;
    }

    public static JSONObject sanitizePackages(JSONObject candidate) {
        return allowlisted(candidate, PACKAGE_KEYS);
    }

    public static String operationSummary(List<OperationRecord> records) {
        if (records.isEmpty()) return "No recorded operations.";
        StringBuilder result = new StringBuilder();
        int first = Math.max(0, records.size() - MAX_OPERATIONS);
        for (int index = records.size() - 1; index >= first; index--) {
            OperationRecord record = records.get(index);
            if (result.length() > 0) result.append('\n');
            result.append(record.operationId)
                    .append(" · ").append(record.kind.name())
                    .append(" · ").append(record.state.name())
                    .append(" · result=").append(record.result != null);
            String category = errorCategory(record);
            if (category != null) result.append(" · ").append(category);
        }
        return result.toString();
    }

    private static String errorCategory(OperationRecord record) {
        if (record.result != null) {
            if (record.result.errorCode > 0) return "TERMUX_IPC_ERROR";
            if (record.result.exitCode != 0) return "NONZERO_EXIT";
        }
        if (!record.error.isBlank()) return "COORDINATOR_ERROR";
        return record.state == OperationState.FAILED ? "FAILED" : null;
    }

    private static JSONObject allowlisted(JSONObject candidate, Set<String> keys) {
        JSONObject safe = new JSONObject();
        if (candidate == null) return safe;
        for (String key : keys) {
            if (!candidate.has(key) || candidate.isNull(key)) continue;
            Object value = candidate.opt(key);
            if (value instanceof String text) {
                if (text.length() <= 256) put(safe, key, text);
            } else if (value instanceof Boolean || value instanceof Number) {
                put(safe, key, value);
            }
        }
        return safe;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not build diagnostic report", error);
        }
    }
}
