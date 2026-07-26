# Security model

## Trust boundaries

The Android app and Termux are different Android sandboxes. `RUN_COMMAND` is an
explicit cross-app capability guarded by Android permission and Termux's
`allow-external-apps` setting. PI//DECK checks the package version and signer
against `compatibility.json` before dispatch.

Loopback is not private IPC. The Pi bridge therefore requires a random 256-bit
token on every request, compares it in constant time, binds only
`127.0.0.1`, stores only a token hash in process metadata and never puts the
token in argv or logs. llama-server has a separate random API key.

## Access profiles

- `READ_ONLY` is the migration and first-run default. Only
  `read,grep,find,ls` are enabled.
- `CONFIRM_CHANGES` disables mutating built-ins and exposes differently named
  gated tools. Each execution uses Pi's documented RPC `confirm` UI request,
  a one-time approval ID and 30-second TTL. Disconnect, restart, malformed or
  duplicate responses deny.
- `AUTONOMOUS` is an explicit opt-in. It can execute shell commands and modify
  anything writable by the Termux UID. The workspace is not an OS sandbox.

Local inference means token generation runs on the phone. It does not mean
network isolation: shell tools can access the network. PI//DECK does not claim
an Android/Termux network namespace, root protection or protection from a
compromised OS.

## Same-UID and model risks

Pi, its tools, the bridge and models run under the same Termux UID. An
autonomous shell can therefore alter runtime files; full private GGUF SHA-256
is repeated before every new server start. A pinned hash proves byte identity,
not provenance. Entries with incomplete conversion metadata remain
`EXPERIMENTAL`.

Secrets and state are mode `0600`; state directories are `0700`; installed
GGUF is `0400`. Process signals require PID, process group, `/proc` start ticks,
expected executable and a per-operation environment token. Unknown processes
on occupied ports are never killed.

Production logs omit prompts and bridge tokens. Event/tool payloads,
transcripts and operation output are byte-bounded. Diagnostic export is not yet
implemented. Component logs are private and reset on supervisor restart, but a
strict live-size cap for every native `llama-server` log stream remains release
follow-up rather than a claimed protection.
