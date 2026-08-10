# RPC bridge contract

The bridge implements schema version 1 over authenticated loopback HTTP:

```text
GET  /v1/health
GET  /v1/state
POST /v1/commands
GET  /v1/events?after=<sequence>&timeoutMs=<0..25000>
POST /v1/shutdown
```

Every request carries `X-PiDeck-Token`. Commands contain canonical UUIDv4
`operationId`, an explicit type and an object payload. Supported commands are
`PROMPT`, `ABORT`, `NEW_SESSION`, `GET_STATE` and `APPROVAL_DECISION`.
Duplicate operation IDs are rejected; mutating prompts are never replayed.

The managed Pi child is launched as:

```text
pi --mode rpc --provider pideck --model <exact-id> --offline
   --no-extensions --extension pideck-local-cache.ts
   --extension pideck-system-prompt.ts
   --extension pideck-hashline-edit.ts
   --extension pideck-syntax-check.ts
   --extension pideck-run-tests.ts
   --extension pideck-context-guard.ts
   --extension pideck-web-tools.ts
   --extension pideck-tool-router.ts
   [profile-specific tools and explicit permission extension]
```

The exact 0.82.1 package documentation and declaration files were used. Pi's
JSONL `prompt`, `abort`, `new_session`, `get_state`,
`extension_ui_request/response` commands are normalized into bounded events.
The always-loaded local extension adds llama.cpp's `cache_prompt` request flag,
so repeated model/tool rounds reuse the common KV prefix without changing the
conversation or tool contract.

The managed web extension registers `web_search`, `web_fetch` and `weather`.
`web_search` returns at most five compact sourced results and falls back from
the Exa public MCP endpoint to DuckDuckGo HTML search. `weather` resolves a
place and returns current conditions plus three days from Open-Meteo. Both use
fixed endpoints, 20-second abort-aware requests and a 256 KiB response ceiling.
They remain inside every Agent profile's hard CLI allowlist, including
`READ_ONLY`, but the tool router keeps their schemas inactive for an ordinary
turn. An explicit web, URL or weather request activates the matching group in
Pi's `input` hook before the first provider request. A compact
`pideck_load_tools` call can add an optional group during a turn. The router
intersects every change with the Android-selected profile, while tool-free Chat
remains isolated from all tools.

Android sends an optional custom system prompt only in the bootstrap stdin
JSON. The runtime validates a 16 KiB UTF-8 limit and atomically writes a fixed
mode-`0600` file. A pinned explicit Pi extension rechecks its hash and applies
it at the final `before_agent_start` hook; only the managed path and fingerprint
travel in the child environment, never the text or argv. `append` puts the
instructions after Pi's assembled project context; `replace` deliberately
replaces the complete prompt. An empty value restores Pi's default. Bridge
config, process metadata and `GET_STATE` expose only mode, byte count and
SHA-256, which lets Android reject stale bridge settings without echoing text.
Default Chat uses a separate short, tool-free final prompt instead of carrying
Pi's coding-agent instructions and project-tool guidance into a conversation
that cannot execute them. Custom append and full-replace semantics are kept.

Stderr is drained separately. Malformed JSON, oversized frames, stdout EOF and
child exit are protocol failures; an active turn becomes failed/unknown and is
not automatically restarted.

A short assistant response made only from Markdown punctuation is not accepted
as successful output. The bridge removes the streamed fragment and emits
`MODEL_OUTPUT_REJECTED`. Pi 0.82.1 does not consume an RPC command written from
`message_end` until after `agent_settled`, so the bridge records a pending retry,
waits for that confirmed idle boundary, and then starts one ordinary `prompt`
in the same session while retaining the original Android operation ID. A second
such response produces `TURN_FAILED`; it is never persisted in the Android
transcript as a successful answer. This ordering also prevents a late
`follow_up` from leaking into the next user turn. The retry prompt carries an
internal prefix that the tool router strips before inference while retaining the
original turn's active optional tools; URL and exact-edit work therefore do not
silently lose capabilities at the idle boundary.

The same fail-closed path covers three incomplete terminal responses: an
assistant message with `stopReason=length`, an unexpected single visible Unicode
letter (including lightweight Markdown wrappers), and a one-token/one-word
fragment when the original prompt explicitly requires a sentence, definition,
description or explanation. The first letter is held inside the bridge rather
than journaled to the UI; other rejected fragments are removed at `message_end`.
Each is retried once with a bounded prompt; a second fragment becomes
`TURN_FAILED` with an empty answer, so Android cannot persist it as a successful
turn. A prompt that explicitly requests a one-letter/one-character format,
initial letter, or lettered option records only that format permission in bridge
memory and bypasses the guard; Unicode options are supported, while negated or
explanation-required choices do not. Explicit one-word formats and naturally
terse questions remain valid; the broader short-fragment guard is enabled only
by an explicit prose obligation. The prompt itself is not copied into bridge
state or diagnostics. Provider `error` and unexpected `aborted` stop reasons use
the same reject/empty-failure contract unless Pi produces a valid recovery before
settling.

Streaming deltas are speculative UI previews. `TURN_COMPLETED.answer` is the
authoritative final text even if only the first delta reached Android or the two
values disagree. An in-progress streaming row is excluded from every durable
transcript snapshot; `MODEL_OUTPUT_REJECTED` and `EVENT_GAP` discard it before
persisting and only then advance the event cursor. Thus pausing or killing the
activity after a one-letter delta cannot restore that fragment as a completed
answer.

For an explicit live-data request, the bridge also requires a successful
`weather` or `web_search` execution before accepting the answer. It retries
once with a targeted instruction if the model searches local files or answers
from memory, and fails clearly after a second miss.

For assistant messages, the bridge accumulates provider-reported output-token
usage and decode time between the first output delta and `message_end`.
The generated Pi provider config explicitly enables streaming usage, causing
Pi's OpenAI adapter to request `stream_options.include_usage` from the pinned
llama.cpp b10092 server.
Terminal turn events may therefore include bounded `outputTokens`,
`decodeDurationMs`, `tokensPerSecond` and `speedEstimated=false`. Android uses
an explicitly approximate character-based rate while streaming, then replaces
it with these exact provider-usage metrics when the turn settles.

Pi 0.82.1 also subtracts a fixed 4096-token hosted-provider safety margin when
it derives `max_tokens`. PI//DECK advertises Pi a virtual context window equal
to the selected model's real llama.cpp window plus that fixed margin. The
llama-server `--ctx-size` remains the catalog's real `recommendedContext`.
Auto-compaction reserves the same fixed margin plus the model's full
`agent.maxTokens`, making Pi compact at `realContext - maxTokens` instead of
silently clamping a later request to one token. Session utilization exposed to
Android is recomputed against the real window, never the virtual descriptor.
Because Pi deep-merges `workspace/.pi/settings.json` over its global settings,
the three managed compaction fields are pinned in both layers while unrelated
global and project preferences are preserved. The bridge launch metadata carries
the context-contract version and effective compaction fingerprint, so an upgrade
restarts a live Pi child that loaded an older descriptor or reserve.

The first non-fragment text delta is journaled immediately. A lone initial
Unicode letter (and its lightweight formatting wrappers) is held until more
visible text arrives unless the prompt explicitly permits a one-character
answer. Later deltas are coalesced until 1 KiB or 100 ms, with a scheduled
quiet-period flush and a mandatory flush before every tool/control/terminal
event. Delta appends do not each call `fsync`; the next control or terminal sync
commits the preceding batch. Server health I/O used by state polling runs outside
the Pi turn lock, so a slow `/health` request cannot block stdout consumption.

Every Pi callback is fenced to the currently owned child instance. A delayed
exit or buffered `agent_settled` from a replaced process cannot fail or complete
the new turn. Shutdown rejects new commands, terminalizes active work, and keeps
process metadata/private prompt files whenever exact child or bridge termination
cannot be confirmed; `bridge-stop` reconciles recorded children even when bridge
metadata is absent or corrupt.

An Android HTTP exception after writing a command is an ambiguous delivery, not
proof that the bridge rejected it. The matching operation remains the sole UI
owner in `UNKNOWN` while the event journal and `/state` reconcile it; the command
is never replayed automatically. Only bounded local preflight rejection or an
authoritative non-2xx bridge response can fail dispatch immediately. Every late
failure callback is fenced by `operationId`, so it cannot clear composer, busy,
or inference state belonging to a newer operation. `TURN_ACCEPTED`, the matching
terminal event, or an authoritative active `/state` snapshot acknowledges only
that operation's pending composer text, so a lost HTTP response cannot leave the
send affordance stuck and a late acknowledgement cannot erase a newer prompt.

Sequences are monotonic per random `bridgeInstanceId`. The journal retains at
most 10,000 events or 20 MiB, each normalized event at most 256 KiB. Active
operation events survive rotation. Android persists the last instance and
sequence, long-polls with bounded backoff and reconciles exact active IDs on
instance change or `EVENT_GAP`.

Approval UI messages are treated as untrusted display data and bounded. Audit
records contain decision metadata and a summary hash, not the full command.

## Client stall reconciliation

The Android client arms a progress-based watchdog per operation. Every
delivered bridge event of that exact operation re-arms an 8-minute stall
window (`OperationKind.stallTimeoutMs`); the pre-existing overall deadline
(45 minutes for an agent turn) still caps the whole operation. On expiry the
client moves the operation to `UNKNOWN`, fetches `/v1/state`, and either
reconciles a terminal outcome or, if the bridge still reports the operation
active, shows the wait/abort card and starts a fresh window — but only until
the overall deadline. Past it the card is final: no automatic re-arm, and the
manual "wait longer" action opens a fresh full window. A `/v1/state`
confirmation is deliberately not progress — only events are.
