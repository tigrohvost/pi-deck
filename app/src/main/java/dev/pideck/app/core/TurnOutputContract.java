package dev.pideck.app.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure reconciliation rules shared by the streaming UI and its durable transcript. */
public final class TurnOutputContract {
    private TurnOutputContract() {
    }

    /**
     * A delta stream is only a speculative preview. Once a terminal event arrives, its answer is
     * authoritative even when the preview is non-empty or disagrees with it.
     */
    public static String reconcileTerminal(
            String speculativeText,
            String terminalAnswer,
            String emptyAnswerText
    ) {
        Objects.requireNonNull(speculativeText, "speculativeText");
        Objects.requireNonNull(emptyAnswerText, "emptyAnswerText");
        if (terminalAnswer == null || terminalAnswer.isBlank()) return emptyAnswerText;
        return terminalAnswer;
    }

    /** Returns a detached persistence snapshot with the speculative streaming row removed. */
    public static <T> List<T> durableSnapshot(List<T> entries, int speculativeIndex) {
        ArrayList<T> durable = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        if (speculativeIndex >= 0 && speculativeIndex < durable.size()) {
            durable.remove(speculativeIndex);
        }
        return durable;
    }
}
