# Turn Stall Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hung agent turn is detected by lack of progress within minutes and resolved fail-closed, instead of waiting out a fixed 45-minute deadline (Stage 0 of `docs/superpowers/specs/2026-08-10-performance-large-models-design.md`).

**Architecture:** A new pure-logic `StallWatchdog` class in `app/.../core` tracks two deadlines per operation — a rolling *stall* window re-armed by every bridge event of that exact operation, and the existing fixed *overall* cap. `MainActivity` replaces its single `postDelayed` deadline with this class; the fire path (existing `operations.timeout()` → `/state` → failure card) stays. A bridge-claimed-active turn does NOT count as progress — that is precisely the hang being fixed.

**Tech Stack:** Java 17 syntax (Android app module), JUnit 4 local unit tests, Python `unittest` runtime suite driven via pytest.

## Global Constraints

- Build needs JDK 21: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (default java 25 fails the build with a bare `25.0.3`).
- After a host reboot: `mkdir -p /tmp/gradle-modules-2` or every Gradle build fails.
- The pinned native runtime `llama.cpp b10092` is untouched in this stage.
- Fail-closed discipline (spec): ambiguous state → `UNKNOWN` + reconciliation, never auto-retry; late callbacks of a replaced operation must not mutate the current one.
- `MODEL_OUTPUT_DELTA` events are flushed at most every 100 ms (see CHANGELOG «Не выпущено»), so re-arming the watchdog per event is bounded at ~10 Hz.
- The stall window must exceed the longest measured legitimate silence: 173 s of prompt replay before the first token (`docs/performance.md`). Chosen value: 480 s (~2.5× margin).
- UI copy in `reportWatchdog` is Russian-literal today — keep that method's existing style; do not convert it to `t()` in this plan.
- Commit messages: lowercase conventional style describing behavior, e.g. `fix: a hung turn is detected by silence, not by a 45-minute deadline`.
- The working tree has many unrelated uncommitted changes. `git add` only the files each task names — never `git add -A`.

---

### Task 1: `StallWatchdog` core class (TDD)

**Files:**
- Create: `app/src/main/java/dev/pideck/app/core/StallWatchdog.java`
- Modify: `app/src/main/java/dev/pideck/app/core/OperationKind.java`
- Test: `app/src/test/java/dev/pideck/app/core/StallWatchdogTest.java`

**Interfaces:**
- Consumes: `OperationId` (existing, `OperationId.create()`/`.equals`), `OperationKind` (existing enum; this task adds `stallTimeoutMs()`).
- Produces (Task 2 relies on these exact signatures):
  - `new StallWatchdog(OperationId operationId, OperationKind kind, long nowMs)` — fresh arm; both windows start at `nowMs`.
  - `new StallWatchdog(OperationId operationId, OperationKind kind, long armedAtMs, long nowMs)` — restored arm; overall cap keeps `armedAtMs`, stall window starts at `nowMs`.
  - `OperationId operationId()`, `OperationKind kind()`
  - `boolean progress(OperationId source, long nowMs)` — true only when `source` equals the armed operation.
  - `StallWatchdog.Verdict verdict(long nowMs)` — `WAIT` | `STALLED` | `EXPIRED` (`EXPIRED` wins over `STALLED`).
  - `long nextCheckDelayMs(long nowMs)` — ms until nearest deadline, floor 1.
  - `long silentForMs(long nowMs)` — ms since last accepted progress, floor 0.
  - `OperationKind.stallTimeoutMs()` — `480_000L` for `AGENT_TURN` and `COMPACT_SESSION`, `== timeoutMs()` for every other kind.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/dev/pideck/app/core/StallWatchdogTest.java`:

```java
package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StallWatchdogTest {
    @Test
    public void progressExtendsStallDeadlineButNotOverallCap() {
        OperationId id = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L);
        long stall = OperationKind.AGENT_TURN.stallTimeoutMs();
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(stall - 1));
        assertTrue(watchdog.progress(id, stall - 1));
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(stall + stall - 2));
        assertEquals(StallWatchdog.Verdict.STALLED, watchdog.verdict(stall - 1 + stall));
        long overall = OperationKind.AGENT_TURN.timeoutMs();
        for (long now = 0; now < overall; now += stall / 2) {
            watchdog.progress(id, now);
        }
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(overall));
    }

    @Test
    public void foreignOperationEventsDoNotFeedTheWatchdog() {
        OperationId mine = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(mine, OperationKind.AGENT_TURN, 0L);
        assertFalse(watchdog.progress(OperationId.create(), 1_000L));
        assertEquals(
                StallWatchdog.Verdict.STALLED,
                watchdog.verdict(OperationKind.AGENT_TURN.stallTimeoutMs())
        );
    }

    @Test
    public void restoredOperationKeepsOverallDeadlineWithFreshStallWindow() {
        OperationId id = OperationId.create();
        long now = OperationKind.AGENT_TURN.timeoutMs() - 60_000L;
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L, now);
        assertEquals(StallWatchdog.Verdict.WAIT, watchdog.verdict(now + 30_000L));
        assertEquals(StallWatchdog.Verdict.EXPIRED, watchdog.verdict(now + 60_000L));
    }

    @Test
    public void nextCheckDelayTracksTheNearestDeadlineWithAFloor() {
        OperationId id = OperationId.create();
        StallWatchdog watchdog = new StallWatchdog(id, OperationKind.AGENT_TURN, 0L);
        assertEquals(
                OperationKind.AGENT_TURN.stallTimeoutMs(),
                watchdog.nextCheckDelayMs(0L)
        );
        assertEquals(
                1L,
                watchdog.nextCheckDelayMs(OperationKind.AGENT_TURN.stallTimeoutMs() + 5L)
        );
        assertEquals(0L, watchdog.silentForMs(0L));
        assertEquals(7L, watchdog.silentForMs(7L));
    }

    @Test
    public void stallWindowsCoverMeasuredSilentPrefill() {
        // docs/performance.md: 173 s of silent prompt replay is legitimate.
        // The stall window must not fire during it.
        assertEquals(480_000L, OperationKind.AGENT_TURN.stallTimeoutMs());
        assertEquals(480_000L, OperationKind.COMPACT_SESSION.stallTimeoutMs());
        for (OperationKind kind : OperationKind.values()) {
            assertTrue(kind.stallTimeoutMs() <= kind.timeoutMs());
            assertTrue(kind.stallTimeoutMs() > 0L);
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mkdir -p /tmp/gradle-modules-2
./gradlew :app:testDebugUnitTest --tests 'dev.pideck.app.core.StallWatchdogTest'
```

Expected: compilation FAILURE — `StallWatchdog` does not exist, `stallTimeoutMs()` undefined.

- [ ] **Step 3: Add `stallTimeoutMs` to `OperationKind`**

In `app/src/main/java/dev/pideck/app/core/OperationKind.java`, change the two stalled-capable constants and add the field/constructor/accessor:

```java
    AGENT_TURN(true, 2_700_000L, 480_000L),
    COMPACT_SESSION(true, 900_000L, 480_000L),
```

(other constants keep their current two-argument form), and:

```java
    private final boolean mutating;
    private final long timeoutMs;
    private final long stallTimeoutMs;

    OperationKind(boolean mutating, long timeoutMs) {
        this(mutating, timeoutMs, timeoutMs);
    }

    OperationKind(boolean mutating, long timeoutMs, long stallTimeoutMs) {
        this.mutating = mutating;
        this.timeoutMs = timeoutMs;
        this.stallTimeoutMs = stallTimeoutMs;
    }

    public long stallTimeoutMs() {
        return stallTimeoutMs;
    }
```

- [ ] **Step 4: Write `StallWatchdog`**

Create `app/src/main/java/dev/pideck/app/core/StallWatchdog.java`:

```java
package dev.pideck.app.core;

/**
 * Progress-based deadline for one operation. Fires when the bridge goes silent for the
 * kind's stall window, or when the kind's overall timeout elapses regardless of progress.
 * A confirmation that the bridge still considers the operation active is not progress;
 * only delivered events of this exact operation are.
 */
public final class StallWatchdog {
    public enum Verdict { WAIT, STALLED, EXPIRED }

    private final OperationId operationId;
    private final OperationKind kind;
    private final long armedAtMs;
    private long lastProgressAtMs;

    public StallWatchdog(OperationId operationId, OperationKind kind, long nowMs) {
        this(operationId, kind, nowMs, nowMs);
    }

    /** A restored operation keeps its original overall deadline but gets a fresh stall window. */
    public StallWatchdog(OperationId operationId, OperationKind kind, long armedAtMs, long nowMs) {
        if (operationId == null || kind == null) {
            throw new IllegalArgumentException("Watchdog needs an operation and a kind");
        }
        this.operationId = operationId;
        this.kind = kind;
        this.armedAtMs = Math.min(armedAtMs, nowMs);
        this.lastProgressAtMs = nowMs;
    }

    public OperationId operationId() {
        return operationId;
    }

    public OperationKind kind() {
        return kind;
    }

    /** Counts only events that belong to this exact operation. */
    public boolean progress(OperationId source, long nowMs) {
        if (!operationId.equals(source)) return false;
        if (nowMs > lastProgressAtMs) lastProgressAtMs = nowMs;
        return true;
    }

    public Verdict verdict(long nowMs) {
        if (nowMs - armedAtMs >= kind.timeoutMs()) return Verdict.EXPIRED;
        if (nowMs - lastProgressAtMs >= kind.stallTimeoutMs()) return Verdict.STALLED;
        return Verdict.WAIT;
    }

    public long nextCheckDelayMs(long nowMs) {
        long stallDeadline = lastProgressAtMs + kind.stallTimeoutMs();
        long overallDeadline = armedAtMs + kind.timeoutMs();
        return Math.max(1L, Math.min(stallDeadline, overallDeadline) - nowMs);
    }

    public long silentForMs(long nowMs) {
        return Math.max(0L, nowMs - lastProgressAtMs);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.pideck.app.core.StallWatchdogTest'
```

Expected: BUILD SUCCESSFUL, 5 tests pass.

- [ ] **Step 6: Run the whole Java unit suite (regressions)**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/pideck/app/core/StallWatchdog.java \
        app/src/main/java/dev/pideck/app/core/OperationKind.java \
        app/src/test/java/dev/pideck/app/core/StallWatchdogTest.java
git commit -m "feat: progress-based stall deadline for agent operations"
```

---

### Task 2: Wire `StallWatchdog` into `MainActivity`

**Files:**
- Modify: `app/src/main/java/dev/pideck/app/MainActivity.java:157-158` (fields), `:1482-1560` (arm/fire/cancel), `:3260-3274` (reconcileRunning branch), `:3337-3339` (handleBridgeEvent entry)

**Interfaces:**
- Consumes: `StallWatchdog` exactly as produced by Task 1; existing `operations.timeout()`, `reportWatchdog(...)`, `cancelWatchdog(...)`, `rpc.state()`, `handleBridgeState(...)`.
- Produces: no new public API. Behavior contract for Task 3's changelog: a silent `AGENT_TURN`/`COMPACT_SESSION` fires reconciliation after 8 minutes without events; a turn the bridge confirms active gets a fresh 8-minute window (not a permanent busy); the overall 45-minute cap still holds.

This file has no unit tests (Android Activity). Verification is compilation plus the full existing suite, and behavior is covered from both sides: `StallWatchdogTest` (Task 1) and the bridge-side induced-failure tests (Task 3).

- [ ] **Step 1: Replace the watchdog fields**

At `MainActivity.java:157-158` replace:

```java
    private Runnable watchdog;
    private OperationId watchdogOperationId;
```

with:

```java
    private Runnable watchdog;
    private StallWatchdog stallState;
```

- [ ] **Step 2: Replace the arm/fire implementation**

Replace the three methods `armWatchdog(OperationId, OperationKind)`, `armRestoredWatchdog(OperationRecord)` and `armWatchdog(OperationId, OperationKind, long)` (`MainActivity.java:1482-1520`) with:

```java
    private void armWatchdog(OperationId operationId, OperationKind kind) {
        stallState = new StallWatchdog(operationId, kind, System.currentTimeMillis());
        scheduleWatchdogCheck();
    }

    private void armRestoredWatchdog(OperationRecord operation) {
        stallState = new StallWatchdog(
                operation.operationId,
                operation.kind,
                operation.createdAtMs,
                System.currentTimeMillis()
        );
        scheduleWatchdogCheck();
    }

    private void scheduleWatchdogCheck() {
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        if (stallState == null) return;
        StallWatchdog armed = stallState;
        watchdog = () -> {
            watchdog = null;
            if (stallState != armed) return;
            OperationId operationId = armed.operationId();
            if (!busy || !operationId.equals(operations.activeOperationId())) return;
            long now = System.currentTimeMillis();
            if (armed.verdict(now) == StallWatchdog.Verdict.WAIT) {
                scheduleWatchdogCheck();
                return;
            }
            long silent = armed.silentForMs(now);
            stallState = null;
            operations.timeout(operationId);
            setBusy(true, "Ответа нет");
            reportWatchdog(operationId, armed.kind(), silent);
            if (armed.kind() == OperationKind.AGENT_TURN
                    || armed.kind() == OperationKind.NEW_SESSION
                    || armed.kind() == OperationKind.COMPACT_SESSION) {
                io.execute(() -> {
                    try {
                        JSONObject state = rpc.state();
                        runOnUiThread(() -> handleBridgeState(state));
                    } catch (Exception error) {
                        runOnUiThread(() -> bridgeFault = safeException(error));
                    }
                });
            }
        };
        main.postDelayed(
                watchdog,
                Math.max(1_000L, armed.nextCheckDelayMs(System.currentTimeMillis()))
        );
    }
```

Note the two intentional changes against the old body: `COMPACT_SESSION` now also triggers the `/state` check (it can stall the same way), and `reportWatchdog` receives the silent-window duration rather than the total deadline.

- [ ] **Step 3: Update `reportWatchdog` copy and its «Ждать ещё» action**

In `reportWatchdog(...)` (`MainActivity.java:1526-1549`), replace the description line:

```java
                "Termux не ответил за " + (waited / 60_000L) + " мин. Так бывает, когда Android "
```

with:

```java
                "Событий не было " + Math.max(1L, waited / 60_000L) + " мин. Так бывает, когда Android "
```

and replace the primary action body:

```java
        failure.primary("Ждать ещё", () -> {
            append(ConsoleEntry.Channel.SYSTEM, "Жду ещё; операция " + operationId + ".");
            armWatchdog(operationId, kind, kind.timeoutMs());
        });
```

with:

```java
        failure.primary("Ждать ещё", () -> {
            append(ConsoleEntry.Channel.SYSTEM, "Жду ещё; операция " + operationId + ".");
            armWatchdog(operationId, kind);
        });
```

- [ ] **Step 4: Update `cancelWatchdog`**

Replace the method (`MainActivity.java:1551-1560`) with:

```java
    private void cancelWatchdog(OperationId completedOperationId) {
        if (completedOperationId != null
                && stallState != null
                && !completedOperationId.equals(stallState.operationId())) {
            return;
        }
        if (watchdog != null) main.removeCallbacks(watchdog);
        watchdog = null;
        stallState = null;
    }
```

- [ ] **Step 5: Feed bridge events into the stall window**

In `handleBridgeEvent(BridgeEvent event)` (`MainActivity.java:3337`), immediately after `observedBridgeInstance = event.bridgeInstanceId;` insert:

```java
        if (event.operationId != null
                && stallState != null
                && stallState.progress(event.operationId, System.currentTimeMillis())) {
            scheduleWatchdogCheck();
        }
```

- [ ] **Step 6: Re-arm after a bridge-confirmed reconcile — without counting it as progress**

In `handleBridgeState`, inside the `active.operationId.toString().equals(remote)` branch (after `operations.reconcileRunning(active.operationId);`, `MainActivity.java:3260`), insert:

```java
                if (stallState == null) {
                    armRestoredWatchdog(active);
                }
```

This is the loop that fixes the permanent hang: fire → `UNKNOWN` → `/state` says active → `reconcileRunning` → a *fresh* stall window → if the turn is still silent, the card returns in 8 minutes with the abort choice. The 5-second state refresh itself never calls `progress()`, so a bridge that merely claims the turn is active cannot silence the watchdog.

- [ ] **Step 7: Compile and run the full Java suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mkdir -p /tmp/gradle-modules-2
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. If compilation reports other `armWatchdog(id, kind, timeout)` call sites the grep missed, convert them to `armWatchdog(id, kind)`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/pideck/app/MainActivity.java
git commit -m "fix: a hung turn is detected by silence, not by a 45-minute deadline"
```

---

### Task 3: Bridge-side induced-failure test

**Files:**
- Test: `tests/runtime/test_runtime.py` (append to `RuntimeTestCase`)

**Interfaces:**
- Consumes: `fake_bridge()` factory, `FakeChild`, `operation_id()`, `value.handle_pi_message(...)`, `value.handle_pi_exit(code, expected, source=...)`, `value.journal.after(0, 0)` — all existing in this file (see `test_stale_pi_child_callbacks_cannot_mutate_a_new_turn` for the idiom).
- Produces: regression coverage for the client-side contract Task 2 relies on — an unexpected Pi death mid-turn yields an authoritative `TURN_FAILED`, so the Android watchdog's `/state` reconcile finds either a terminal event or no active operation, never a phantom active turn.

The spec names three induced failures. The other two are already pinned by
existing tests and need no new code: a broken HTTP connection resolves through
`onDisconnected` → `UNKNOWN` → reconnect reconcile (`BridgeFaultPolicyTest`),
and a suppressed terminal event resolves through
`SessionContract.mayDeclareTerminalEventMissing` (`SessionContractTest`). Only
the Pi-death contract had no dedicated bridge-side test.

- [ ] **Step 1: Write the test**

Append to `RuntimeTestCase` in `tests/runtime/test_runtime.py`:

```python
    def test_unexpected_pi_death_mid_turn_fails_closed(self) -> None:
        value = fake_bridge()
        operation = operation_id()
        value.active_operation_id = operation
        value.active_operation_kind = "prompt"
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {"type": "text_delta", "delta": "Начал"},
            },
            source=value.child,
        )

        value.handle_pi_exit(9, False, source=value.child)

        self.assertIsNone(value.active_operation_id)
        _gap, events = value.journal.after(0, 0)
        failed = [event for event in events if event["type"] == "TURN_FAILED"]
        self.assertEqual(operation, failed[-1]["operationId"])
        self.assertTrue(any(event["type"] == "PI_EXITED" for event in events))
        self.assertFalse(any(event["type"] == "TURN_COMPLETED" for event in events))
```

- [ ] **Step 2: Run the test**

```bash
python3 -m pytest tests/runtime/test_runtime.py -k unexpected_pi_death -v
```

Expected: PASS — the bridge already implements this (`bridge.py` `handle_pi_exit`). This test pins the contract so a future bridge change cannot silently break the watchdog's reconcile assumption. If it FAILS, stop and report: that is a real pre-existing bug, not a test problem — do not adjust the assertions to match observed behavior.

- [ ] **Step 3: Run the full runtime suite**

```bash
python3 -m pytest tests/runtime/test_runtime.py -q
```

Expected: all pass (same count of skips as on `main`).

- [ ] **Step 4: Commit**

```bash
git add tests/runtime/test_runtime.py
git commit -m "test: pin that an unexpected pi death fails the active turn"
```

---

### Task 4: Changelog and bridge doc note

**Files:**
- Modify: `CHANGELOG.md` (раздел «Не выпущено»)
- Modify: `docs/rpc-bridge.md` (короткая секция о клиентской сверке)

**Interfaces:**
- Consumes: behavior contract from Task 2.
- Produces: user-facing record; no code.

- [ ] **Step 1: Add the changelog entry**

Append this bullet to the «Не выпущено» list in `CHANGELOG.md`, matching the existing style (behavior, not commits):

```markdown
- зависший turn обнаруживается по отсутствию прогресса, а не по общему
  таймауту: если по активной операции не пришло ни одного события за 8 минут
  (окно покрывает измеренные 173 с молчаливого replay контекста), дека
  переводит операцию в UNKNOWN, сверяется с авторитетным `/state` и показывает
  карточку с выбором «ждать/прервать» вместо 45-минутного молчания. Общий
  предел 45 минут сохраняется. Turn, который bridge подтверждает активным,
  получает новое окно наблюдения, а не вечный busy; подтверждение активности
  само по себе прогрессом не считается.
```

- [ ] **Step 2: Add the doc note**

In `docs/rpc-bridge.md`, append a short section (append at end of file):

```markdown
## Client stall reconciliation

The Android client arms a progress-based watchdog per operation. Every
delivered bridge event of that exact operation re-arms an 8-minute stall
window (`OperationKind.stallTimeoutMs`); the pre-existing overall deadline
(45 minutes for an agent turn) still caps the whole operation. On expiry the
client moves the operation to `UNKNOWN`, fetches `/v1/state`, and either
reconciles a terminal outcome or, if the bridge still reports the operation
active, shows the wait/abort card and starts a fresh window. A `/v1/state`
confirmation is deliberately not progress — only events are.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md docs/rpc-bridge.md
git commit -m "docs: record the stall watchdog contract"
```

---

## Out of scope for this plan

Stages 1–4 of the spec (new llama.cpp pin, LFM2-8B-A1B admission, OpenCL
prefill, resource policy) get their own plans after this stage's gate closes —
their contents depend on measured results that do not exist yet. The spec's
device-level gate for Stage 0 («0 зависаний в смоках») runs with the release
smokes on the phone; this plan's exit criterion is green unit and runtime
suites plus the pinned bridge contract test.
