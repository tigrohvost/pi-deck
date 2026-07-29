package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.pideck.app.core.AgentMode;
import dev.pideck.app.core.UiLanguage;

/**
 * ЯДРО: which model runs, how the deck looks, and what maintenance is available.
 *
 * <p>This root replaces the MODEL MATRIX and CORE CONTROL dialogs. It renders descriptors handed
 * to it by the activity rather than reaching into preferences or the download manager itself, so
 * every decision about what a row means stays in one place outside the view layer.
 */
@SuppressLint("ViewConstructor")
public final class CoreRootView extends ScrollView {
    /** One selectable model profile. */
    public static final class ModelRow {
        public final String title;
        public final String meta;
        public final String state;
        public final int stateColor;
        public final boolean selected;
        /** False when the phone cannot run the profile; the row dims and stops responding. */
        public final boolean available;
        /** −1 when no transfer is running. */
        public final int progressPercent;
        public final String actionLabel;
        public final Runnable action;
        public final String secondaryLabel;
        public final Runnable secondary;

        public ModelRow(
                String title,
                String meta,
                String state,
                int stateColor,
                boolean selected,
                boolean available,
                int progressPercent,
                String actionLabel,
                Runnable action,
                String secondaryLabel,
                Runnable secondary
        ) {
            this.title = title;
            this.meta = meta;
            this.state = state;
            this.stateColor = stateColor;
            this.selected = selected;
            this.available = available;
            this.progressPercent = progressPercent;
            this.actionLabel = actionLabel;
            this.action = action;
            this.secondaryLabel = secondaryLabel;
            this.secondary = secondary;
        }
    }

    /** A label/value pair in the СОСТОЯНИЕ section. */
    public static final class InfoRow {
        public final String label;
        public final String value;
        public final int color;

        public InfoRow(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    /** A tappable maintenance row. */
    public static final class ActionRow {
        public final String title;
        public final String subtitle;
        public final int color;
        public final Runnable action;

        public ActionRow(String title, String subtitle, int color, Runnable action) {
            this.title = title;
            this.subtitle = subtitle;
            this.color = color;
            this.action = action;
        }
    }

    /** Everything the core screen needs for one render pass. */
    public static final class State {
        public final List<ModelRow> models = new ArrayList<>();
        public final List<InfoRow> info = new ArrayList<>();
        public final List<ActionRow> maintenance = new ArrayList<>();
        public String schemeId = Palette.SCHEME_NORD;
        public float textScale = 1f;
        public String stopCoreLabel = "";
        public Runnable onStopCore;
        public String accessProfileLabel = "";
        public String accessProfileNote = "";
        public final List<ActionRow> accessProfiles = new ArrayList<>();
        public ActionRow systemPrompt;
        public AgentMode agentMode = AgentMode.AGENT;
        public UiLanguage language = UiLanguage.RUSSIAN;
        public boolean maximumSpeed = true;
        public boolean autostartCore = false;
        /** Mirror of the checkbox on the consent screen. */
        public boolean askBeforeOverwrite = true;
    }

    public interface Listener {
        void onSchemeChosen(String schemeId);

        void onTextScaleChosen(float scale);

        void onAskBeforeOverwriteChanged(boolean askBeforeOverwrite);

        void onAgentModeChosen(AgentMode mode);

        void onMaximumSpeedChanged(boolean enabled);

        void onAutostartCoreChanged(boolean enabled);

        void onLanguageChosen(UiLanguage language);
    }

    private final DeckStyle style;
    private final Listener listener;
    private final LinearLayout column;
    private final UiLanguage language;
    private String lastSignature = "";

    public CoreRootView(
            Context context,
            DeckStyle style,
            Listener listener,
            UiLanguage language
    ) {
        super(context);
        this.style = style;
        this.listener = listener;
        this.language = language == null ? UiLanguage.RUSSIAN : language;
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(style.dp(22), style.dp(18), style.dp(22), style.dp(26));
        addView(column, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    public void render(State state) {
        String signature = signature(state);
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        int previousScroll = getScrollY();
        column.removeAllViews();
        column.addView(style.screenTitle(t("Ядро", "Core")));

        section(t("Модель", "Model"));
        for (ModelRow model : state.models) column.addView(modelRow(model));

        section(t("Режим работы", "Operating mode"));
        addSpaced(style.bodySecondary(
                state.agentMode.label(language) + " — "
                        + state.agentMode.description(language)
        ), 8);
        column.addView(segments(
                new String[]{t("Чат", "Chat"), t("Агент", "Agent")},
                new AgentMode[]{AgentMode.CHAT, AgentMode.AGENT},
                state.agentMode,
                value -> listener.onAgentModeChosen((AgentMode) value)
        ));
        TextView speedNote = style.bodySecondary(
                t(
                        "Максимальная скорость не даёт экрану погаснуть во время ответа.",
                        "Maximum speed keeps the screen awake while the model answers."
                )
        );
        LinearLayout.LayoutParams speedNoteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        speedNoteLp.topMargin = style.dp(14);
        speedNoteLp.bottomMargin = style.dp(8);
        column.addView(speedNote, speedNoteLp);
        column.addView(segments(
                new String[]{t("Скорость", "Speed"), t("Экономия", "Battery saver")},
                new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                state.maximumSpeed,
                value -> listener.onMaximumSpeedChanged((Boolean) value)
        ));

        TextView autostartNote = style.bodySecondary(
                t(
                        "Автозапуск грузит модель сразу при открытии деки: первый ответ ближе, "
                                + "но батарея расходуется даже если вы зашли посмотреть переписку.",
                        "Autostart loads the model as soon as the deck opens: the first answer "
                                + "comes sooner, at the cost of battery even when you only came to "
                                + "read the transcript."
                )
        );
        LinearLayout.LayoutParams autostartNoteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        autostartNoteLp.topMargin = style.dp(14);
        autostartNoteLp.bottomMargin = style.dp(8);
        column.addView(autostartNote, autostartNoteLp);
        column.addView(segments(
                new String[]{t("Автозапуск", "Autostart"), t("По запросу", "On demand")},
                new Boolean[]{Boolean.TRUE, Boolean.FALSE},
                state.autostartCore,
                value -> listener.onAutostartCoreChanged((Boolean) value)
        ));

        if (state.systemPrompt != null) {
            section(t("Системный промпт", "System prompt"));
            column.addView(actionRow(state.systemPrompt));
        }

        section(t("Язык", "Language"));
        column.addView(segments(
                new String[]{"Русский", "English"},
                new UiLanguage[]{UiLanguage.RUSSIAN, UiLanguage.ENGLISH},
                state.language,
                value -> listener.onLanguageChosen((UiLanguage) value)
        ));

        section(t("Цветовая схема", "Colour scheme"));
        column.addView(segments(
                new String[]{"NORD", "DECK"},
                new String[]{Palette.SCHEME_NORD, Palette.SCHEME_DECK},
                state.schemeId,
                value -> listener.onSchemeChosen((String) value)
        ));

        section(t("Размер текста", "Text size"));
        Float[] scaleValues = new Float[DeckStyle.TEXT_SCALES.length];
        String[] scaleLabels = new String[DeckStyle.TEXT_SCALES.length];
        for (int i = 0; i < DeckStyle.TEXT_SCALES.length; i++) {
            scaleValues[i] = DeckStyle.TEXT_SCALES[i];
            scaleLabels[i] = "×" + DeckStyle.TEXT_SCALES[i];
        }
        column.addView(segments(
                scaleLabels,
                scaleValues,
                DeckStyle.normalizeScale(state.textScale),
                value -> listener.onTextScaleChosen((Float) value)
        ));

        section(t("Доступ", "Access"));
        addSpaced(style.bodySecondary(
                t("Перезапись чужих файлов", "Overwrite files created elsewhere")
        ), 8);
        column.addView(segments(
                new String[]{t("Спрашивать", "Ask"), t("Не спрашивать", "Do not ask")},
                new Object[]{Boolean.TRUE, Boolean.FALSE},
                state.askBeforeOverwrite,
                value -> listener.onAskBeforeOverwriteChanged((Boolean) value)
        ));
        if (!state.accessProfiles.isEmpty()) {
            TextView current = style.bodySecondary(
                    state.accessProfileLabel + " — " + state.accessProfileNote
            );
            LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            currentLp.topMargin = style.dp(18);
            currentLp.bottomMargin = style.dp(8);
            column.addView(current, currentLp);
            for (ActionRow profile : state.accessProfiles) column.addView(actionRow(profile));
        }

        section(t("Обслуживание", "Maintenance"));
        for (ActionRow action : state.maintenance) column.addView(actionRow(action));

        section(t("Состояние", "Status"));
        for (InfoRow info : state.info) column.addView(infoRow(info));

        if (state.onStopCore != null) {
            TextView stop = style.outlinedButton(
                    state.stopCoreLabel, style.palette.errorText, state.onStopCore
            );
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = style.dp(22);
            column.addView(stop, lp);
        }
        post(() -> scrollTo(0, Math.min(
                previousScroll,
                Math.max(0, column.getHeight() - getHeight())
        )));
    }

    private String signature(State state) {
        StringBuilder value = new StringBuilder()
                .append(state.schemeId).append('|')
                .append(state.textScale).append('|')
                .append(state.agentMode).append('|')
                .append(state.language).append('|')
                .append(state.maximumSpeed).append('|')
                .append(state.autostartCore).append('|')
                .append(state.askBeforeOverwrite).append('|')
                .append(state.accessProfileLabel).append('|')
                .append(state.accessProfileNote).append('|')
                .append(state.stopCoreLabel);
        if (state.systemPrompt != null) {
            value.append('|').append(state.systemPrompt.title)
                    .append('|').append(state.systemPrompt.subtitle);
        }
        for (ModelRow row : state.models) {
            value.append('|').append(row.title).append('|').append(row.meta)
                    .append('|').append(row.state).append('|').append(row.selected)
                    .append('|').append(row.available).append('|').append(row.progressPercent)
                    .append('|').append(row.actionLabel).append('|').append(row.secondaryLabel);
        }
        for (ActionRow row : state.accessProfiles) {
            value.append('|').append(row.title).append('|').append(row.subtitle);
        }
        for (ActionRow row : state.maintenance) {
            value.append('|').append(row.title).append('|').append(row.subtitle);
        }
        for (InfoRow row : state.info) {
            value.append('|').append(row.label).append('|').append(row.value)
                    .append('|').append(row.color);
        }
        return value.toString();
    }

    private String t(String russian, String english) {
        return language.pick(russian, english);
    }

    private void section(String label) {
        TextView view = style.monoLabel(label, style.palette.muted);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = style.dp(22);
        lp.bottomMargin = style.dp(11);
        column.addView(view, lp);
    }

    private void addSpaced(View view, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = style.dp(bottomDp);
        column.addView(view, lp);
    }

    private View modelRow(ModelRow model) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(style.dp(14), style.dp(13), style.dp(14), style.dp(13));
        row.setMinimumHeight(style.dp(48));
        row.setBackground(model.selected
                ? style.outlined(style.palette.panel, style.palette.accent, 7)
                : style.pressable(
                        style.palette.cardFill, style.palette.cardFillHover, style.palette.stroke, 7
                ));
        if (!model.available) row.setAlpha(0.55f);
        if (model.action != null && model.available) style.clickable(row, model.action);

        LinearLayout head = new LinearLayout(getContext());
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new RadioDot(getContext(), style, model.selected), new LinearLayout.LayoutParams(
                style.dp(14), style.dp(14)
        ));
        TextView title = style.rowTitle(model.title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        titleLp.leftMargin = style.dp(11);
        head.addView(title, titleLp);
        row.addView(head);

        TextView meta = style.monoTrace(model.meta, style.palette.muted);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        metaLp.topMargin = style.dp(6);
        metaLp.leftMargin = style.dp(25);
        row.addView(meta, metaLp);

        if (!model.state.isEmpty()) {
            TextView state = style.monoTrace(model.state, model.stateColor);
            LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            stateLp.topMargin = style.dp(4);
            stateLp.leftMargin = style.dp(25);
            row.addView(state, stateLp);
        }

        if (model.progressPercent >= 0) {
            row.addView(new ProgressBarView(getContext(), style, model.progressPercent),
                    progressLp());
        }

        if (model.actionLabel != null || model.secondaryLabel != null) {
            LinearLayout actions = new LinearLayout(getContext());
            actions.setOrientation(LinearLayout.HORIZONTAL);
            if (model.actionLabel != null && model.action != null) {
                actions.addView(style.inlineAction(
                        model.actionLabel, style.palette.accent, model.action
                ));
            }
            if (model.secondaryLabel != null && model.secondary != null) {
                actions.addView(style.inlineAction(
                        model.secondaryLabel, style.palette.muted, model.secondary
                ));
            }
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            actionsLp.leftMargin = style.dp(14);
            row.addView(actions, actionsLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = style.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout.LayoutParams progressLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(3)
        );
        lp.topMargin = style.dp(9);
        lp.leftMargin = style.dp(25);
        return lp;
    }

    private View actionRow(ActionRow action) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12));
        row.setMinimumHeight(style.dp(48));
        row.setBackground(style.pressable(
                style.palette.cardFill, style.palette.cardFillHover, style.palette.stroke, 7
        ));
        style.clickable(row, action.action);

        TextView title = style.rowTitle(action.title);
        title.setTextColor(action.color);
        row.addView(title);
        if (action.subtitle != null && !action.subtitle.isEmpty()) {
            TextView subtitle = style.monoTrace(action.subtitle, style.palette.muted);
            LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subtitleLp.topMargin = style.dp(4);
            row.addView(subtitle, subtitleLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = style.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private View infoRow(InfoRow info) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, style.dp(8), 0, style.dp(8));
        TextView label = style.monoTrace(info.label, style.palette.muted);
        TextView value = style.monoTrace(info.value, info.color);
        value.setGravity(Gravity.END);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f
        ));
        row.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f
        ));
        return row;
    }

    private interface SegmentChosen {
        void accept(Object value);
    }

    private View segments(
            String[] labels,
            Object[] values,
            Object current,
            SegmentChosen chosen
    ) {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setBackground(style.outlined(style.palette.cardFill, style.palette.stroke, 7));
        group.setPadding(style.dp(4), style.dp(4), style.dp(4), style.dp(4));
        for (int index = 0; index < labels.length; index++) {
            boolean active = values[index].equals(current);
            TextView segment = style.monoButton(
                    labels[index], active ? style.palette.background : style.palette.textSecondary
            );
            segment.setGravity(Gravity.CENTER);
            segment.setPadding(style.dp(8), style.dp(12), style.dp(8), style.dp(12));
            segment.setMinHeight(style.dp(44));
            if (active) segment.setBackground(style.round(style.palette.accent, 5));
            Object value = values[index];
            if (!active) style.clickable(segment, () -> chosen.accept(value));
            group.addView(segment, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
        }
        return group;
    }

    /** Radio state of a model row: a ring that fills when the profile is the active one. */
    private static final class RadioDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DeckStyle style;
        private final boolean checked;

        RadioDot(Context context, DeckStyle style, boolean checked) {
            super(context);
            this.style = style;
            this.checked = checked;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(style.dpf(1));
            paint.setColor(checked ? style.palette.accent : style.palette.stroke);
            canvas.drawCircle(centerX, centerY, radius - style.dpf(0.5f), paint);
            if (!checked) return;
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX, centerY, radius * 0.45f, paint);
        }
    }

    /** Flat 3 dp progress track; no platform widget, so it carries no theme of its own. */
    private static final class ProgressBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DeckStyle style;
        private final int percent;

        ProgressBarView(Context context, DeckStyle style, int percent) {
            super(context);
            this.style = style;
            this.percent = Math.max(0, Math.min(100, percent));
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(style.palette.stroke);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(style.palette.accent);
            canvas.drawRect(0, 0, getWidth() * percent / 100f, getHeight(), paint);
        }
    }
}
