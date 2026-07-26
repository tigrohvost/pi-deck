package dev.pideck.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import dev.pideck.app.core.DeckPreferences;
import dev.pideck.app.core.BridgeTokenStore;
import dev.pideck.app.core.ModelCatalog;
import dev.pideck.app.core.ModelSpec;
import dev.pideck.app.core.OperationId;
import dev.pideck.app.core.OperationKind;
import dev.pideck.app.core.RuntimeAssetBundle;
import dev.pideck.app.core.RuntimeScripts;
import dev.pideck.app.core.TermuxBridge;

/**
 * ADB-only entry point for exercising the real Termux bridge while the debug APK is installed.
 * The DUMP permission limits callers to adb/system, and the fixed action set cannot execute an
 * arbitrary shell command.
 */
public final class DebugCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "PiDeckDebug";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getStringExtra("action");
        try {
            TermuxBridge termux = new TermuxBridge(context);
            OperationId operationId = OperationId.create();
            switch (action == null ? "" : action) {
                case "probe" -> termux.runBash(
                        operationId, OperationKind.PROBE_RUNTIME, RuntimeScripts.probe()
                );
                case "install_core" -> termux.runBash(
                        operationId,
                        OperationKind.INSTALL_RUNTIME,
                        RuntimeAssetBundle.installCore(context)
                );
                case "update_runtime" -> termux.runBash(
                        operationId,
                        OperationKind.UPDATE_RUNTIME,
                        RuntimeAssetBundle.updateRuntime(context)
                );
                case "start", "server_start" -> termux.runRuntime(
                        operationId,
                        OperationKind.START_SERVER,
                        "server-start",
                        serverInput(operationId, selectedModel(context)).toString()
                );
                case "agent" -> termux.runRuntime(
                        operationId,
                        OperationKind.AGENT_TURN,
                        "agent-once",
                        agentInput(
                                operationId,
                                selectedModel(context),
                                intent.getStringExtra("prompt") == null
                                        ? "Reply with exactly PIDECK_OK"
                                        : intent.getStringExtra("prompt")
                        ).toString()
                );
                case "help" -> termux.runBash(
                        operationId,
                        OperationKind.RECONCILE,
                        "set -eu\n"
                                + "export PATH=\"$HOME/.pideck/runtime/bin:$PATH\"\n"
                                + "pi --version\n"
                                + "pi --help\n"
                );
                case "llama_help" -> termux.runBash(
                        operationId,
                        OperationKind.RECONCILE,
                        "set -eu\nllama-server --help\n"
                );
                case "llama_devices" -> termux.runBash(
                        operationId,
                        OperationKind.RECONCILE,
                        """
                                set -eu
                                dpkg-query -W -f='${Package} ${Version}\n' \
                                  'llama-cpp*' 'vulkan-loader*' 2>/dev/null || true
                                ls -l "$PREFIX/lib/libvulkan.so" "$PREFIX/lib/libggml-vulkan.so" \
                                  2>/dev/null || true
                                llama-server --version
                                llama-server --list-devices
                                """
                );
                case "stop" -> termux.runRuntime(
                        operationId, OperationKind.STOP_SERVER, "server-stop", "{}"
                );
                case "bridge_start" -> termux.runRuntime(
                        operationId,
                        OperationKind.START_BRIDGE,
                        "bridge-start",
                        bridgeInput(
                                operationId,
                                selectedModel(context),
                                new BridgeTokenStore(context).getOrCreate()
                        ).toString()
                );
                case "bridge_stop" -> termux.runRuntime(
                        operationId, OperationKind.STOP_BRIDGE, "bridge-stop", "{}"
                );
                case "reconcile" -> termux.runRuntime(
                        operationId, OperationKind.RECONCILE, "reconcile", "{}"
                );
                case "state" -> termux.runBash(
                        operationId,
                        OperationKind.RECONCILE,
                        """
                                set -eu
                                printf '%s\n' '--- llama-server.log ---'
                                tail -n 120 "$HOME/.pideck/logs/llama-server.log" 2>/dev/null || true
                                printf '%s\n' '--- runtime state ---'
                                export PATH="$HOME/.pideck/runtime/bin:$PATH"
                                export PYTHONPATH="$HOME/.pideck/runtime"
                                python -m pideck_runtime.launcher reconcile 2>/dev/null || true
                                printf '%s\n' '--- session files ---'
                                find "$HOME/.pideck/sessions" -type f -print 2>/dev/null || true
                                """
                );
                case "legacy_state" -> termux.runBash(
                        operationId,
                        OperationKind.RECONCILE,
                        """
                                set -eu
                                export PYTHONPATH="$HOME/.pideck/runtime"
                                python - <<'PY'
                                import json
                                from pideck_runtime.common import proc_cmdline, proc_identity
                                from pideck_runtime.server_supervisor import (
                                    LEGACY_SERVER_PID,
                                    _legacy_arguments_recognized,
                                    _legacy_candidate,
                                    _process_started_before_file,
                                )

                                value = {"pidFileExists": LEGACY_SERVER_PID.is_file()}
                                try:
                                    stat = LEGACY_SERVER_PID.stat()
                                    raw_pid = LEGACY_SERVER_PID.read_text(encoding="ascii").strip()
                                    pid = int(raw_pid)
                                    process_group, start_ticks = proc_identity(pid)
                                    arguments = proc_cmdline(pid)
                                    value.update({
                                        "pid": pid,
                                        "pidFileBytes": stat.st_size,
                                        "pidFileMtime": stat.st_mtime,
                                        "processGroup": process_group,
                                        "startTicks": start_ticks,
                                        "timeBound": _process_started_before_file(
                                            start_ticks, stat.st_mtime
                                        ),
                                        "arguments": arguments,
                                        "argumentsRecognized": _legacy_arguments_recognized(
                                            arguments, 8080
                                        ),
                                        "candidate": _legacy_candidate(8080) is not None,
                                    })
                                except Exception as error:
                                    value["error"] = f"{type(error).__name__}: {error}"
                                print(json.dumps(value, separators=(",", ":")))
                                PY
                                """
                );
                case "abort" -> termux.runRuntime(
                        operationId,
                        OperationKind.ABORT_AGENT,
                        "abort-agent",
                        abortInput(intent.getStringExtra("targetOperationId")).toString()
                );
                default -> throw new IllegalArgumentException("Unknown debug action: " + action);
            }
            Log.i(TAG, "Dispatched " + action + " as " + operationId);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not dispatch " + action, error);
        }
    }

    private static ModelSpec selectedModel(Context context) {
        ModelCatalog catalog = ModelCatalog.initialize(context);
        String selected = new DeckPreferences(context).selectedModelId();
        if (selected != null) {
            ModelSpec found = catalog.byId(selected).orElse(null);
            if (found != null) return found;
        }
        for (ModelSpec model : catalog.all()) {
            if ("CORE".equals(model.tier)) return model;
        }
        return catalog.all().get(0);
    }

    private static JSONObject serverInput(OperationId operationId, ModelSpec model) {
        JSONObject value = baseInput(operationId);
        put(value, "modelId", model.id);
        put(value, "threads", 7);
        put(value, "port", 8080);
        return value;
    }

    private static JSONObject bridgeInput(
            OperationId operationId,
            ModelSpec model,
            String token
    ) {
        JSONObject value = baseInput(operationId);
        put(value, "modelId", model.id);
        put(value, "accessProfile", "read_only");
        put(value, "token", token);
        put(value, "port", 8787);
        return value;
    }

    private static JSONObject agentInput(
            OperationId operationId,
            ModelSpec model,
            String prompt
    ) {
        JSONObject value = baseInput(operationId);
        put(value, "modelId", model.id);
        put(value, "accessProfile", "read_only");
        put(value, "prompt", prompt);
        return value;
    }

    private static JSONObject abortInput(String target) {
        JSONObject value = new JSONObject();
        put(value, "targetOperationId", target == null ? "" : target);
        return value;
    }

    private static JSONObject baseInput(OperationId operationId) {
        JSONObject value = new JSONObject();
        put(value, "schemaVersion", 1);
        put(value, "operationId", operationId.toString());
        return value;
    }

    private static void put(JSONObject value, String key, Object item) {
        try {
            value.put(key, item);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }
}
