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
as successful output. The bridge removes the streamed fragment, queues one
explicit Pi `follow_up`, and emits `MODEL_OUTPUT_REJECTED`. A second such
response produces `TURN_FAILED`; it is never persisted in the Android
transcript as a successful answer.

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

Sequences are monotonic per random `bridgeInstanceId`. The journal retains at
most 10,000 events or 20 MiB, each normalized event at most 256 KiB. Active
operation events survive rotation. Android persists the last instance and
sequence, long-polls with bounded backoff and reconciles exact active IDs on
instance change or `EVENT_GAP`.

Approval UI messages are treated as untrusted display data and bounded. Audit
records contain decision metadata and a summary hash, not the full command.
