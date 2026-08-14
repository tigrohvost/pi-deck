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

- `CONFIRM_CHANGES` is the first-run default. A legacy indefinite `AUTONOMOUS`
  preference has no valid expiry and is downgraded on first read; an unknown or
  unreadable stored value still resolves to `READ_ONLY`, so corruption cannot
  escalate privilege. The consent screen is still shown before the first run
  and describes shell access explicitly.
- `READ_ONLY` enables `read,grep,find,ls` plus the bounded, APK-managed
  `web_search`, `web_fetch` and `weather`; shell and file mutation remain
  disabled. Reading a page is not a mutation, so it sits in this profile. The
  network tools remain allowed but their schemas are activated only for a
  routed live-data task.
- `CONFIRM_CHANGES` disables mutating built-ins and exposes differently named
  gated tools, including `pideck_replace_lines`, which edits by line anchor and
  asks through the same single approval path as `pideck_edit`. The managed
  read-only `web_search`, `web_fetch` and `weather` tools are also available. Each mutation uses Pi's documented RPC `confirm` UI request, a
  one-time approval ID and 30-second TTL. Disconnect, restart, malformed or
  duplicate responses deny.
- `AUTONOMOUS` can execute shell commands and modify anything writable by the
  Termux UID, and it includes the same network tools. `pideck_replace_lines` is
  available here too and applies without asking, because this profile's whole
  point is that it does not ask; `PIDECK_HASHLINE_APPROVAL` carries that decision
  from the bridge, and any value other than the explicit `none` keeps the prompt.
  The workspace is not an OS sandbox. This profile is an explicit 30-minute
  Android grant: the UI shows remaining time, extends it only after a fresh risk
  acknowledgement, and returns to `CONFIRM_CHANGES` on expiry. An already active
  turn may settle, but the bridge independently rejects each new prompt whose
  grant is missing, expired or longer than 30 minutes.

Pi's `--tools` option is a hard registry allowlist, not merely an initial tool
selection. PI//DECK therefore passes every capability the chosen profile may
use, then its bundled router narrows the active subset before each idle prompt.
The router rejects calls outside that same profile and can only activate names
already present in the hard allowlist. `CONFIRM_CHANGES` still omits the mutating
built-ins entirely and keeps the existing permission extension as a second,
independent guard.

Local inference means token generation runs on the phone. It does not mean
network isolation: shell tools can access the network. PI//DECK does not claim
an Android/Termux network namespace, root protection or protection from a
compromised OS.

Pi supports installable third-party packages, but PI//DECK passes
`--no-extensions` and explicitly loads only APK-managed extension files.
Third-party packages therefore cannot silently join the active tool surface.
Web-search queries are sent to Exa (or DuckDuckGo on fallback), while weather
place names are sent to Open-Meteo; no API key is exposed to the model.
Responses are byte-bounded before parsing.

`web_fetch` is the one tool that does take a model-supplied URL, so it is worth
stating plainly what that costs. The URL is validated to be absolute http or
https and is read directly first, which keeps an ordinary page inside the same
first-party request the browser would make. Only when a direct read yields too
little text — a JavaScript-only page — does it retry through `r.jina.ai`, and
that proxy then learns the URL being read. The direct attempt always happens
first, the fallback is never silent in the tool output, and the page text is
bounded before it reaches the model.

## UID and model boundaries

Pi, its tools and the bridge run under the Termux UID. The model and native
server run under the separate PI//DECK UID, so an autonomous Termux shell
cannot modify the installed GGUF. The shared incoming file is transport only:
PI//DECK streams a second SHA-256 before atomic installation. A pinned hash
proves byte identity, not provenance. Entries with incomplete conversion
metadata remain `EXPERIMENTAL`.

Secrets and Termux state are mode `0600`; state directories are `0700`;
installed GGUF is exactly `0400`. Termux process signals require PID, process
group, `/proc` start ticks, expected executable and a per-operation environment
token. The Android service owns its child through a private `Process` handle.
Unknown processes on occupied ports are never killed.

Production logs omit prompts and bridge tokens. Event/tool payloads,
transcripts and operation output are byte-bounded. The diagnostic UI/export is
an explicit allowlist of device/runtime facts and operation metadata; it never
serializes requests, prompt/answer text, paths, raw output or tokens. Component
logs are private and every live subprocess stream is capped at 4 MiB, retaining
only its newest 2 MiB tail.

The editable agent system prompt is stored in Android-private preferences. It
crosses into Termux only in `RUN_COMMAND` stdin JSON, is persisted there as a
fixed mode-`0600` file and never appears as CLI text. Runtime config, process
metadata, events and bridge state retain only its mode, UTF-8 byte count,
SHA-256 and managed path. A pinned explicit Pi extension repeats the integrity
check before applying it at the final per-turn system-prompt hook. Stopping the
bridge removes the Termux copy.
