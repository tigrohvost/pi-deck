package dev.pideck.app.core;

import java.util.concurrent.CopyOnWriteArrayList;

public final class CommandEvents {
    public interface Listener {
        void onCommandResult(CommandResult result);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CommandEvents() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void publish(CommandResult result) {
        for (Listener listener : LISTENERS) {
            listener.onCommandResult(result);
        }
    }
}
