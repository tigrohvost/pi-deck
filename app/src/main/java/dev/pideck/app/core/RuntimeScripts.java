package dev.pideck.app.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RuntimeScripts {
    private RuntimeScripts() {
    }

    public static String probe() {
        return """
                set -eu
                printf 'PIDECK_LINK_OK\\n'
                command -v pi >/dev/null 2>&1 && printf 'PI=' && pi --version || true
                command -v llama-server >/dev/null 2>&1 && printf 'LLAMA=ready\\n' || true
                command -v python >/dev/null 2>&1 && printf 'PYTHON=' && python --version || true
                """;
    }

    public static String installCore() {
        return """
                set -eu
                export DEBIAN_FRONTEND=noninteractive
                printf '[01/05] synchronizing Termux packages\\n'
                pkg update -y
                printf '[02/05] installing native runtime\\n'
                # An app-shell has no tty, so dpkg must never stop on a conffile prompt.
                pkg install -y -o Dpkg::Options::=--force-confold \
                  nodejs python llama-cpp curl git ripgrep jq procps
                printf '[03/05] installing Pi coding agent\\n'
                if npm install -g --ignore-scripts @earendil-works/pi-coding-agent@0.82.1; then
                  PI_PACKAGE='@earendil-works/pi-coding-agent@0.82.1'
                else
                  npm install -g --ignore-scripts @mariozechner/pi-coding-agent@0.73.1
                  PI_PACKAGE='@mariozechner/pi-coding-agent@0.73.1'
                fi
                printf '[04/05] creating deck workspace\\n'
                mkdir -p "$HOME/.pideck/workspace" "$HOME/.pideck/sessions" "$HOME/.pideck/pi"
                cat > "$HOME/.pideck/workspace/AGENTS.md" <<'PIDECK_AGENTS'
                # PI//DECK phone workspace

                You are Pi running locally on this Android phone through Termux.

                - Work inside this workspace unless the user explicitly asks for another local path.
                - Python, shell, git, curl, and ripgrep are available.
                - Internet access is available through normal command-line tools.
                - Shared phone files are under `~/storage/shared`; downloads are under `~/storage/downloads`.
                - Preserve existing phone files. Before overwriting or deleting user data, explain the exact target.
                - Prefer small, runnable programs and verify them locally before reporting completion.
                PIDECK_AGENTS
                cat > "$HOME/.pideck/pi/models.json" <<'PIDECK_MODELS'
                {
                  "providers": {
                    "pideck": {
                      "baseUrl": "http://127.0.0.1:8080/v1",
                      "api": "openai-completions",
                      "apiKey": "pideck-local",
                      "compat": {
                        "supportsDeveloperRole": false,
                        "supportsReasoningEffort": false,
                        "supportsStore": false,
                        "supportsUsageInStreaming": false,
                        "maxTokensField": "max_tokens"
                      },
                      "models": [
                        {
                          "id": "qwen3.5-0.8b",
                          "name": "Qwen3.5 0.8B · PI//DECK NANO",
                          "reasoning": false,
                          "input": ["text"],
                          "contextWindow": 8192,
                          "maxTokens": 1024,
                          "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0}
                        },
                        {
                          "id": "qwen3.5-2b",
                          "name": "Qwen3.5 2B · PI//DECK EDGE",
                          "reasoning": false,
                          "input": ["text"],
                          "contextWindow": 8192,
                          "maxTokens": 1536,
                          "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0}
                        },
                        {
                          "id": "qwen3.5-4b",
                          "name": "Qwen3.5 4B · PI//DECK CORE",
                          "reasoning": false,
                          "input": ["text"],
                          "contextWindow": 8192,
                          "maxTokens": 2048,
                          "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0}
                        },
                        {
                          "id": "qwen3.5-9b",
                          "name": "Qwen3.5 9B · PI//DECK MAX",
                          "reasoning": false,
                          "input": ["text"],
                          "contextWindow": 8192,
                          "maxTokens": 2048,
                          "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0}
                        }
                      ]
                    }
                  }
                }
                PIDECK_MODELS
                printf '[05/05] runtime self-check\\n'
                printf 'PI_PACKAGE=%s\\n' "$PI_PACKAGE"
                printf 'PI_VERSION='
                PI_CODING_AGENT_DIR="$HOME/.pideck/pi" pi --version
                printf 'PYTHON_VERSION='
                python --version
                printf 'LLAMA_SERVER='
                command -v llama-server
                printf 'PIDECK_CORE_READY\\n'
                """;
    }

    public static String updateAgent() {
        return """
                set -eu
                printf 'Updating Pi agent…\\n'
                if npm install -g --ignore-scripts @earendil-works/pi-coding-agent@latest; then
                  :
                else
                  npm install -g --ignore-scripts @mariozechner/pi-coding-agent@latest
                fi
                printf 'PI_VERSION='
                PI_CODING_AGENT_DIR="$HOME/.pideck/pi" pi --version
                printf 'PIDECK_AGENT_UPDATED\\n'
                """;
    }

    public static String startServer(ModelSpec model, int threads) {
        String safeFile = shellSingleQuote(model.fileName);
        String safeId = shellSingleQuote(model.id);
        int safeThreads = Math.max(2, Math.min(8, threads));
        return String.format(Locale.US, """
                set -eu
                BASE="$HOME/.pideck"
                MODEL="$HOME/storage/downloads/PiDeck/models/"%s
                MODEL_ID=%s
                LOG="$BASE/llama-server.log"
                PIDFILE="$BASE/llama-server.pid"
                test -r "$MODEL" || {
                  printf 'GGUF is not readable at: %%s\\n' "$MODEL" >&2
                  printf 'Run termux-setup-storage once and grant file access.\\n' >&2
                  exit 21
                }
                if [ -s "$PIDFILE" ]; then
                  OLD_PID="$(cat "$PIDFILE" 2>/dev/null || true)"
                  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
                    kill "$OLD_PID" 2>/dev/null || true
                    for _ in 1 2 3 4 5; do
                      kill -0 "$OLD_PID" 2>/dev/null || break
                      sleep 1
                    done
                  fi
                fi
                termux-wake-lock >/dev/null 2>&1 || true
                : > "$LOG"
                nohup llama-server \
                  -m "$MODEL" \
                  --alias "$MODEL_ID" \
                  --host 127.0.0.1 \
                  --port 8080 \
                  -c 8192 \
                  -np 1 \
                  -t %d \
                  --jinja \
                  --reasoning off \
                  --temp 0.7 \
                  --top-p 0.8 \
                  --top-k 20 \
                  --min-p 0.0 \
                  --presence-penalty 1.5 \
                  > "$LOG" 2>&1 < /dev/null &
                SERVER_PID=$!
                printf '%%s\\n' "$SERVER_PID" > "$PIDFILE"
                sleep 3
                if ! kill -0 "$SERVER_PID" 2>/dev/null; then
                  tail -n 40 "$LOG" >&2 || true
                  exit 22
                fi
                printf 'PIDECK_SERVER_STARTED pid=%%s model=%%s threads=%s\\n' "$SERVER_PID" "$MODEL_ID"
                """, safeFile, safeId, safeThreads, safeThreads);
    }

    public static String stopServer() {
        return """
                set -eu
                PIDFILE="$HOME/.pideck/llama-server.pid"
                if [ -s "$PIDFILE" ]; then
                  PID="$(cat "$PIDFILE")"
                  kill "$PID" 2>/dev/null || true
                  rm -f "$PIDFILE"
                fi
                termux-wake-unlock >/dev/null 2>&1 || true
                printf 'PIDECK_SERVER_STOPPED\\n'
                """;
    }

    public static String newSession() {
        return """
                set -eu
                SRC="$HOME/.pideck/sessions"
                DST="$HOME/.pideck/session-archive/$(date +%Y%m%d-%H%M%S)"
                moved=0
                if [ -d "$SRC" ]; then
                  mkdir -p "$DST"
                  # Pi stores sessions as sessions/<encoded-cwd>/<id>.jsonl, so archive whole entries.
                  for entry in "$SRC"/* "$SRC"/.[!.]*; do
                    [ -e "$entry" ] || continue
                    mv "$entry" "$DST/"
                    moved=1
                  done
                  [ "$moved" -eq 1 ] || rmdir "$DST" 2>/dev/null || true
                fi
                printf 'PIDECK_NEW_SESSION\\n'
                """;
    }

    public static String abortAgent() {
        return """
                set -eu
                pkill -INT -f '/data/data/com.termux/files/usr/bin/pi' 2>/dev/null || true
                printf 'PIDECK_AGENT_ABORT_SENT\\n'
                """;
    }

    public static String[] agentArguments(ModelSpec model, boolean continueSession, String prompt) {
        List<String> args = new ArrayList<>();
        args.add("PI_CODING_AGENT_DIR=" + TermuxBridge.HOME + "/.pideck/pi");
        args.add("PI_CODING_AGENT_SESSION_DIR=" + TermuxBridge.HOME + "/.pideck/sessions");
        args.add("PI_OFFLINE=1");
        args.add(TermuxBridge.PREFIX + "/bin/pi");
        args.add("--approve");
        args.add("--mode");
        args.add("json");
        args.add("--provider");
        args.add("pideck");
        args.add("--model");
        args.add(model.id);
        args.add("--thinking");
        args.add("off");
        args.add("--tools");
        args.add("read,bash,edit,write,grep,find,ls");
        args.add("--session-dir");
        args.add(TermuxBridge.HOME + "/.pideck/sessions");
        if (continueSession) args.add("--continue");
        args.add(prompt);
        return args.toArray(new String[0]);
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
