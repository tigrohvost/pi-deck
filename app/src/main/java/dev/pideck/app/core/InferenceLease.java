package dev.pideck.app.core;

/** Fences queued ACTIVE/IDLE intents by both server and task identity. */
public final class InferenceLease {
    private String generation = "", task = "";
    private boolean active;

    public synchronized void begin(String generation, String task) {
        this.generation = generation; this.task = task; active = true;
    }
    public synchronized boolean end(String generation, String task) {
        if (!this.generation.equals(generation) || !this.task.equals(task)) return false;
        active = false;
        return true;
    }
    public synchronized boolean accepts(String generation, String task, boolean active) {
        return this.active == active && this.generation.equals(generation) && this.task.equals(task);
    }
    public synchronized void clear() { generation = ""; task = ""; active = false; }
}
