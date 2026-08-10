# End-to-end phone agent benchmark

[`tools/adb_agent_benchmark.py`](../tools/adb_agent_benchmark.py) measures the
path a person actually waits for:

```text
Android cold/warm state -> authenticated bridge command -> Pi/provider
-> optional tool/retry rounds -> terminal turn
```

This fills a different role from `adb_llama_probe.py` and
`speculative_probe.py`. Those probes isolate the native server. The agent
harness keeps PI//DECK in the foreground and drives the same authenticated RPC
bridge used by the app.

## Safety and prerequisites

- Use a disposable benchmark session and make sure no turn is active. The
  harness creates a fresh Pi session before every sample; Android will adopt
  the bridge's authoritative session on its next state reconciliation.
- A cold sample deliberately runs `am force-stop dev.pideck.app`, restarts the
  exported main Activity, and waits for authenticated `server=READY` plus a
  live Pi child. It interrupts any existing app-owned inference.
- Before requesting cold runs, enable `CORE -> Autostart` in PI//DECK. The
  harness does not bypass consent or synthesize an `IGNITE` screen tap; with
  autostart disabled, a force-stopped app correctly remains cold and the
  readiness wait times out. Warm-only runs do not require this setting.
- Supply the existing bridge token through a private host file. The tool never
  accepts it as a command-line value, never prints it, and requires mode `0600`.
  Copy it from Termux's private `~/.pideck/bridge/token` over a private channel
  such as Termux SSH; do not leave it in shared Android storage.
- The selected bridge must use `agentMode=agent` for the tool case. The default
  tool prompt calls only `read`; no approval or mutation is needed.
- Put the phone on a stable surface, leave PI//DECK visible, and avoid charging
  if possible. ADB thermal polling has overhead, so keep the same sampling
  interval in every A/B comparison.

No ADB command is executed by tests or by importing the module.

Example:

```bash
chmod 600 /tmp/pideck-bridge-token
python3 tools/adb_agent_benchmark.py \
  --serial R5CW11HGLVV \
  --token-file /tmp/pideck-bridge-token \
  --model-id qwen3.5-2b \
  --cold-runs 3 \
  --warm-runs 5 \
  --tool-runs 3 \
  --output out/agent-speed-qwen2b.json
```

The default cooldown gate waits for at least 98% big-core frequency headroom
for up to ten minutes before each case. A timed-out gate does not invent a
clean sample: `cooldown.met=false` and the measured headroom stay in the report.
For a diagnostic run that must not wait, pass `--cooldown-timeout 0` and treat
the result as thermally unmatched.

To measure a known answer-guard retry reproducer, add
`--retry-prompt '...'`. The report counts actual `MODEL_OUTPUT_REJECTED`
events. If the model does not trigger the guard, `retryCoverageObserved` remains
false; the harness never turns a zero observation into claimed retry latency.

## Measurements

Each case records:

- cold Activity/server readiness, cold-to-first-visible-token and full
  cold-to-terminal wall time;
- warm dispatch-to-first-visible-token (user-visible TTFT) and terminal wall
  time;
- provider-backed `outputTokens`, `decodeDurationMs` and decode tokens/s from
  the authoritative terminal event;
- prefill tokens, seconds and tokens/s from the native server log when
  `adb exec-out run-as dev.pideck.app` is available (normally a debuggable
  build);
- otherwise, a clearly tagged estimate based on fresh-session context-token
  growth minus output tokens, divided by visible TTFT;
- time to first tool call, paired tool execution time, tool failures and
  post-tool-to-terminal time;
- rejection reasons, added agent rounds, retry-dispatch delay and
  first-rejection-to-terminal time;
- thermal status, hottest CPU reading, big-core headroom, battery level,
  charging tags and instantaneous sysfs power when both `current_now` and
  `voltage_now` are readable.

Prompts and answers are not written to the report. Cases contain only the
prompt SHA-256 and UTF-8 byte count. Tool arguments, tool results, the bridge
token and the raw native log are also excluded.

## Interpretation

`native_run_as_log` prefill figures are direct llama-server timings.
`estimated_context_delta_over_visible_ttft` is end-to-end diagnostic evidence,
not an exact kernel rate: it includes prompt assembly, queueing and first-token
latency. Compare estimates only with estimates produced under the same bridge,
session-reset and polling contract.

The native log path is also fail-closed: if its bounded tail slides during a
turn and the harness can no longer prove the exact append boundary, native
timings are omitted and the tagged estimate is used. Earlier server requests
are never silently attributed to the current sample.

"Cold" means a new app-owned llama-server process. Android's filesystem page
cache may still be warm; the harness does not require root, reboot the phone or
drop kernel caches. "Warm" means the server and bridge stay alive, while a new
Pi session prevents earlier conversation growth from deciding the result.

Instantaneous battery current is signed differently across devices and USB
charging can dominate it. The harness reports an absolute sampled mean tagged
`instantaneous_sysfs_average_watts`; it is not a calibrated external power
measurement. Prefer an external meter or an energy counter for admission
claims.

## Relationship to the admission report

`schemas/benchmark-report.schema.json` is the strict aggregate contract for the
28-task admission suite. It currently has one cold-start value, one TTFT, one
tool-call latency and one decode-rate value; it cannot preserve cold/warm
distributions or per-round overhead.

The agent-speed JSON is raw evidence, not a replacement report. Aggregate its
matched samples into the existing fields only after the task outcomes are also
available:

- median cold `startupSeconds` -> `cold_start_seconds`;
- median no-tool TTFT -> `time_to_first_token_seconds`;
- median tool-case first-call latency -> `time_to_first_tool_call_seconds`;
- median provider decode rate -> `tokens_per_second`;
- environment summaries -> temperature, battery, power/energy and thermal
  fields.

Task success, invalid calls, mutation checks, recovery and abort rates still
come from the 28-task harness. This speed probe must not manufacture them.
