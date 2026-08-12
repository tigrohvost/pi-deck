# Stage 4: Resource Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The core stops itself after a configurable idle timeout instead of living forever; thermal degradation becomes visible (with an optional pre-dispatch «передышка»); a backgrounded deck says honestly in its notification that it is slow (Stage 4 of `docs/superpowers/specs/2026-08-10-performance-large-models-design.md`, the only remaining open stage).

**Architecture:** Three independent features around the existing `NativeLlamaService` foreground service and Core settings. (A) A pure `IdleShutdown` policy + a `Handler` timer inside the service, armed on READY/idle actions and cancelled on inference/start/stop; on expiry the existing `stopAndExit()` path runs and the Activity explains it on next resume. (B) A pure `ThermalHeadroom` sysfs reader (cpu7 `scaling_max_freq / cpuinfo_max_freq`, same definition as `tools/speculative_probe.py:94`) enriching the existing `warnIfHot()` notice, plus an opt-in pacing gate before the two dispatch sites. (C) Activity lifecycle drives a background flag in the service; `promote()` appends an honest «фон: медленно» suffix; a scratch-build device experiment decides whether affinity relaxation is worth a production design. All new decision logic lives in pure static classes with JUnit tests; the service and Activity stay thin.

**Tech Stack:** Java 17 (app module), JUnit 4 local unit tests, existing `SharedPreferences` pattern (`DeckPreferences`), `adb` device gates.

## Global Constraints

- Build needs JDK 21: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (default java 25 fails with a bare `25.0.3`).
- After a host reboot: `mkdir -p /tmp/gradle-modules-2` or every Gradle build fails.
- The native runtime pin `b10092` and everything in `tools/vendor_llama_android.sh` are untouched.
- Fail-closed discipline (spec): every new path (idle timer, pacing, background hint) degrades to today's behavior when its input is unavailable — sysfs unreadable → no notice, pref missing → defaults, timer race with a new inference → no stop.
- The idle timer must never kill an active operation: any `ACTION_INFERENCE_ACTIVE` or `ACTION_START` cancels it, and the fire path re-checks state before stopping.
- UI copy in Russian and English through the existing `t(ru, en)` helper; CORE controls follow the `segments(...)` pattern of `CoreRootView.java:200-248`.
- Settings keys are versioned with `_v1` like every key in `DeckPreferences.java:21-41`.
- Device of record: SM-S918B over adb (serial `R5CW11HGLVV`), release-adb-signed installs use the local debug keystore recipe recorded in this repo's `dist/` naming.
- Commit style: lowercase conventional, behavior-describing. `git add` only files each task names.
- Work on a feature branch (e.g. `perf/stage4-resource-policy`) in an isolated worktree.

---

### Task 1: `IdleShutdown` policy + preference (TDD)

**Files:**
- Create: `app/src/main/java/dev/pideck/app/core/IdleShutdown.java`
- Modify: `app/src/main/java/dev/pideck/app/core/DeckPreferences.java` (new key + getter/setter near `autostartCore()`, `DeckPreferences.java:149-155`)
- Test: `app/src/test/java/dev/pideck/app/core/IdleShutdownTest.java`

**Interfaces:**
- Produces: `IdleShutdown.NEVER = 0L`; `IdleShutdown.DEFAULT_MINUTES = 10L`; `static boolean enabled(long timeoutMinutes)`; `static long delayMs(long timeoutMinutes)`; `static boolean normalized(long)` → allowed set {0, 5, 10, 30}; `DeckPreferences.coreIdleTimeoutMinutes()` / `setCoreIdleTimeoutMinutes(long)`. Tasks 2 and 6 rely on these exact names.

- [ ] **Step 1: Write the failing test**

```java
package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleShutdownTest {

    @Test
    public void neverDisablesTheTimer() {
        assertFalse(IdleShutdown.enabled(IdleShutdown.NEVER));
        assertTrue(IdleShutdown.enabled(IdleShutdown.DEFAULT_MINUTES));
    }

    @Test
    public void delayIsMinutesInMilliseconds() {
        assertEquals(600_000L, IdleShutdown.delayMs(10L));
        assertEquals(300_000L, IdleShutdown.delayMs(5L));
    }

    @Test
    public void onlyOfferedValuesAreNormalized() {
        assertTrue(IdleShutdown.normalized(0L));
        assertTrue(IdleShutdown.normalized(5L));
        assertTrue(IdleShutdown.normalized(10L));
        assertTrue(IdleShutdown.normalized(30L));
        assertFalse(IdleShutdown.normalized(7L));
        assertFalse(IdleShutdown.normalized(-1L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest --tests '*IdleShutdownTest'`
Expected: FAIL — `IdleShutdown` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.pideck.app.core;

/**
 * Pure policy for the core idle timeout. 0 means "never stop", which is exactly
 * the pre-Stage-4 behavior; any other offered value is minutes of idleness after
 * which the foreground service stops the resident llama-server.
 */
public final class IdleShutdown {

    public static final long NEVER = 0L;
    public static final long DEFAULT_MINUTES = 10L;

    private IdleShutdown() {}

    public static boolean enabled(long timeoutMinutes) {
        return timeoutMinutes != NEVER;
    }

    public static long delayMs(long timeoutMinutes) {
        return timeoutMinutes * 60_000L;
    }

    public static boolean normalized(long timeoutMinutes) {
        return timeoutMinutes == NEVER
                || timeoutMinutes == 5L
                || timeoutMinutes == 10L
                || timeoutMinutes == 30L;
    }
}
```

In `DeckPreferences.java` add the key next to `KEY_AUTOSTART_CORE` (line 39):

```java
    private static final String KEY_CORE_IDLE_TIMEOUT = "core_idle_timeout_v1";
```

and the accessor pair after `setAutostartCore` (line 155):

```java
    /**
     * Minutes of idleness after which the resident core stops itself; 0 keeps the
     * pre-Stage-4 "runs until stopped by hand" behavior. Stored values outside the
     * offered set fall back to the default rather than arming a surprising timer.
     */
    public long coreIdleTimeoutMinutes() {
        long stored = prefs.getLong(KEY_CORE_IDLE_TIMEOUT, IdleShutdown.DEFAULT_MINUTES);
        return IdleShutdown.normalized(stored) ? stored : IdleShutdown.DEFAULT_MINUTES;
    }

    public void setCoreIdleTimeoutMinutes(long minutes) {
        prefs.edit().putLong(
                KEY_CORE_IDLE_TIMEOUT,
                IdleShutdown.normalized(minutes) ? minutes : IdleShutdown.DEFAULT_MINUTES
        ).apply();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests '*IdleShutdownTest'` — PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/pideck/app/core/IdleShutdown.java \
        app/src/main/java/dev/pideck/app/core/DeckPreferences.java \
        app/src/test/java/dev/pideck/app/core/IdleShutdownTest.java
git commit -m "feat: pure idle-shutdown policy and core timeout preference"
```

---

### Task 2: Idle timer in the service + CORE setting + resume explanation

**Files:**
- Modify: `app/src/main/java/dev/pideck/app/core/NativeLlamaService.java` (actions at 33-39, `onStartCommand` 151-193, `stopAndExit` 326-342)
- Modify: `app/src/main/java/dev/pideck/app/ui/CoreRootView.java` (`State` 101-120, `Listener` 122-138, render 222-248)
- Modify: `app/src/main/java/dev/pideck/app/ui/DeckView.java` (listener relay near 684-686)
- Modify: `app/src/main/java/dev/pideck/app/MainActivity.java` (`onAutostartCoreChanged` 577-592 as the pattern; `renderCoreRoot` ~2519-2528; `onResume` 300-308)

**Interfaces:**
- Consumes: `IdleShutdown`, `DeckPreferences.coreIdleTimeoutMinutes()` from Task 1.
- Produces: service action `ACTION_IDLE_REARM`; static `NativeLlamaService.rearmIdleTimer(Context)`; service PREFS flag `"idle_stop"` (boolean) consumed by `MainActivity.onResume`; `Listener.onCoreIdleTimeoutChanged(long minutes)`; `State.coreIdleTimeoutMinutes`.

- [ ] **Step 1: Service timer**

In `NativeLlamaService`, add a main-looper handler and one runnable field near the other fields:

```java
    private final android.os.Handler idleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleShutdown = this::onIdleTimeout;
```

Add two private methods:

```java
    /** Armed only in READY/idle states; any activity cancels before it can fire. */
    private void armIdleTimer() {
        idleHandler.removeCallbacks(idleShutdown);
        long minutes = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(minutes)) return;
        idleHandler.postDelayed(idleShutdown, IdleShutdown.delayMs(minutes));
    }

    private void onIdleTimeout() {
        Snapshot current = snapshot(this);
        // Fail closed: a race with a starting or answering core means no stop.
        if (!"READY".equals(current.state)) return;
        long minutes = new DeckPreferences(this).coreIdleTimeoutMinutes();
        if (!IdleShutdown.enabled(minutes)) return;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("idle_stop", true).apply();
        promote("Останавливаю ядро: бездействие " + minutes + " мин");
        new Thread(this::stopAndExit, "pideck-native-llama-idle-stop").start();
    }
```

Wire the timer into `onStartCommand`:
- in the `ACTION_READY` branch (line 153-156): call `armIdleTimer();` after `promote(...)`;
- in the `ACTION_INFERENCE_ACTIVE` branch (157-162): call `idleHandler.removeCallbacks(idleShutdown);` first;
- in the `ACTION_INFERENCE_IDLE` branch (163-167): call `armIdleTimer();` after `promote(...)`;
- in the `ACTION_STOP` branch (168-172) and before `launch` in the `ACTION_START` tail: `idleHandler.removeCallbacks(idleShutdown);`
- add a new action constant `ACTION_IDLE_REARM = "dev.pideck.app.native.IDLE_REARM"` next to the others (33-39), a branch in `onStartCommand` that calls `armIdleTimer()` only when the current snapshot state is `"READY"`, and the static entry:

```java
    public static void rearmIdleTimer(Context context) {
        context.startService(
                new Intent(context, NativeLlamaService.class).setAction(ACTION_IDLE_REARM)
        );
    }
```

Also clear the timer in `onDestroy()` (196-203): `idleHandler.removeCallbacks(idleShutdown);`.

- [ ] **Step 2: CORE UI control**

`CoreRootView.State` (101-120): add `public long coreIdleTimeoutMinutes = 10L;`.
`CoreRootView.Listener` (122-138): add `void onCoreIdleTimeoutChanged(long minutes);`.
In the render, directly after the autostart `segments(...)` block (222-227), add a note + four-segment control following the autostart-note pattern (207-221):

```java
        TextView idleNote = style.bodySecondary(
                t(
                        "Таймаут ядра: без запросов модель выгружается сама и освобождает память. "
                                + "«Всегда» повторяет старое поведение — ядро живёт до ручной остановки.",
                        "Core timeout: after this much idle time the model unloads itself and frees "
                                + "memory. “Always” keeps the old behavior — the core lives "
                                + "until stopped by hand."
                )
        );
        LinearLayout.LayoutParams idleNoteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        idleNoteLp.topMargin = style.dp(14);
        idleNoteLp.bottomMargin = style.dp(8);
        column.addView(idleNote, idleNoteLp);
        column.addView(segments(
                new String[]{"5м", "10м", "30м", t("Всегда", "Always")},
                new Long[]{5L, 10L, 30L, 0L},
                state.coreIdleTimeoutMinutes,
                value -> listener.onCoreIdleTimeoutChanged((Long) value)
        ));
```

(The `t("5м","5m")`-style localization of the short labels may follow the file's conventions; keep the value array exactly `{5L, 10L, 30L, 0L}`.)

- [ ] **Step 3: Relay and Activity handler**

`DeckView.java` (~684-686): forward the new listener method to its own `Listener` the same way `onAutostartCoreChanged` is forwarded, and add the method to `DeckView`'s listener interface (near `DeckView.java:94`).

`MainActivity`: implement, modeled on `onAutostartCoreChanged` (577-592):

```java
    @Override
    public void onCoreIdleTimeoutChanged(long minutes) {
        prefs.setCoreIdleTimeoutMinutes(minutes);
        append(ConsoleEntry.Channel.SYSTEM, minutes == IdleShutdown.NEVER
                ? t(
                        "Ядро будет жить до ручной остановки.",
                        "The core will live until stopped by hand."
                )
                : t(
                        "Ядро будет останавливаться после " + minutes + " мин без запросов.",
                        "The core will stop after " + minutes + " min of idleness."
                ));
        NativeLlamaService.rearmIdleTimer(this);
        refreshUi();
    }
```

In `renderCoreRoot()` (~2519-2528) populate `state.coreIdleTimeoutMinutes = prefs.coreIdleTimeoutMinutes();`.

- [ ] **Step 4: Resume explanation**

In `MainActivity.onResume()` (300-308), after the `unconsumedResults` loop and before `refreshUi()`:

```java
        android.content.SharedPreferences nativeState =
                getSharedPreferences("native_llama", MODE_PRIVATE);
        if (nativeState.getBoolean("idle_stop", false)) {
            nativeState.edit().remove("idle_stop").apply();
            append(ConsoleEntry.Channel.SYSTEM, t(
                    "Ядро остановилось по таймауту бездействия (настройка — в ЯДРО).",
                    "The core stopped on the idle timeout (configured in CORE)."
            ));
        }
```

Before writing this step, confirm the service's `PREFS` constant value (near `NativeLlamaService.java:40-60`) and use the same literal instead of `"native_llama"` if it differs.

- [ ] **Step 5: Build, test, commit**

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest lintDebug assembleDebug
git add app/src/main/java/dev/pideck/app/core/NativeLlamaService.java \
        app/src/main/java/dev/pideck/app/ui/CoreRootView.java \
        app/src/main/java/dev/pideck/app/ui/DeckView.java \
        app/src/main/java/dev/pideck/app/MainActivity.java
git commit -m "feat: the core stops itself after a configurable idle timeout"
```

---

### Task 3: `ThermalHeadroom` + enriched thermal notice (TDD)

**Files:**
- Create: `app/src/main/java/dev/pideck/app/core/ThermalHeadroom.java`
- Modify: `app/src/main/java/dev/pideck/app/MainActivity.java:642-659` (`warnIfHot`)
- Test: `app/src/test/java/dev/pideck/app/core/ThermalHeadroomTest.java`

**Interfaces:**
- Produces: `static Float parse(String scalingMaxRaw, String cpuinfoMaxRaw)` (null on garbage); `static Float read()` (cpu7 sysfs, null on any I/O failure); Task 4 consumes both.

- [ ] **Step 1: Failing test**

```java
package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ThermalHeadroomTest {

    @Test
    public void parsesFractionOfNominalClock() {
        assertEquals(0.55f, ThermalHeadroom.parse("1848000\n", "3360000\n"), 0.001f);
        assertEquals(1.0f, ThermalHeadroom.parse("3360000", "3360000"), 0.001f);
    }

    @Test
    public void refusesGarbageFailClosed() {
        assertNull(ThermalHeadroom.parse("", "3360000"));
        assertNull(ThermalHeadroom.parse("abc", "3360000"));
        assertNull(ThermalHeadroom.parse("1848000", "0"));
        assertNull(ThermalHeadroom.parse(null, null));
    }
}
```

Run `./gradlew testDebugUnitTest --tests '*ThermalHeadroomTest'` — FAIL (class missing).

- [ ] **Step 2: Implementation**

```java
package dev.pideck.app.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fraction of the big core's nominal clock the thermal governor still allows.
 * Same definition as tools/speculative_probe.py: scaling_max_freq / cpuinfo_max_freq
 * on cpu7. Any read or parse failure returns null and the caller stays silent —
 * a thermal indicator must never itself break dispatch.
 */
public final class ThermalHeadroom {

    private static final Path BASE = Path.of("/sys/devices/system/cpu/cpu7/cpufreq");

    private ThermalHeadroom() {}

    public static Float read() {
        try {
            return parse(
                    Files.readString(BASE.resolve("scaling_max_freq")),
                    Files.readString(BASE.resolve("cpuinfo_max_freq"))
            );
        } catch (Exception error) {
            return null;
        }
    }

    public static Float parse(String scalingMaxRaw, String cpuinfoMaxRaw) {
        if (scalingMaxRaw == null || cpuinfoMaxRaw == null) return null;
        try {
            long scaling = Long.parseLong(scalingMaxRaw.trim());
            long nominal = Long.parseLong(cpuinfoMaxRaw.trim());
            if (scaling <= 0 || nominal <= 0) return null;
            return (float) scaling / (float) nominal;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
```

- [ ] **Step 3: Enrich `warnIfHot()`**

Replace the body of `warnIfHot()` (`MainActivity.java:642-659`) so a measured headroom both lowers the trigger threshold and appears in the copy; the PowerManager path stays as fallback:

```java
    private void warnIfHot() {
        if (thermalWarned) return;
        Float headroom = ThermalHeadroom.read();
        int status = 0;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) status = power.getCurrentThermalStatus();
        }
        boolean throttled = (headroom != null && headroom < 0.85f)
                || status >= PowerManager.THERMAL_STATUS_MODERATE;
        if (!throttled) return;
        thermalWarned = true;
        String clock = headroom == null ? "" : t(
                " Сейчас доступно " + Math.round(headroom * 100) + "% частоты.",
                " " + Math.round(headroom * 100) + "% of the clock is available now."
        );
        deck.addThermalNotice((status >= PowerManager.THERMAL_STATUS_SEVERE
                || (headroom != null && headroom < 0.6f))
                ? t(
                        "Телефон сильно нагрелся — Android режет частоту, ответ будет заметно дольше.",
                        "The phone is very hot — Android is throttling it, so the answer will take noticeably longer."
                ) + clock
                : t(
                        "Телефон нагрелся — скорость упала.",
                        "The phone is hot — generation has slowed down."
                ) + clock);
        prefs.saveTranscript(deck.entries());
    }
```

- [ ] **Step 4: Test + commit**

```bash
./gradlew testDebugUnitTest --tests '*ThermalHeadroomTest' && ./gradlew testDebugUnitTest lintDebug
git add app/src/main/java/dev/pideck/app/core/ThermalHeadroom.java \
        app/src/test/java/dev/pideck/app/core/ThermalHeadroomTest.java \
        app/src/main/java/dev/pideck/app/MainActivity.java
git commit -m "feat: thermal notice reports measured clock headroom"
```

---

### Task 4: Optional pre-dispatch pacing («передышка»)

**Files:**
- Modify: `app/src/main/java/dev/pideck/app/core/DeckPreferences.java` (key `thermal_pacing_v1`, boolean, default false, pattern of `maximumSpeed()` at 137-143)
- Modify: `app/src/main/java/dev/pideck/app/ui/CoreRootView.java` (+State/Listener/segments, directly after the idle-timeout block of Task 2)
- Modify: `app/src/main/java/dev/pideck/app/ui/DeckView.java` (relay)
- Modify: `app/src/main/java/dev/pideck/app/MainActivity.java` (handler + gate at the two dispatch sites)
- Test: extend `app/src/test/java/dev/pideck/app/core/ThermalHeadroomTest.java`

**Interfaces:**
- Consumes: `ThermalHeadroom.read()`/`parse` from Task 3.
- Produces: `static boolean ThermalHeadroom.shouldPace(Float headroom, boolean pacingEnabled)`; `DeckPreferences.thermalPacing()`/`setThermalPacing(boolean)`; `Listener.onThermalPacingChanged(boolean)`.

- [ ] **Step 1: Failing test additions**

```java
    @Test
    public void pacingTriggersOnlyWhenEnabledAndThrottled() {
        assertEquals(false, ThermalHeadroom.shouldPace(null, true));
        assertEquals(false, ThermalHeadroom.shouldPace(0.5f, false));
        assertEquals(false, ThermalHeadroom.shouldPace(0.9f, true));
        assertEquals(true, ThermalHeadroom.shouldPace(0.84f, true));
    }
```

Run — FAIL (method missing).

- [ ] **Step 2: Implementation**

In `ThermalHeadroom`:

```java
    /** Pace only on an explicit user opt-in and a measured (not assumed) throttle. */
    public static boolean shouldPace(Float headroom, boolean pacingEnabled) {
        return pacingEnabled && headroom != null && headroom < 0.85f;
    }
```

`DeckPreferences`: key `private static final String KEY_THERMAL_PACING = "thermal_pacing_v1";`, accessors `thermalPacing()` (default `false`) / `setThermalPacing(boolean)` — copy the `maximumSpeed` pair (137-143).

CORE UI (after Task 2's idle block), note + toggle:

```java
        column.addView(segments(
                new String[]{t("Передышка", "Cooldown wait"), t("Сразу", "Immediately")},
                new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                state.thermalPacing,
                value -> listener.onThermalPacingChanged((Boolean) value)
        ));
```

`MainActivity` handler follows `onSmartCompactionChanged` (594-601): persist, one SYSTEM line each way («Перед длинным ходом дека подождёт восстановления частоты (до 60 с).» / «Запросы уходят сразу, даже на троттлинге.»), `refreshUi()`.

- [ ] **Step 3: The pacing gate**

Add to `MainActivity`:

```java
    /**
     * When pacing is on and the clock is measurably cut, wait up to 60 s in 5 s
     * steps for recovery to >=95% before running the dispatch. The wait is
     * visible in the console and always bounded; any read failure dispatches now.
     */
    private void paceThermalThenRun(Runnable dispatch) {
        if (!ThermalHeadroom.shouldPace(ThermalHeadroom.read(), prefs.thermalPacing())) {
            dispatch.run();
            return;
        }
        Float now = ThermalHeadroom.read();
        append(ConsoleEntry.Channel.SYSTEM, t(
                "Передышка: жду восстановления частоты (сейчас "
                        + Math.round(now * 100) + "%), максимум 60 с…",
                "Cooldown wait: letting the clock recover (now "
                        + Math.round(now * 100) + "%), up to 60 s…"
        ));
        paceThermalPoll(dispatch, android.os.SystemClock.uptimeMillis() + 60_000L);
    }

    private void paceThermalPoll(Runnable dispatch, long deadlineUptimeMs) {
        Float headroom = ThermalHeadroom.read();
        boolean recovered = headroom == null || headroom >= 0.95f;
        if (recovered || android.os.SystemClock.uptimeMillis() >= deadlineUptimeMs) {
            dispatch.run();
            return;
        }
        main.postDelayed(() -> paceThermalPoll(dispatch, deadlineUptimeMs), 5_000L);
    }
```

At the two dispatch sites that already call `warnIfHot()` — `sendPromptNow` (`MainActivity.java:465`) and `dispatchQueuedPromptNow` (`MainActivity.java:3171`) — wrap the code that follows `warnIfHot()` up to the actual dispatch call into the gate: read the function, extract the post-`warnIfHot` dispatch tail into a local `Runnable dispatch = () -> { …existing code… };` and end the function with `paceThermalThenRun(dispatch);`. The surrounding state checks (busy flags, approvals) stay OUTSIDE the runnable so a queued pace cannot bypass them; if the tail re-checks busy state internally today, keep those checks inside too — the gate must only add delay, never reorder logic.

- [ ] **Step 4: Test, build, commit**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
git add app/src/main/java/dev/pideck/app/core/ThermalHeadroom.java \
        app/src/test/java/dev/pideck/app/core/ThermalHeadroomTest.java \
        app/src/main/java/dev/pideck/app/core/DeckPreferences.java \
        app/src/main/java/dev/pideck/app/ui/CoreRootView.java \
        app/src/main/java/dev/pideck/app/ui/DeckView.java \
        app/src/main/java/dev/pideck/app/MainActivity.java
git commit -m "feat: optional cooldown wait before dispatch on a throttled clock"
```

---

### Task 5: Honest background indication

**Files:**
- Modify: `app/src/main/java/dev/pideck/app/core/NativeLlamaService.java` (`promote` 426-460, actions, statics 130-142)
- Modify: `app/src/main/java/dev/pideck/app/MainActivity.java` (`onStart` 289-298, `onStop` 310-318)
- Test: `app/src/test/java/dev/pideck/app/core/NativeLlamaServiceLabelsTest.java` (new, pure label logic)

**Interfaces:**
- Produces: `static NativeLlamaService.backgroundHint(Context, boolean)`; action `ACTION_BACKGROUND_HINT` + `EXTRA_BACKGROUND`; pure `static String NativeLlamaService.notificationText(String base, boolean background)`.

- [ ] **Step 1: Failing test**

```java
package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NativeLlamaServiceLabelsTest {

    @Test
    public void backgroundSuffixIsHonestAndOnlyInBackground() {
        assertEquals("Локальная модель готова",
                NativeLlamaService.notificationText("Локальная модель готова", false));
        assertEquals("Локальная модель готова · фон: медленно",
                NativeLlamaService.notificationText("Локальная модель готова", true));
    }
}
```

Run — FAIL.

- [ ] **Step 2: Implementation**

In `NativeLlamaService`:

```java
    private volatile boolean backgroundHint;
    private volatile String lastPromotedText = "Локальное ядро";

    /** The measured fact behind the suffix: a backgrounded deck decodes at ~1.5 tok/s. */
    static String notificationText(String base, boolean background) {
        return background ? base + " · фон: медленно" : base;
    }

    public static void backgroundHint(Context context, boolean background) {
        context.startService(
                new Intent(context, NativeLlamaService.class)
                        .setAction(ACTION_BACKGROUND_HINT)
                        .putExtra(EXTRA_BACKGROUND, background)
        );
    }
```

New constants next to the existing actions: `ACTION_BACKGROUND_HINT = "dev.pideck.app.native.BACKGROUND_HINT"`, `EXTRA_BACKGROUND = "background"`.

In `onStartCommand`, add the branch:

```java
        if (ACTION_BACKGROUND_HINT.equals(action)) {
            backgroundHint = intent.getBooleanExtra(EXTRA_BACKGROUND, false);
            promote(lastPromotedText);
            return START_NOT_STICKY;
        }
```

In `promote(String text)` (426): first line becomes

```java
        lastPromotedText = text;
        String shown = notificationText(text, backgroundHint);
```

and `setContentText(text)` (441) becomes `setContentText(shown)`.

Guard: `promote` is called from `onStartCommand` before the service ever ran `ACTION_START` too; the default `backgroundHint=false` keeps today's texts byte-identical when the deck is foreground.

`MainActivity`: in `onStart()` after `activityStarted = true;` add `NativeLlamaService.backgroundHint(this, false);`; in `onStop()` after `activityStarted = false;` add `NativeLlamaService.backgroundHint(this, true);`.

- [ ] **Step 3: Test, build, commit**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
git add app/src/main/java/dev/pideck/app/core/NativeLlamaService.java \
        app/src/test/java/dev/pideck/app/core/NativeLlamaServiceLabelsTest.java \
        app/src/main/java/dev/pideck/app/MainActivity.java
git commit -m "feat: the core notification says honestly that background is slow"
```

---

### Task 6: Documentation

**Files:**
- Modify: `CHANGELOG.md` («Не выпущено», top)
- Modify: `README.md` + `README.ru.md` («Core settings» / «Настройки ЯДРО» lists)

- [ ] **Step 1: CHANGELOG bullet (Russian, behavior-first)**

```markdown
- политика ресурсов этапа 4: ядро останавливается само после настраиваемого
  таймаута бездействия (5/10/30 мин или «Всегда», дефолт 10 мин, настройка в
  ЯДРО) с объяснением в консоли; термопредупреждение теперь показывает
  измеренную долю доступной частоты big-ядер и срабатывает по ней, а не только
  по грубому статусу Android; опциональная «передышка» перед отправкой ждёт
  восстановления частоты до 60 с; нотификация foreground-сервиса честно
  помечает «фон: медленно», пока дека свёрнута;
```

- [ ] **Step 2: README setting lists**

Add to `README.md` «Core settings» (107-113): `- **Core timeout** — the idle model unloads itself after 5/10/30 minutes, or never;` and `- **Cooldown wait** — optionally wait up to 60 s for the clock to recover before dispatching;`. Mirror in `README.ru.md` («Настройки ЯДРО», 105-111): `- **Таймаут ядра** — простаивающая модель выгружается через 5/10/30 минут или живёт всегда;` and `- **Передышка** — опционально ждать восстановления частоты (до 60 с) перед отправкой;`.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md README.md README.ru.md
git commit -m "docs: stage 4 resource policy settings"
```

---

### Task 7: Device gate (REQUIRED, blocks merge)

Preconditions: SM-S918B on adb; debug APK of this branch installed (`./gradlew assembleDebug`, `adb install -r`); Qwen3.5 2B selected; autostart on.

- [ ] **Step 1: Idle timeout fires and explains itself**

Set «Таймаут ядра» = 5м in CORE. Warm the core, send one short prompt, wait for the answer, leave the deck open and idle. Expected within ~5 min of the terminal event: notification text «Останавливаю ядро: бездействие 5 мин», then the notification disappears; `adb shell "pidof -s libpideck_llama_server.so || echo dead"` prints `dead`. Reopen/resume the deck: one SYSTEM line «Ядро остановилось по таймауту бездействия…». Then set «Всегда», repeat idle wait 6+ min: server stays alive.

- [ ] **Step 2: Timer never kills activity**

Set 5м, start a prompt whose answer takes >1 min (e.g. «напиши 400 слов о…»), confirm no stop occurs during generation and the timer re-arms only after the terminal event (server still alive 4 min into the answer's idle tail, dead after ~5).

- [ ] **Step 3: Thermal notice with a number**

Warm the phone (2-3 long prompts back to back). Expected on a later dispatch: the inline thermal card contains «Сейчас доступно NN% частоты» with NN < 100 matching `adb shell cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq` ÷ `cpuinfo_max_freq`.

- [ ] **Step 4: Pacing observable and bounded**

Enable «Передышка» on the warm phone, send a prompt. Expected: SYSTEM line «Передышка: жду восстановления частоты…», dispatch happens within 60 s regardless. Disable — dispatch is immediate again.

- [ ] **Step 5: Background honesty**

With the core READY, press HOME. Expected notification text gains « · фон: медленно»; return to the deck — suffix disappears. Verify no behavior change beyond text (server keeps running, prompts still dispatch on return).

- [ ] **Step 6: Record and commit evidence**

Append a short dated «Этап 4, смок на устройстве» list (pass/fail per step) to `docs/performance.md` and commit as `docs: stage 4 device smoke evidence`.

---

### Task 8: Device experiment — background affinity (measured, decides a follow-up)

The measured pain: a backgrounded deck decodes at ~1.5 tok/s because the server leaves the `top-app` cpuset while llama.cpp threads stay hard-pinned to `3-7` with `--cpu-strict 1` (`ModelSpec.java:441-451`). Question: does relaxing the pin help when confined, or is the cpuset itself the whole penalty?

- [ ] **Step 1: Scratch build**

On a throwaway branch off this one, patch `ModelSpec.nativeLlamaServerArguments` to omit `-Cr/--cpu-strict/-Crb/--cpu-strict-batch` (keep `-t`/`-tb`), build `assembleDebug`, install. This build is NEVER merged; it exists to answer one question.

- [ ] **Step 2: A/B under a real background**

For each build (strict = this branch, relaxed = scratch): warm the core, background the deck (HOME), from Termux run 3 authenticated completions via `python3 tools/adb_llama_probe.py --max-tokens 128 --output ~/probe-<variant>.json` (it reads `~/.pideck/server/api-key` and hits the app server), pull the JSONs, record decode tok/s. Same battery/thermal discipline as Stage 1 (≤30 °C start, no charge current).

- [ ] **Step 3: Verdict**

Relaxed ≥ 1.5× strict in background → write a follow-up design note (production re-affinity needs a managed-flag change and a policy for switching back on foreground). Anything less → record the refusal with numbers in `docs/performance.md» («перепиновка не спасает фон — штраф в cpuset, а не в маске») and the honest notification from Task 5 remains the complete answer. Either way reinstall the strict debug APK afterwards.

---

## Rollback

Each feature is a self-contained commit; `git revert` any of them independently. The idle timer's «Всегда» setting IS the old behavior, so a user-level rollback exists without code. No native runtime, catalog, or protocol changes anywhere in this plan.

## Explicitly out of scope

- Native pin, llama.cpp flags, OpenCL/Vulkan (stage 3 closed with a measured refusal).
- Production re-affinity switching (only the Task 8 experiment decides whether to design it).
- Keep-alive of the Termux bridge/Pi process (only the native core's lifecycle changes).
- suite-v2 additions.
