package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.pideck.app.core.UiLanguage;

/**
 * The pause before the agent changes something it did not create.
 *
 * <p>The card is what makes the AGENTS.md rule — explain the intent before overwriting user data
 * — visible: the agent's own words for why, and enough of the diff to judge them. The
 * destructive-looking action is not red, because it is the one the user asked for and the agent
 * has just justified; the red would be theatre.
 */
@SuppressLint("ViewConstructor")
public final class DecisionCardView extends LinearLayout {
    /** One pending overwrite, as the bridge described it. */
    public static final class Decision {
        public final String approvalId;
        public final String path;
        public final String reason;
        public final int addedLines;
        public final int removedLines;
        public final boolean selfCreated;
        public final List<String> preview = new ArrayList<>();

        public Decision(
                String approvalId,
                String path,
                String reason,
                int addedLines,
                int removedLines,
                boolean selfCreated
        ) {
            this.approvalId = approvalId;
            this.path = path == null ? "" : path;
            this.reason = reason == null ? "" : reason;
            this.addedLines = addedLines;
            this.removedLines = removedLines;
            this.selfCreated = selfCreated;
        }

        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
        }

        /** Flattened for the stored transcript, where the buttons cannot survive. */
        public String transcriptText() {
            return "Нужно ваше решение: перезаписать " + fileName() + ".\n" + reason;
        }

        public String transcriptText(UiLanguage language) {
            return language.pick(
                    "Нужно ваше решение: перезаписать " + fileName() + ".\n" + reason,
                    "Your decision is required: overwrite " + fileName() + ".\n" + reason
            );
        }
    }

    public interface Listener {
        void onDecision(String approvalId, boolean confirmed);
    }

    public DecisionCardView(
            Context context,
            DeckStyle style,
            Decision decision,
            UiLanguage language,
            Listener listener
    ) {
        super(context);
        UiLanguage selectedLanguage = language == null
                ? UiLanguage.RUSSIAN : language;
        Palette palette = style.palette;
        setOrientation(VERTICAL);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setBackground(style.outlined(palette.panel, palette.warn, 8));
        card.setPadding(style.dp(16), style.dp(16), style.dp(16), style.dp(16));
        addView(card, matchWidth());

        card.addView(style.monoLabel(
                selectedLanguage.pick("Нужно ваше решение", "Your decision is required"),
                palette.warn
        ));

        TextView title = style.cardTitle("");
        title.setText(titleFor(decision, palette, selectedLanguage));
        LayoutParams titleLp = matchWidth();
        titleLp.topMargin = style.dp(14);
        card.addView(title, titleLp);

        if (!decision.reason.isEmpty()) {
            TextView reason = style.bodySecondary("«" + decision.reason + "»");
            LayoutParams reasonLp = matchWidth();
            reasonLp.topMargin = style.dp(14);
            card.addView(reason, reasonLp);
        }

        if (!decision.preview.isEmpty()) {
            LinearLayout diff = new LinearLayout(context);
            diff.setOrientation(VERTICAL);
            diff.setBackground(style.round(palette.background, 5));
            diff.setPadding(style.dp(13), style.dp(12), style.dp(13), style.dp(12));
            for (String line : decision.preview) {
                int color = line.startsWith("+")
                        ? palette.ok
                        : line.startsWith("−") || line.startsWith("-")
                        ? palette.errorText
                        : palette.muted;
                TextView row = style.monoTrace(line, color);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                diff.addView(row, matchWidth());
            }
            TextView summary = style.monoTrace(
                    "−" + decision.removedLines + " · +" + decision.addedLines,
                    palette.muted
            );
            LayoutParams summaryLp = matchWidth();
            summaryLp.topMargin = style.dp(8);
            diff.addView(summary, summaryLp);

            LayoutParams diffLp = matchWidth();
            diffLp.topMargin = style.dp(14);
            card.addView(diff, diffLp);
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        TextView confirm = style.primaryButton(
                selectedLanguage.pick("Перезаписать", "Overwrite"),
                () -> listener.onDecision(decision.approvalId, true)
        );
        LayoutParams confirmLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(confirm, confirmLp);
        TextView refuse = style.outlinedButton(
                selectedLanguage.pick("Не трогать", "Leave unchanged"),
                palette.textSecondary,
                () -> listener.onDecision(decision.approvalId, false)
        );
        LayoutParams refuseLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        refuseLp.leftMargin = style.dp(9);
        actions.addView(refuse, refuseLp);
        LayoutParams actionsLp = matchWidth();
        actionsLp.topMargin = style.dp(14);
        card.addView(actions, actionsLp);

        TextView note = style.caption(
                selectedLanguage.pick(
                        "Спрашиваю только про файлы, которые агент не создавал сам. Отключается в Ядре.",
                        "Only files created elsewhere require approval. This can be disabled in Core."
                )
        );
        LayoutParams noteLp = matchWidth();
        noteLp.topMargin = style.dp(11);
        addView(note, noteLp);
    }

    /** «Перезаписать AGENTS.md?» with the file name set in mono so it reads as a path. */
    private SpannableString titleFor(
            Decision decision,
            Palette palette,
            UiLanguage language
    ) {
        String name = decision.fileName();
        String text = language.pick("Перезаписать ", "Overwrite ") + name + "?";
        SpannableString value = new SpannableString(text);
        int start = text.indexOf(name);
        if (start >= 0) {
            value.setSpan(
                    new TypefaceSpan("monospace"),
                    start, start + name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            value.setSpan(
                    new ForegroundColorSpan(palette.accent),
                    start, start + name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return value;
    }

    private LayoutParams matchWidth() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
