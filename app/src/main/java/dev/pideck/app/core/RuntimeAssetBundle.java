package dev.pideck.app.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Installs the exact assets shipped in the APK into app-private Termux storage. */
public final class RuntimeAssetBundle {
    private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    // Intent extras are marshalled as UTF-16 on some Android builds. Keep ample headroom below
    // their approximately 512 KiB Binder transaction ceiling instead of failing asynchronously
    // after the UI has already entered its busy state.
    static final int MAX_INSTALL_SCRIPT_BYTES = 200 * 1024;
    private static final String PI_PACKAGE = "@earendil-works/pi-coding-agent";
    private static final String PI_VERSION = "0.82.1";
    private static final String PI_INTEGRITY =
            "sha512-zbkAhoIuDPMF3pKuja0ajZabrMWU29FUMV9A/XMXT/XC1yXs5xt6t6t13GogQFsDrDqbFP4DkZQO1w8rWRAzYA==";
    private static final String NODE_MINIMUM = "22.19.0";
    private static final String[] ASSETS = {
            "models-v2.json",
            "compatibility.json",
            "runtime/AGENTS.default.md",
            "runtime/pideck-local-cache.ts",
            "runtime/pideck-system-prompt.ts",
            "runtime/pideck-hashline-edit.ts",
            "runtime/pideck-syntax-check.ts",
            "runtime/pideck-context-guard.ts",
            "runtime/pideck-web-tools.ts",
            "runtime/pideck-tool-router.ts",
            "runtime/pideck-permission-gate.ts",
            "runtime/pideck_runtime/__init__.py",
            "runtime/pideck_runtime/common.py",
            "runtime/pideck_runtime/model_store.py",
            "runtime/pideck_runtime/server_supervisor.py",
            "runtime/pideck_runtime/bridge.py",
            "runtime/pideck_runtime/launcher.py"
    };

    private RuntimeAssetBundle() {
    }

    /** The exact asset paths the installer copies onto the phone. */
    public static java.util.List<String> installedAssets() {
        return java.util.List.of(ASSETS);
    }

    public static String installCore(Context context) {
        return build(context, true);
    }

    public static String updateRuntime(Context context) {
        return build(context, false);
    }

    static String buildFromContents(Map<String, byte[]> contents, boolean installPackages) {
        StringBuilder script = new StringBuilder();
        script.append("""
                set -eu
                umask 077
                BASE="$HOME/.pideck"
                RUNTIME="$BASE/runtime"
                mkdir -p "$RUNTIME" "$RUNTIME/pideck_runtime" "$RUNTIME/bin" \
                  "$RUNTIME/pi" "$BASE/workspace" "$BASE/sessions" "$BASE/session-archive" \
                  "$BASE/models" "$BASE/processes" "$BASE/server" "$BASE/bridge" "$BASE/logs" \
                  "$BASE/pi" "$BASE/tool-results"
                chmod 700 "$BASE" "$RUNTIME" "$RUNTIME/pideck_runtime" "$RUNTIME/bin" \
                  "$RUNTIME/pi" "$BASE/workspace" "$BASE/sessions" "$BASE/session-archive" \
                  "$BASE/models" "$BASE/processes" "$BASE/server" "$BASE/bridge" "$BASE/logs" \
                  "$BASE/pi" "$BASE/tool-results"
                """);
        if (installPackages) {
            script.append("""
                    export DEBIAN_FRONTEND=noninteractive
                    printf '[01/06] synchronizing Termux packages\\n'
                    pkg update -y
                    printf '[02/06] installing Termux agent runtime\\n'
                    pkg install -y -o Dpkg::Options::=--force-confold \
                      nodejs python curl git ripgrep jq procps termux-exec termux-api
                    """);
        } else {
            script.append("""
                    printf '[01/06] keeping native packages unchanged\\n'
                    for executable in node npm python jq; do
                      command -v "$executable" >/dev/null 2>&1 || {
                        printf 'Missing runtime executable: %s\\n' "$executable" >&2
                        exit 31
                      }
                    done
                    """);
        }
        script.append("printf '[03/06] installing versioned runtime assets\\n'\n");
        int index = 0;
        for (Map.Entry<String, byte[]> asset : contents.entrySet()) {
            String target = targetFor(asset.getKey());
            String delimiter = "PIDECK_ASSET_" + index++;
            String encoded = Base64.getEncoder().encodeToString(gzip(asset.getValue()));
            script.append("base64 -d <<'").append(delimiter)
                    .append("' | python -c ")
                    .append(shellQuote(
                            "import gzip,sys;"
                                    + "sys.stdout.buffer.write(gzip.decompress(sys.stdin.buffer.read()))"
                    ))
                    .append(" > ").append(shellQuote(target + ".tmp")).append('\n');
            for (int offset = 0; offset < encoded.length(); offset += 120) {
                script.append(encoded, offset, Math.min(encoded.length(), offset + 120))
                        .append('\n');
            }
            script.append(delimiter).append('\n')
                    .append("chmod 600 ").append(shellQuote(target + ".tmp")).append('\n')
                    .append("mv -f ").append(shellQuote(target + ".tmp")).append(' ')
                    .append(shellQuote(target)).append('\n');
        }
        script.append("printf '[04/06] preserving workspace instructions\\n'\n");
        script.append(workspaceInstructionsScript());
        script.append("printf '[05/06] installing pinned Pi package\\n'\n");
        script.append("PI_PACKAGE=").append(shellQuote(PI_PACKAGE)).append('\n')
                .append("PI_VERSION=").append(shellQuote(PI_VERSION)).append('\n')
                .append("PI_INTEGRITY=").append(shellQuote(PI_INTEGRITY)).append('\n')
                .append("NODE_MINIMUM=").append(shellQuote(NODE_MINIMUM)).append('\n');
        script.append("""
                node -e '
                  const actual = process.versions.node.split(".").map(Number);
                  const minimum = process.argv[1].split(".").map(Number);
                  for (let i = 0; i < 3; i++) {
                    if ((actual[i] || 0) > (minimum[i] || 0)) process.exit(0);
                    if ((actual[i] || 0) < (minimum[i] || 0)) process.exit(33);
                  }
                ' "$NODE_MINIMUM" || {
                  printf 'Node.js %s or newer is required by Pi %s\\n' \
                    "$NODE_MINIMUM" "$PI_VERSION" >&2
                  exit 33
                }
                PI_TARGET="$RUNTIME/pi/$PI_VERSION"
                PI_BINARY="$PI_TARGET/node_modules/.bin/pi"
                if [ ! -x "$PI_BINARY" ] || \
                   [ "$(LD_PRELOAD="$PREFIX/lib/libtermux-exec.so" \
                     "$PI_BINARY" --version 2>/dev/null || true)" != "$PI_VERSION" ]; then
                  PREVIOUS_TARGET=
                  if [ -e "$PI_TARGET" ]; then
                    QUARANTINE="$RUNTIME/pi/quarantine-$PI_VERSION-$(date +%s)-$$"
                    mv "$PI_TARGET" "$QUARANTINE"
                    PREVIOUS_TARGET="$QUARANTINE"
                    printf 'Previous incomplete Pi install moved to %s\\n' "$QUARANTINE"
                  fi
                  STAGE="$(mktemp -d "$RUNTIME/.pi-install.XXXXXX")"
                  cleanup_stage() {
                    case "$STAGE" in "$RUNTIME"/.pi-install.*) rm -rf -- "$STAGE" ;; esac
                  }
                  rollback_pi() {
                    status=$?
                    trap - EXIT HUP INT TERM
                    cleanup_stage
                    if [ "$status" -ne 0 ] && [ -n "$PREVIOUS_TARGET" ] \
                       && [ -e "$PREVIOUS_TARGET" ] && [ ! -e "$PI_TARGET" ]; then
                      mv "$PREVIOUS_TARGET" "$PI_TARGET"
                      printf 'Restored previous Pi installation after failed update\\n' >&2
                    fi
                    exit "$status"
                  }
                  trap rollback_pi EXIT HUP INT TERM
                  PACK_JSON="$(cd "$STAGE" && npm pack "$PI_PACKAGE@$PI_VERSION" --ignore-scripts --json)"
                  ACTUAL_INTEGRITY="$(printf '%s' "$PACK_JSON" | jq -r '.[0].integrity')"
                  PACKAGE_FILE="$(printf '%s' "$PACK_JSON" | jq -r '.[0].filename')"
                  [ "$ACTUAL_INTEGRITY" = "$PI_INTEGRITY" ] || {
                    printf 'Pi npm integrity mismatch\\n' >&2
                    exit 32
                  }
                  npm install --prefix "$STAGE/install" --ignore-scripts --no-audit --no-fund \
                    "$STAGE/$PACKAGE_FILE"
                  STAGED_PI="$STAGE/install/node_modules/.bin/pi"
                  test -x "$STAGED_PI"
                  [ "$(LD_PRELOAD="$PREFIX/lib/libtermux-exec.so" \
                    "$STAGED_PI" --version)" = "$PI_VERSION" ]
                  mv "$STAGE/install" "$PI_TARGET"
                  trap - EXIT HUP INT TERM
                  cleanup_stage
                fi
                ln -sfn "$PI_BINARY" "$RUNTIME/bin/pi.new"
                mv -f "$RUNTIME/bin/pi.new" "$RUNTIME/bin/pi"
                chmod 700 "$RUNTIME/bin"
                printf '[06/06] runtime self-check\\n'
                export PATH="$RUNTIME/bin:$PATH"
                export PYTHONPATH="$RUNTIME"
                export PI_CODING_AGENT_DIR="$BASE/pi"
                export PI_CODING_AGENT_SESSION_DIR="$BASE/sessions"
                export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
                printf 'PI_VERSION='
                pi --version
                python -m pideck_runtime.launcher probe
                printf 'PIDECK_CORE_READY\\n'
                """);
        String value = script.toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_INSTALL_SCRIPT_BYTES) {
            throw new IllegalStateException(
                    "Bundled runtime exceeds the safe Android command size; split the payload"
            );
        }
        return value;
    }

    static String workspaceInstructionsScript() {
        return """
                cp "$RUNTIME/AGENTS.default.md" "$BASE/workspace/AGENTS.default.md.tmp"
                chmod 600 "$BASE/workspace/AGENTS.default.md.tmp"
                mv -f "$BASE/workspace/AGENTS.default.md.tmp" "$BASE/workspace/AGENTS.default.md"
                if [ ! -e "$BASE/workspace/AGENTS.md" ]; then
                  cp "$BASE/workspace/AGENTS.default.md" "$BASE/workspace/AGENTS.md"
                  chmod 600 "$BASE/workspace/AGENTS.md"
                  printf 'PIDECK_AGENTS_CREATED\\n'
                else
                  printf 'PIDECK_AGENTS_PRESERVED\\n'
                  if ! cmp -s "$BASE/workspace/AGENTS.md" "$BASE/workspace/AGENTS.default.md"; then
                    printf 'PIDECK_AGENTS_TEMPLATE_AVAILABLE: diff -u AGENTS.md AGENTS.default.md\\n'
                  fi
                fi
                """;
    }

    private static String build(Context context, boolean installPackages) {
        LinkedHashMap<String, byte[]> contents = new LinkedHashMap<>();
        for (String asset : ASSETS) {
            try (InputStream input = context.getAssets().open(asset)) {
                contents.put(asset, readBounded(input));
            } catch (IOException error) {
                throw new IllegalStateException("Missing bundled runtime asset: " + asset, error);
            }
        }
        return buildFromContents(contents, installPackages);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
            if (output.size() > MAX_ASSET_BYTES) throw new IOException("Runtime asset too large");
        }
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] value) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(value);
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Could not compress bundled runtime asset", error);
        }
    }

    private static String targetFor(String asset) {
        if ("models-v2.json".equals(asset) || "compatibility.json".equals(asset)) {
            return TermuxBridge.HOME + "/.pideck/runtime/" + asset;
        }
        return TermuxBridge.HOME + "/.pideck/" + asset;
    }

    private static String shellQuote(String value) {
        // Values are project-owned paths, but quote them as data to keep the generator reusable.
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
