package dev.pideck.app.core;

import org.json.JSONObject;

import java.util.List;

/**
 * Single source of truth for operation ownership. UI labels may share a kind, identity never does.
 */
public final class OperationCoordinator {
    private final OperationStore store;
    private OperationId activeOperationId;

    public OperationCoordinator(OperationStore store) {
        this.store = store;
        // begin() is persisted before external dispatch. A CREATED/DISPATCHED record means the
        // process stopped before anything could be handed to Termux/RPC, so replay is unnecessary
        // and restoring a permanent busy state would be wrong. Close orphaned control records too.
        for (OperationRecord record : store.list()) {
            if (record.state == OperationState.CREATED
                    || record.state == OperationState.DISPATCHED) {
                store.fail(record.operationId, "Application stopped before external dispatch");
            }
        }
        OperationRecord active = store.latestActive();
        if (active != null) activeOperationId = active.operationId;
    }

    public synchronized OperationRecord begin(OperationKind kind, JSONObject request) {
        if (activeOperationId != null) {
            OperationRecord active = store.load(activeOperationId);
            if (active != null && !active.state.isTerminal()) {
                throw new IllegalStateException(
                        "Operation already active: " + active.kind + " " + active.operationId
                );
            }
            activeOperationId = null;
        }
        OperationRecord record = store.create(kind, request);
        activeOperationId = record.operationId;
        return record;
    }

    /** Control operations are persisted but do not replace the active agent turn. */
    public synchronized OperationRecord beginControl(OperationKind kind, JSONObject request) {
        if (kind != OperationKind.ABORT_AGENT && kind != OperationKind.RECONCILE) {
            throw new IllegalArgumentException("Not a control operation: " + kind);
        }
        return store.create(kind, request);
    }

    public synchronized void dispatched(OperationId id) {
        OperationRecord current = store.transition(id, OperationState.DISPATCHED);
        store.save(current.transition(OperationState.RUNNING, System.currentTimeMillis()));
    }

    public synchronized void dispatchFailed(OperationId id, String error) {
        store.fail(id, error);
        if (id.equals(activeOperationId)) activeOperationId = null;
    }

    /** A late failure callback may mutate state only while this exact operation owns the UI. */
    public synchronized boolean dispatchFailedIfActive(OperationId id, String error) {
        if (!id.equals(activeOperationId)) return false;
        OperationRecord record = store.load(id);
        if (record == null || record.state.isTerminal()) return false;
        store.fail(id, error);
        activeOperationId = null;
        return true;
    }

    /** Transport failure after send is ambiguous: retain ownership until bridge reconciliation. */
    public synchronized boolean dispatchUnknownIfActive(OperationId id) {
        if (!id.equals(activeOperationId)) return false;
        OperationRecord record = store.load(id);
        if (record == null || record.state.isTerminal()) return false;
        if (record.state != OperationState.UNKNOWN) {
            store.transition(id, OperationState.UNKNOWN);
        }
        return true;
    }

    /**
     * A package replacement kills the Activity and invalidates the installer payload that created
     * the active runtime-install operation. Do not restore that record as a 30-minute busy state;
     * the newly installed APK must generate and dispatch its own versioned payload.
     */
    public synchronized boolean failRuntimeInstallStartedBefore(long packageUpdatedAtMs) {
        if (activeOperationId == null || packageUpdatedAtMs <= 0L) return false;
        OperationRecord active = store.load(activeOperationId);
        if (active == null
                || active.state.isTerminal()
                || active.createdAtMs >= packageUpdatedAtMs
                || (active.kind != OperationKind.INSTALL_RUNTIME
                    && active.kind != OperationKind.UPDATE_RUNTIME)) {
            return false;
        }
        store.fail(
                active.operationId,
                "Application package changed during runtime installation; retry with the new APK"
        );
        activeOperationId = null;
        return true;
    }

    public synchronized void requestAbort(OperationId target) {
        OperationRecord record = store.load(target);
        if (record == null || record.state.isTerminal()) {
            throw new IllegalStateException("No running operation to abort");
        }
        if (record.state != OperationState.ABORT_REQUESTED) {
            store.transition(target, OperationState.ABORT_REQUESTED);
        }
    }

    public synchronized boolean onResult(CommandResult result) {
        store.recordResult(result);
        if (result.kind == OperationKind.ABORT_AGENT || result.kind == OperationKind.RECONCILE) {
            return true;
        }
        boolean ownsUi = result.operationId.equals(activeOperationId);
        if (ownsUi) activeOperationId = null;
        return ownsUi;
    }

    public synchronized void timeout(OperationId id) {
        OperationRecord record = store.load(id);
        if (record == null || record.state.isTerminal()) return;
        if (record.state != OperationState.UNKNOWN) {
            store.transition(id, OperationState.UNKNOWN);
        }
    }

    public synchronized void reconcileRunning(OperationId id) {
        if (!id.equals(activeOperationId)) return;
        OperationRecord record = store.load(id);
        if (record != null && record.state == OperationState.UNKNOWN) {
            store.transition(id, OperationState.RUNNING);
        }
    }

    /**
     * Reconcile an UNKNOWN operation only after the authoritative bridge state reports no matching
     * active operation and its event journal had a chance to deliver a terminal event.
     */
    public synchronized void reconcileTerminalMissing(OperationId id, String reason) {
        if (!id.equals(activeOperationId)) return;
        OperationRecord record = store.load(id);
        if (record == null || record.state.isTerminal()) {
            activeOperationId = null;
            return;
        }
        if (record.state != OperationState.UNKNOWN) {
            throw new IllegalStateException("Operation must be UNKNOWN before terminal reconcile");
        }
        store.fail(id, reason);
        activeOperationId = null;
    }

    public synchronized OperationRecord active() {
        return activeOperationId == null ? null : store.load(activeOperationId);
    }

    public synchronized OperationId activeOperationId() {
        return activeOperationId;
    }

    public List<OperationRecord> unconsumedResults() {
        return store.unconsumedTerminal();
    }

    public void markConsumed(OperationId id) {
        store.markConsumed(id);
    }
}
