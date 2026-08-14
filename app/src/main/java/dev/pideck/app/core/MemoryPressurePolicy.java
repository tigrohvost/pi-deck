package dev.pideck.app.core;

/** Decides how to protect the phone without corrupting an active model turn. */
public final class MemoryPressurePolicy {
    public enum Action { NONE, WARN_ACTIVE_TURN, STOP_IDLE_CORE }

    private MemoryPressurePolicy() {
    }

    public static boolean critical(int trimLevel) {
        // ComponentCallbacks2: RUNNING_CRITICAL=15, MODERATE=60, COMPLETE=80.
        // UI_HIDDEN=20 and BACKGROUND=40 are lifecycle hints, not pressure signals.
        return trimLevel == 15 || trimLevel >= 60;
    }

    public static Action decide(int trimLevel, boolean coreReady, boolean inferenceActive) {
        if (!critical(trimLevel) || !coreReady) return Action.NONE;
        return inferenceActive ? Action.WARN_ACTIVE_TURN : Action.STOP_IDLE_CORE;
    }
}
