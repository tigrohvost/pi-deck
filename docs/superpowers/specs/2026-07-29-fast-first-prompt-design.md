# From launch to the first prompt

Design date: 2026-07-29. Target: PI//DECK 0.3.0-alpha8.

## Problem

Between opening the deck and the agent receiving a first prompt there are too
many taps and too many seconds. Measured on the reference device (Samsung
SM-S918B, Qwen3.5 2B Q4_K_M, `docs/performance.md`), with Termux, the Pi
runtime, and the private GGUF already installed but nothing running:

| Step | Cost |
|---|---|
| `probeRuntimeOnLaunch` → Termux `RUN_COMMAND` → `launcher.py probe` | Termux service cold start plus `node`/`pi`/`python` version subprocesses |
| `linkConfirmed = false` while the probe runs | boot card `BOOT SEQUENCE // 03` flashes on every launch |
| tap `IGNITE LLM` | plus the OOM-risk dialog on a memory-tight device |
| `startServerConfirmed` → `stopServerRuntime(true)` | a Termux round trip to `server-stop` even when nothing is running |
| native `llama-server` | 20.64 s model load |
| `dispatchAdoption` → `server-adopt` | a third Termux round trip |
| `bridge-start` | a fourth round trip: Python plus Node plus Pi |
| typing | rejected until `canRunAgent()`, so the human waits idle for all of the above |

The `IGNITE → BRIDGE` chain already exists (`MainActivity.java:856`), so the
common cold path is one tap, not two. The second tap appears when the server
survived but the bridge did not, which is the usual state after the Activity is
recreated while the foreground service keeps running.

## Non-goals

- Starting the Pi bridge in parallel with the model load. `bootstrap_bridge`
  (`bridge.py:2030`) requires an exact-identity `READY` server plus an
  authenticated health check before it will start. Relaxing that would weaken
  the identity invariant and require a runtime contract bump. Not worth it.
- `--no-warmup`. It saves about a second and moves the cost into the first
  prompt.
- Removing the consent screen. It stays exactly as it is.

## Design

### 1. Unblock the launch

`onCreate` learns the server state locally instead of waiting for Termux:

```java
NativeLlamaService.Snapshot boot = NativeLlamaService.snapshot(this);
serverReady = "READY".equals(boot.state) && selectedModel.id.equals(boot.modelId);
```

`probeRuntimeOnLaunch` no longer sets `linkConfirmed = false`. The last known
link state holds until the probe actually fails, which is where line 782 already
sets it. The `BOOT SEQUENCE // 03` flash disappears.

### 2. Stop paying for Termux round trips that do nothing

`startServerConfirmed` goes straight to `launchNativeServer()` when there is
nothing to stop: no live bridge, `serverReady` false, and the native service
snapshot in `STOPPED` or `FAILED`. The Termux `server-stop` path stays for the
case it was written for, a managed or legacy server that really is running.

The OOM-risk dialog is asked once per model. `DeckPreferences` stores the
acknowledged model id; changing the model asks again. A phone that is genuinely
short on memory still gets the warning the first time.

### 3. Type immediately, send automatically

`onSend` currently rejects everything until `canRunAgent()`. It gains a middle
case: when the core is installed and merely cold, the prompt is queued through
the existing `queuedPrompt` slot and the warm-up starts.

```
onSend, !canRunAgent():
    core installable-complete (Termux + link + core + consent + private GGUF)
        → queue the prompt, tell the user it will go when Pi is up, warmCore()
    otherwise
        → the existing "complete the boot sequence" toast
```

`warmCore()` is the same ladder the ignite tap walks: start the server if it is
not ready, otherwise start the bridge. It is triggered from the points where
readiness actually arrives: `setBusy(false, …)` as today, plus the
`BRIDGE_READY` and `PI_STARTED` events.

`dispatchQueuedPrompt` must not treat a cold core as a dead one. A successful
`START_SERVER` calls `setBusy(false, …)` before it posts `startBridge()`, so the
queue drain runs in the gap between the two steps and would otherwise discard
the prompt with "the core is no longer ready". Instead, while the core is still
warmable the prompt stays queued and the warm-up is nudged forward, bounded by
`MAX_QUEUED_WARM_ATTEMPTS` so a warm-up that keeps failing ends in an honest
error rather than a loop. Only when warming is impossible or exhausted is the
prompt dropped and reported.

### 4. Autostart

A new `ЯДРО → Автозапуск` toggle, stored as `autostart_core_v1`, default
**off**. When on, a successful launch probe warms the core without a tap.
Suppressed when Android reports `lowMemory`.

When the toggle is off, one automatic continuation still happens: a server that
is already `READY` with no bridge starts the bridge by itself. That costs no
extra battery, since the expensive process is already running, and it removes
the `BOOT SEQUENCE // 09` tap.

### 5. Defaults: agent mode and unattended commands

`AgentMode` already defaults to `AGENT` (`AgentMode.fromWireName(null)`).

The access profile default moves from `READ_ONLY` to `AUTONOMOUS`: Pi may run
shell commands and edit files visible to the Termux user without a per-action
Android approval.

The default lives in `DeckPreferences.accessProfile()`, not in
`AccessProfile.fromWireName`. An unknown or blank wire value still resolves to
`READ_ONLY`, so a corrupt or unrecognised value cannot silently escalate
privilege — only the absence of a stored preference selects the new default.

Consequences, stated plainly:

- On a fresh install the first prompt can run shell commands. The consent screen
  is still shown first and already describes exactly this.
- On upgrade, a user who never touched the access profile moves from
  `READ_ONLY` to `AUTONOMOUS` without acting. This is a privilege escalation
  performed by an update. It is intentional and requested; it is recorded here
  so it is not discovered later as a surprise.
- `README.md` and `docs/security-model.md` are updated in the same change, so
  the shipped documentation never describes a default the code does not use.
- The app version goes to `0.3.0-alpha8` (`versionCode` 16), with
  `compatibility.json` and `RUNTIME_VERSION` following it. Shipping two builds
  that call themselves alpha7 with different security defaults would make the
  version string useless for exactly the question it would be asked.
  `RUNTIME_CONTRACT_VERSION` stays at 12: no runtime protocol changed.

The per-action approval path (`CONFIRM_CHANGES`) is untouched and remains one
tap away in `ЯДРО → Доступ`.

## Testability

The decisions become pure functions in a new `core/StartupPolicy.java`, in the
style of `BridgeFaultPolicy`, so they are covered by JVM unit tests without an
Android context:

- `skipsRuntimeStop(nativeState, serverReady, bridgeLive)`
- `warmsOnLaunch(autostart, canWarm, serverReady, bridgeReady, busy, lowMemory)`
- `queuesUntilReady(canRunAgent, canWarmCore, alreadyQueued)`
- `asksOomRisk(lowMemory, availableRam, minimumBytes, peakBytes, acknowledged)`

`MainActivity` keeps only the wiring.

## Result

For "everything installed, nothing running": zero taps, the composer accepts
text from the first second, and the prompt is dispatched by itself after the
20.6 s model load plus bridge start. About 5–10 s of Termux round trips are
removed. For "the foreground service survived": the answer starts immediately.

The 20.6 s model load itself is unchanged. Nothing in this design pretends
otherwise.
