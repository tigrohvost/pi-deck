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
   --no-extensions [profile-specific tools and explicit extension]
```

The exact 0.82.1 package documentation and declaration files were used. Pi's
JSONL `prompt`, `abort`, `new_session`, `get_state`,
`extension_ui_request/response` commands are normalized into bounded events.
Stderr is drained separately. Malformed JSON, oversized frames, stdout EOF and
child exit are protocol failures; an active turn becomes failed/unknown and is
not automatically restarted.

Sequences are monotonic per random `bridgeInstanceId`. The journal retains at
most 10,000 events or 20 MiB, each normalized event at most 256 KiB. Active
operation events survive rotation. Android persists the last instance and
sequence, long-polls with bounded backoff and reconciles exact active IDs on
instance change or `EVENT_GAP`.

Approval UI messages are treated as untrusted display data and bounded. Audit
records contain decision metadata and a summary hash, not the full command.
