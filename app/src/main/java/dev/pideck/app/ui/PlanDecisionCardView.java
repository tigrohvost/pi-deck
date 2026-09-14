package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.pideck.app.core.UiLanguage;

/** Android-owned boundary between a read-only planning pass and plan execution. */
@SuppressLint("ViewConstructor")
public final class PlanDecisionCardView extends LinearLayout {
    public static final class Decision {
        public final String approvalId;
        public final String goal;
        public final List<String> steps = new ArrayList<>();

        public Decision(String approvalId, String goal) {
            this.approvalId = approvalId == null ? "" : approvalId;
            this.goal = goal == null ? "" : goal;
        }

        public String transcriptText(UiLanguage language) {
            return language.pick(
                    "План готов: " + steps.size() + " шагов. Ожидается решение об исполнении.",
                    "Plan ready: " + steps.size() + " steps. Waiting for execution approval."
            );
        }
    }

    public interface Listener {
        void onDecision(String approvalId, boolean confirmed);
    }

    public PlanDecisionCardView(
            Context context,
            DeckStyle style,
            Decision decision,
            UiLanguage language,
            Listener listener
    ) {
        super(context);
        UiLanguage selected = language == null ? UiLanguage.RUSSIAN : language;
        Palette palette = style.palette;
        setOrientation(VERTICAL);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setBackground(style.outlined(palette.panel, palette.accentAlt, 8));
        card.setPadding(style.dp(16), style.dp(16), style.dp(16), style.dp(16));
        addView(card, matchWidth());

        card.addView(style.monoLabel("PLAN // READ-ONLY COMPLETE", palette.accentAlt));
        TextView title = style.cardTitle(selected.pick(
                "Выполнить этот план?", "Execute this plan?"
        ));
        LayoutParams titleLp = matchWidth();
        titleLp.topMargin = style.dp(13);
        card.addView(title, titleLp);

        if (!decision.goal.isBlank()) {
            TextView goal = style.bodySecondary("«" + decision.goal + "»");
            LayoutParams goalLp = matchWidth();
            goalLp.topMargin = style.dp(10);
            card.addView(goal, goalLp);
        }

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        list.setBackground(style.round(palette.background, 5));
        list.setPadding(style.dp(13), style.dp(11), style.dp(13), style.dp(11));
        for (int index = 0; index < decision.steps.size(); index++) {
            TextView row = style.monoTrace(
                    (index + 1) + ".  " + decision.steps.get(index),
                    palette.textSecondary
            );
            row.setMaxLines(2);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams rowLp = matchWidth();
            if (index > 0) rowLp.topMargin = style.dp(7);
            list.addView(row, rowLp);
        }
        LayoutParams listLp = matchWidth();
        listLp.topMargin = style.dp(13);
        card.addView(list, listLp);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        TextView execute = style.primaryButton(
                selected.pick("Выполнить", "Execute"),
                () -> listener.onDecision(decision.approvalId, true)
        );
        actions.addView(execute, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cancel = style.outlinedButton(
                selected.pick("Отмена", "Cancel"),
                palette.textSecondary,
                () -> listener.onDecision(decision.approvalId, false)
        );
        LayoutParams cancelLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.leftMargin = style.dp(9);
        actions.addView(cancel, cancelLp);
        LayoutParams actionsLp = matchWidth();
        actionsLp.topMargin = style.dp(14);
        card.addView(actions, actionsLp);

        TextView note = style.caption(selected.pick(
                "Планирование ничего не меняло. Решение истекает через 2 минуты; права на запись не расширяются.",
                "Planning changed nothing. This decision expires in 2 minutes; write permissions do not expand."
        ));
        LayoutParams noteLp = matchWidth();
        noteLp.topMargin = style.dp(10);
        addView(note, noteLp);
    }

    private LayoutParams matchWidth() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
