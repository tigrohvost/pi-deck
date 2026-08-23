package dev.pideck.app.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small bootstrap scripts and argument arrays; operational logic lives in versioned Python. */
public final class RuntimeScripts {
    private static final int RUNTIME_CONTRACT_VERSION = 53;

    private RuntimeScripts() {
    }

    public static String probe() {
        return """
                set -eu
                printf 'PIDECK_LINK_OK\\n'
                BASE="$HOME/.pideck"
                if [ -f "$BASE/runtime/pideck_runtime/launcher.py" ] \
                  && [ -f "$BASE/runtime/models-v2.json" ]; then
                  export PATH="$BASE/runtime/bin:$PATH"
                  export PYTHONPATH="$BASE/runtime"
                  export PI_CODING_AGENT_DIR="$BASE/pi"
                  export PI_CODING_AGENT_SESSION_DIR="$BASE/sessions"
                  export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
                  python -m pideck_runtime.launcher probe
                else
                  printf '{"schemaVersion":1,"ok":false,"state":"NOT_INSTALLED"}\\n'
                fi
                """;
    }

    public static boolean isLinkProbeOutput(String stdout) {
        if (stdout == null) return false;
        for (String line : stdout.split("\\R")) {
            if ("PIDECK_LINK_OK".equals(line.trim())) return true;
        }
        return false;
    }

    public static boolean isReadyProbeOutput(String stdout) {
        JSONObject result = finalJsonObject(stdout);
        return result != null
                && result.optInt("schemaVersion", -1) == 1
                && result.optBoolean("ok", false)
                && "READY".equals(result.optString("state"))
                && result.optInt("runtimeContractVersion", -1) == RUNTIME_CONTRACT_VERSION
                && result.optBoolean("layoutReady", false)
                && result.optBoolean("versionsCompatible", false)
                && "0.82.1".equals(result.optString("piVersion"))
                && !result.isNull("nodeVersion")
                && !result.isNull("pythonVersion")
                && "b10092".equals(result.optString("llamaVersion"));
    }

    public static JSONObject finalJsonObject(String stdout) {
        if (stdout == null) return null;
        JSONObject latest = null;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) continue;
            try {
                JSONObject candidate = new JSONObject(trimmed);
                if (candidate.has("schemaVersion") && candidate.has("ok")) latest = candidate;
            } catch (JSONException ignored) {
                // A malformed line is not interpreted by substring; caller receives a probe error.
            }
        }
        return latest;
    }

    public static String[] runtimeArguments(String command) {
        if (command == null || !command.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("Unsafe runtime command");
        }
        String base = TermuxBridge.HOME + "/.pideck";
        List<String> arguments = new ArrayList<>();
        arguments.add("LD_PRELOAD=" + TermuxBridge.TERMUX_EXEC);
        arguments.add("PYTHONPATH=" + base + "/runtime");
        arguments.add("PATH=" + base + "/runtime/bin:" + TermuxBridge.PREFIX + "/bin");
        arguments.add("PI_CODING_AGENT_DIR=" + base + "/pi");
        arguments.add("PI_CODING_AGENT_SESSION_DIR=" + base + "/sessions");
        arguments.add("PI_OFFLINE=1");
        arguments.add(TermuxBridge.PREFIX + "/bin/python");
        arguments.add("-m");
        arguments.add("pideck_runtime.launcher");
        arguments.add(command);
        return arguments.toArray(new String[0]);
    }

    public static String jsonInput(JSONObject value) {
        return (value == null ? new JSONObject() : value).toString();
    }
}
