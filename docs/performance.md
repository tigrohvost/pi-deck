# ADB performance evidence

Evidence date: 2026-07-27. Device: Samsung SM-S918B, Android API 36,
Snapdragon 8 Gen 2, 12 GiB RAM. Model: Qwen3.5 2B Q4_K_M, SHA-256
`57a1085840f497d764a7fc5d346922dbde961efb54cc792ea81d694fd846a1d8`.
Runtime: official Android arm64 `llama.cpp b10092`, context 10240, reasoning off,
one slot. This is a device smoke, not the 28-task model-admission benchmark.

| Check | Result |
|---|---:|
| Native model cold start | 20.64 s |
| Direct prompt ingestion, 39 tokens | 68.38 tok/s |
| Direct generation, 292 tokens | 16.13 tok/s |
| Authenticated HTTP wall rate | 15.54 tok/s |
| Peak server RSS after Pi + direct probes | 3031 MiB |
| Clean-session Pi RPC result | exact `PIDECK_OK` |
| Foreground process CPU-set | `/top-app`, CPUs `0-7` |
| Managed profile | decode `5@3-7`, batch `8@0-7` |

Pi 0.82.1 keeps a 4096-token safety reserve before issuing a provider request.
The default 2B profile therefore uses a real 10240-token llama context so the
agent's system prompt and 1536-token output budget both fit without advertising
capacity the server did not allocate. A new-session RPC smoke streamed
`P`, `IDE`, `CK`, `_OK` and completed with the exact answer `PIDECK_OK`.

The same model under the Termux UID fell to the background CPU-set when the
PI//DECK Activity was visible. A controlled pre-change run produced about
0.058 tok/s in that state; putting the workload in the foreground restored
roughly 16 tok/s. Owning inference in PI//DECK removes that cross-application
scheduler conflict during normal deck use.

The checked-in `tools/adb_llama_probe.py` runs inside Termux, reads the managed
API key without printing it, sends a bounded authenticated completion and
writes a JSON report to shared Downloads. Server timings come from the
app-private native log and process state from ADB `/proc`.

When PI//DECK itself is backgrounded, Android may move its foreground-service
process from `/top-app` to `/moderate`; generation continues but can slow down.
The published throughput therefore describes the intended interactive state:
the deck visible in the foreground.

## Two ways this device gets slower, measured 2026-07-29

Every figure above is a short probe on a cool, foreground phone. Two effects make
a long session slower than that, and both were large enough to reorder results
before they were controlled for.

**Any other app in the foreground costs the cpuset, not just backgrounding the
deck.** The paragraph above describes what happens when the deck is backgrounded.
The condition is broader: the server sits on `/moderate` whenever *something else*
is the resumed activity. Opening Termux alongside a running agent was enough:

```
topResumedActivity=com.termux/.app.TermuxActivity
/proc/<llama-server>/cpuset  →  /moderate
```

Bringing the deck back to the front returned it to `/top-app` immediately. This
matters for the ordinary case of starting a long agent turn and switching away to
do something else.

**Sustained load halves the machine.** After several hours of near-continuous
inference the big cores were clamped to less than half their nominal clock and
prompt processing collapsed:

| | Cool phone | After hours of inference |
|---|---:|---:|
| Hottest CPU thermal zone | 47–52 °C | **82.8 °C** |
| `cpu7 scaling_max_freq` | 3 360 000 (nominal) | **1 478 400 (44 %)** |
| Prompt processing | 37–60 tok/s | **0.36 tok/s** |

The consequence for any multi-task benchmark is that a run without pauses scores
its later tasks on a slower machine than its earlier ones, so task ordering alone
can decide the outcome. That is not hypothetical: it invalidated a first pass of
the speculative-decoding measurements, and the detail is in
[`speculative-decoding-measurements.md`](speculative-decoding-measurements.md).
Anything comparative on this device should wait for the governor to return the
clock before each case, and record the headroom next to each result — which is
what `tools/speculative_probe.py` does.

Charging over USB while measuring keeps the phone warm, so a cooldown gate can
take several minutes per case and may never reach the full clock at all.
