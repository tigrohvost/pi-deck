package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pideck.app.core.AgentMode;
import dev.pideck.app.core.GenerationSpeed;
import dev.pideck.app.core.SessionContextUsage;
import dev.pideck.app.core.TurnOutputContract;
import dev.pideck.app.core.UiLanguage;

/**
 * The shell of the deck: header, the three roots the tab bar switches between, the prompt field
 * and the tab bar itself.
 *
 * <p>The console root is a stream rather than a log: a human turn is a bubble, the tool calls that
 * answer it collapse into one trace feed, and the answer carries the actions it earned. Only the
 * console owns a prompt field, so the field hides with it.
 */
@SuppressLint("ViewConstructor")
public final class DeckView extends FrameLayout implements CoreRootView.Listener {
    /** The one thing the header reports: whether the core can answer right now. */
    public enum CoreStatus {
        SLEEPING,
        STARTING,
        READY,
        BUSY,
        AWAITING_USER,
        FAILED
    }

    public interface Listener {
        void onSend(String prompt);

        void onTabSelected(int tab);

        void onSchemeChosen(String schemeId);

        void onTextScaleChosen(float scale);

        /** «СТОП» in the execution row. */
        void onStopTurn();

        /** «ОТКРЫТЬ ФАЙЛ» under an answer that wrote one. */
        void onOpenFile(String path);

        /** The one-time shell-access consent, with the state of its checkbox. */
        void onConsentGranted(boolean askBeforeOverwrite);

        /** The same flag, changed later from ЯДРО. */
        void onAskBeforeOverwriteChanged(boolean askBeforeOverwrite);

        void onCompactSession();

        void onNewSessionRequested();

        void onAgentModeChosen(AgentMode mode);

        void onMaximumSpeedChanged(boolean enabled);

        void onAutostartCoreChanged(boolean enabled);

        void onCoreIdleTimeoutChanged(long minutes);

        void onThermalPacingChanged(boolean enabled);

        void onSmartCompactionChanged(boolean enabled);

        /** Non-empty draft text lets the Activity hide core startup behind typing time. */
        void onComposerIntentChanged(boolean hasText);

        /** Debounced by the Activity into durable storage; never used to dispatch a prompt. */
        void onComposerDraftChanged(String draft);

        void onLanguageChosen(UiLanguage language);
    }

    /** One card in the empty console: a headline and the prompt it stands for. */
    private static final String[][] SCENARIOS_RU = {
            {"Разобраться в чужом коде", "объясни, что делает этот проект"},
            {"Найти причину падения", "тест parse_jsonl падает, почему"},
            {"Написать скрипт", "собери из логов csv по дням"},
            {"Навести порядок", "найди все TODO и собери в файл"},
    };
    private static final String[][] SCENARIOS_EN = {
            {"Understand unfamiliar code", "explain what this project does"},
            {"Find a crash cause", "the parse_jsonl test fails; find out why"},
            {"Write a script", "build a daily CSV from the logs"},
            {"Tidy the workspace", "find every TODO and collect them in a file"},
    };

    /** Paths and file names inside an answer are set in mono so they can be picked out. */
    private static final Pattern PATH_LIKE = Pattern.compile(
            "`[^`\\n]{1,120}`|[\\w./~-]*[\\w-]+\\.[A-Za-z][\\w]{0,7}\\b"
    );

    private static final List<String> WRITING_VERBS =
            List.of("write", "create", "edit", "delete");

    private final DeckStyle style;
    private final Listener listener;
    private final Palette p;
    private final UiLanguage language;

    private final TextView coreStatusLabel;
    private final StatusDot coreStatusDot;
    private final LinearLayout consoleRoot;
    private final CoreRootView coreRoot;
    private final SessionsRootView sessionsRoot;
    private final LinearLayout contextRow;
    private final LinearLayout inputRow;
    private final TabBarView tabBar;

    private LinearLayout bootPanel;
    private TextView bootKicker;
    private TextView bootTitle;
    private TextView bootBody;
    private LinearLayout bootActions;
    private LinearLayout emptyState;
    private TextView workspacePath;
    private ScrollView streamScroll;
    private LinearLayout stream;
    private ExecutionRowView executionRow;
    private EditText promptInput;
    private TextView sendButton;
    private LinearLayout.LayoutParams sendButtonLayout;
    private TextView contextLabel;
    private TextView generationRateLabel;
    private TextView compactContextAction;
    private TextView newSessionAction;
    private boolean contextAvailable;
    private boolean composerDispatchPending;
    private boolean composerHasText;
    private boolean composerAvailable = true;
    private boolean composerWillWarm;
    private int queuedPromptCount;

    private final List<ConsoleEntry> entries = new ArrayList<>();
    /** The block each entry was drawn into; several trace entries share one feed. */
    private final List<View> blocks = new ArrayList<>();
    private TraceFeedView openTrace;
    private DecisionCardView decisionCard;
    private ConsentView consentView;
    private int safeInsetLeft;
    private int safeInsetTop;
    private int safeInsetRight;
    private int safeInsetBottom;
    private TextView streamingMessage;
    private LinearLayout streamingAnswer;
    private int streamingEntryIndex = -1;
    private int renderedStreamingLength;
    private long streamingEntryTime;
    private final StringBuilder streamingText = new StringBuilder();
    private boolean streamingRenderScheduled;
    private boolean scrollScheduled;
    private final Runnable renderStreamingFrame = () -> {
        streamingRenderScheduled = false;
        renderStreamingText();
    };
    private final Runnable scrollToEndFrame = () -> {
        scrollScheduled = false;
        if (streamScroll == null || streamScroll.getChildCount() == 0) return;
        View content = streamScroll.getChildAt(0);
        streamScroll.scrollTo(0, Math.max(0, content.getHeight() - streamScroll.getHeight()));
    };
    private String lastWrittenPath = "";

    public DeckView(
            Context context,
            Listener listener,
            Palette palette,
            float textScale,
            UiLanguage language
    ) {
        super(context);
        this.listener = listener;
        this.p = palette;
        this.language = language == null ? UiLanguage.RUSSIAN : language;
        this.style = new DeckStyle(context, palette, textScale);
        setBackgroundColor(p.background);
        setFocusable(true);
        setFocusableInTouchMode(true);

        addView(new GridBackdropView(context, p), match());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, match());

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(style.dp(22), style.dp(12), style.dp(22), style.dp(12));
        header.setMinimumHeight(style.dp(48));

        SpannableString brandText = new SpannableString("π//DECK");
        brandText.setSpan(
                new ForegroundColorSpan(p.accentAlt), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        brandText.setSpan(
                new ForegroundColorSpan(p.accent), 1, brandText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        TextView brand = style.wordmark("π//DECK");
        brand.setText(brandText);
        header.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        coreStatusDot = new StatusDot(context, p.error);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                style.dp(6), style.dp(6)
        );
        dotLp.rightMargin = style.dp(6);
        header.addView(coreStatusDot, dotLp);
        coreStatusLabel = style.monoAt(
                t("Ядро не запущено", "Core is stopped"), 11f, p.error, true
        );
        header.addView(coreStatusLabel);
        root.addView(header, matchWidth());
        root.addView(divider(), dividerLp());

        FrameLayout contentHost = new FrameLayout(context);
        root.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        consoleRoot = buildConsoleRoot(context);
        contentHost.addView(consoleRoot, match());
        coreRoot = new CoreRootView(context, style, this, this.language);
        coreRoot.setVisibility(GONE);
        contentHost.addView(coreRoot, match());
        sessionsRoot = new SessionsRootView(context, style, this.language);
        sessionsRoot.setVisibility(GONE);
        contentHost.addView(sessionsRoot, match());

        contextRow = buildContextRow(context);
        root.addView(contextRow, matchWidth());
        inputRow = buildInput(context);
        root.addView(inputRow, matchWidth());

        root.addView(divider(), dividerLp());
        tabBar = new TabBarView(context, style, listener::onTabSelected, this.language);
        root.addView(tabBar, matchWidth());

        addView(new ScanlineView(context, p), match());

        if (Build.VERSION.SDK_INT >= 35) {
            setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                safeInsetLeft = safe.left;
                safeInsetTop = safe.top;
                safeInsetRight = safe.right;
                safeInsetBottom = safe.bottom;
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                boolean imeVisible = windowInsets.isVisible(WindowInsets.Type.ime());
                // Android 15 enforces edge-to-edge for target 35. adjustResize alone no longer
                // moves this custom root, so reserve the real IME height inside the edge-to-edge
                // window and keep the composer above the keyboard.
                root.setPadding(
                        safe.left,
                        safe.top,
                        safe.right,
                        imeVisible ? ime.bottom : 0
                );
                tabBar.setPadding(
                        0,
                        0,
                        0,
                        imeVisible ? style.dp(8) : Math.max(safe.bottom, style.dp(22))
                );
                applyConsentInsets();
                return windowInsets;
            });
        } else {
            tabBar.setPadding(0, 0, 0, style.dp(22));
        }
        setCoreStatus(CoreStatus.SLEEPING, null);
        updateEmptyState();
    }

    private LinearLayout buildContextRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(style.dp(22), style.dp(7), style.dp(18), 0);

        contextLabel = style.monoTrace("", p.muted);
        row.addView(contextLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        generationRateLabel = style.monoTrace("", p.accent);
        generationRateLabel.setVisibility(GONE);
        LinearLayout.LayoutParams rateLp = wrap();
        rateLp.leftMargin = style.dp(8);
        rateLp.rightMargin = style.dp(8);
        row.addView(generationRateLabel, rateLp);
        compactContextAction = style.inlineAction(
                t("Ускорить", "Speed up"), p.accent, listener::onCompactSession
        );
        compactContextAction.setVisibility(GONE);
        row.addView(compactContextAction);
        newSessionAction = style.inlineAction(
                t("Новая", "New"), p.muted, listener::onNewSessionRequested
        );
        newSessionAction.setVisibility(GONE);
        LinearLayout.LayoutParams newLp = wrap();
        newLp.leftMargin = style.dp(8);
        row.addView(newSessionAction, newLp);
        row.setVisibility(GONE);
        return row;
    }

    private LinearLayout buildConsoleRoot(Context context) {
        LinearLayout console = new LinearLayout(context);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setPadding(style.dp(22), style.dp(18), style.dp(22), 0);

        bootPanel = new LinearLayout(context);
        bootPanel.setOrientation(LinearLayout.VERTICAL);
        bootPanel.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14));
        bootPanel.setBackground(style.outlined(p.panel, p.accent, 7));

        bootKicker = style.monoLabel("BOOT SEQUENCE // 00", p.accent);
        bootPanel.addView(bootKicker);

        bootTitle = style.cardTitle(t("ЛОКАЛЬНОЕ ЯДРО", "LOCAL CORE"));
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = style.dp(8);
        bootPanel.addView(bootTitle, titleLp);

        bootBody = style.bodySecondary("");
        LinearLayout.LayoutParams bodyLp = wrap();
        bodyLp.topMargin = style.dp(8);
        bootPanel.addView(bootBody, bodyLp);

        bootActions = new LinearLayout(context);
        bootActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = wrap();
        actionsLp.topMargin = style.dp(14);
        bootPanel.addView(bootActions, actionsLp);

        LinearLayout.LayoutParams bootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bootLp.bottomMargin = style.dp(18);
        console.addView(bootPanel, bootLp);

        streamScroll = new ScrollView(context);
        streamScroll.setFillViewport(true);
        streamScroll.setVerticalScrollBarEnabled(false);
        streamScroll.setClipToPadding(false);
        console.addView(streamScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        streamScroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        emptyState = buildEmptyState(context);
        column.addView(emptyState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        stream = new LinearLayout(context);
        stream.setOrientation(LinearLayout.VERTICAL);
        column.addView(stream, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        executionRow = new ExecutionRowView(
                context, style, listener::onStopTurn, this.language
        );
        LinearLayout.LayoutParams executionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        executionLp.topMargin = style.dp(20);
        executionLp.bottomMargin = style.dp(14);
        column.addView(executionRow, executionLp);
        return console;
    }

    private LinearLayout buildEmptyState(Context context) {
        LinearLayout empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);

        empty.addView(style.screenTitle(t("Дека готова", "Deck ready")));
        TextView subtitle = style.body(t(
                "Модель работает на телефоне. Можно в самолёт.",
                "The model runs on this phone—even in airplane mode."
        ));
        subtitle.setTextColor(p.muted);
        LinearLayout.LayoutParams subtitleLp = matchWidth();
        subtitleLp.topMargin = style.dp(8);
        empty.addView(subtitle, subtitleLp);

        LinearLayout workspace = new LinearLayout(context);
        workspace.setOrientation(LinearLayout.VERTICAL);
        workspace.setBackground(style.round(p.panel, 7));
        workspace.setPadding(style.dp(15), style.dp(13), style.dp(15), style.dp(13));
        workspace.addView(style.caption(t("Рабочая папка", "Workspace")));
        workspacePath = style.monoMeta("~/.pideck/workspace", p.accent);
        LinearLayout.LayoutParams pathLp = matchWidth();
        pathLp.topMargin = style.dp(4);
        workspace.addView(workspacePath, pathLp);
        LinearLayout.LayoutParams workspaceLp = matchWidth();
        workspaceLp.topMargin = style.dp(22);
        empty.addView(workspace, workspaceLp);

        TextView scenariosLabel = style.monoLabel(
                t("С чего начать", "Try one of these"), p.muted
        );
        LinearLayout.LayoutParams labelLp = matchWidth();
        labelLp.topMargin = style.dp(22);
        labelLp.bottomMargin = style.dp(11);
        empty.addView(scenariosLabel, labelLp);

        for (String[] scenario : scenarios()) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(style.dp(15), style.dp(11), style.dp(15), style.dp(11));
            card.setMinimumHeight(style.dp(48));
            card.setBackground(style.pressable(p.cardFill, p.cardFillHover, p.stroke, 7));
            card.addView(style.rowTitle(scenario[0]));
            TextView prompt = style.monoTrace("«" + scenario[1] + "»", p.muted);
            LinearLayout.LayoutParams promptLp = matchWidth();
            promptLp.topMargin = style.dp(4);
            card.addView(prompt, promptLp);
            // Tapping fills the field and stops there: the user still sends the prompt.
            style.clickable(card, () -> setPrompt(scenario[1]));
            LinearLayout.LayoutParams cardLp = matchWidth();
            cardLp.bottomMargin = style.dp(8);
            empty.addView(card, cardLp);
        }

        View spacer = new View(context);
        empty.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        TextView footer = style.caption(
                t(
                        "Агент видит только рабочую папку и общие файлы через ~/storage.",
                        "The agent sees the workspace and shared files through ~/storage."
                )
        );
        LinearLayout.LayoutParams footerLp = matchWidth();
        footerLp.topMargin = style.dp(22);
        footerLp.bottomMargin = style.dp(14);
        empty.addView(footer, footerLp);
        return empty;
    }

    private LinearLayout buildInput(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(style.dp(18), style.dp(14), style.dp(18), style.dp(14));

        promptInput = new EditText(context);
        promptInput.setTextColor(p.text);
        promptInput.setHintTextColor(p.muted);
        promptInput.setHint(t("Что сделать?", "What should I do?"));
        promptInput.setTextSize(14.5f * style.textScale());
        promptInput.setLineSpacing(0f, 1.4f);
        promptInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        promptInput.setPadding(style.dp(16), style.dp(13), style.dp(16), style.dp(13));
        promptInput.setMinHeight(style.dp(44));
        promptInput.setMaxHeight(style.dp(128));
        promptInput.setSingleLine(false);
        promptInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        );
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        promptInput.setBackground(style.round(p.panel, 22));
        promptInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                emitPrompt();
                return true;
            }
            if (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.isCtrlPressed()
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                emitPrompt();
                return true;
            }
            return false;
        });
        row.addView(promptInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        sendButton = style.monoAt("↑", 17f, p.muted, false);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(style.round(p.panel, 22));
        sendButton.setContentDescription(t("Отправить сообщение", "Send message"));
        style.clickable(sendButton, this::emitPrompt);
        sendButtonLayout = new LinearLayout.LayoutParams(
                style.dp(44), style.dp(44)
        );
        sendButtonLayout.leftMargin = style.dp(11);
        row.addView(sendButton, sendButtonLayout);

        promptInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                listener.onComposerDraftChanged(editable.toString());
                boolean hasText = !editable.toString().trim().isEmpty();
                if (hasText != composerHasText) {
                    composerHasText = hasText;
                    listener.onComposerIntentChanged(hasText);
                }
                updateSendAffordance();
            }
        });
        return row;
    }

    private void updateSendAffordance() {
        boolean hasText = !promptInput.getText().toString().trim().isEmpty();
        boolean running = executionRow.isRunning();
        boolean queueFull = queuedPromptCount > 0;
        boolean armed = composerAvailable
                && hasText
                && !composerDispatchPending
                && !queueFull;
        if (composerDispatchPending) {
            sendButton.setText("…");
            sendButton.setContentDescription(t("Сообщение отправляется", "Sending message"));
        } else if (queueFull) {
            sendButton.setText(t("В очереди · 1", "Queued · 1"));
            sendButton.setContentDescription(t(
                    "Сообщение надёжно сохранено в очереди",
                    "The message is safely saved in the queue"
            ));
        } else if (running && hasText) {
            sendButton.setText(t("В очередь", "Queue"));
            sendButton.setContentDescription(
                    t("Добавить сообщение в очередь", "Add message to queue")
            );
        } else {
            sendButton.setText("↑");
            sendButton.setContentDescription(t("Отправить сообщение", "Send message"));
        }
        boolean wordLabel = (running && hasText) || queueFull;
        sendButton.setTextSize((wordLabel ? 11.5f : 17f) * style.textScale());
        int horizontalPadding = wordLabel ? style.dp(12) : 0;
        sendButton.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        sendButton.setMinWidth(wordLabel ? style.dp(92) : style.dp(44));
        int desiredWidth = wordLabel
                ? ViewGroup.LayoutParams.WRAP_CONTENT
                : style.dp(44);
        if (sendButtonLayout.width != desiredWidth) {
            sendButtonLayout.width = desiredWidth;
            sendButton.setLayoutParams(sendButtonLayout);
        }
        sendButton.setBackground(style.round(armed ? p.accent : p.panel, 22));
        sendButton.setTextColor(armed ? p.background : p.muted);
        sendButton.setEnabled(armed);
        sendButton.setAlpha(armed ? 1f : 0.78f);

        boolean inputEnabled = composerAvailable && !composerDispatchPending && !queueFull;
        promptInput.setEnabled(inputEnabled);
        promptInput.setHint(queueFull
                ? t("Запрос сохранён в очереди", "Prompt saved in queue")
                : !composerAvailable
                ? t("Сначала завершите настройку выше", "Finish setup above first")
                : composerWillWarm
                ? t(
                        "Опишите задачу — запустимся сами",
                        "Describe a task — starts automatically"
                )
                : t("Что сделать?", "What should I do?"));
    }

    public void setActiveTab(int tab) {
        tabBar.setSelectedTab(tab);
        consoleRoot.setVisibility(tab == TabBarView.TAB_CONSOLE ? VISIBLE : GONE);
        coreRoot.setVisibility(tab == TabBarView.TAB_CORE ? VISIBLE : GONE);
        sessionsRoot.setVisibility(tab == TabBarView.TAB_SESSIONS ? VISIBLE : GONE);
        inputRow.setVisibility(tab == TabBarView.TAB_CONSOLE ? VISIBLE : GONE);
        contextRow.setVisibility(
                tab == TabBarView.TAB_CONSOLE && contextAvailable ? VISIBLE : GONE
        );
        if (tab != TabBarView.TAB_CONSOLE && promptInput.hasFocus()) {
            promptInput.clearFocus();
            requestFocus();
        }
    }

    public int activeTab() {
        return tabBar.selectedTab();
    }

    public void renderCore(CoreRootView.State state) {
        coreRoot.render(state);
    }

    public void renderSessions(SessionsRootView.State state) {
        sessionsRoot.render(state);
    }

    public void setWorkspacePath(String path) {
        workspacePath.setText(path);
    }

    /**
     * Consent covers the whole window: there is no tab bar, no prompt field and no way back into
     * the console from it, because it is the decision the rest of the app depends on.
     */
    public void setConsentVisible(boolean visible) {
        if (visible == (consentView != null)) return;
        if (!visible) {
            removeView(consentView);
            consentView = null;
            return;
        }
        consentView = new ConsentView(
                getContext(),
                style,
                workspacePath.getText().toString(),
                language,
                askBeforeOverwrite -> {
                    setConsentVisible(false);
                    listener.onConsentGranted(askBeforeOverwrite);
                }
        );
        applyConsentInsets();
        addView(consentView, match());
    }

    private void applyConsentInsets() {
        if (consentView == null) return;
        consentView.setPadding(
                safeInsetLeft,
                safeInsetTop,
                safeInsetRight,
                safeInsetBottom
        );
    }

    public boolean isConsentVisible() {
        return consentView != null;
    }

    @Override
    public void onSchemeChosen(String schemeId) {
        listener.onSchemeChosen(schemeId);
    }

    @Override
    public void onTextScaleChosen(float scale) {
        listener.onTextScaleChosen(scale);
    }

    @Override
    public void onAskBeforeOverwriteChanged(boolean askBeforeOverwrite) {
        listener.onAskBeforeOverwriteChanged(askBeforeOverwrite);
    }

    @Override
    public void onAgentModeChosen(AgentMode mode) {
        listener.onAgentModeChosen(mode);
    }

    @Override
    public void onMaximumSpeedChanged(boolean enabled) {
        listener.onMaximumSpeedChanged(enabled);
    }

    @Override
    public void onAutostartCoreChanged(boolean enabled) {
        listener.onAutostartCoreChanged(enabled);
    }

    @Override
    public void onCoreIdleTimeoutChanged(long minutes) {
        listener.onCoreIdleTimeoutChanged(minutes);
    }

    @Override
    public void onThermalPacingChanged(boolean enabled) {
        listener.onThermalPacingChanged(enabled);
    }

    @Override
    public void onSmartCompactionChanged(boolean enabled) {
        listener.onSmartCompactionChanged(enabled);
    }

    @Override
    public void onLanguageChosen(UiLanguage language) {
        listener.onLanguageChosen(language);
    }

    /** The header says one thing at a time: can the core answer, or what is it waiting on. */
    public void setCoreStatus(CoreStatus status, String detail) {
        int color = switch (status) {
            case READY, BUSY -> p.ok;
            case AWAITING_USER, STARTING -> p.warn;
            case FAILED -> p.errorText;
            case SLEEPING -> p.error;
        };
        String label = switch (status) {
            case READY -> detail == null || detail.isBlank()
                    ? t("Ядро живо", "Core online") : detail;
            case BUSY -> detail == null || detail.isBlank()
                    ? t("Работаю", "Working") : detail;
            case STARTING -> t("Поднимаю ядро", "Starting core");
            case AWAITING_USER -> t("Ждёт вас", "Waiting for you");
            case FAILED -> t("Ядро упало", "Core failed");
            case SLEEPING -> t("Ядро не запущено", "Core is stopped");
        };
        coreStatusDot.setColor(color);
        coreStatusLabel.setTextColor(color);
        coreStatusLabel.setText(label.toUpperCase(Locale.getDefault()));
    }

    public void setBootState(
            String kicker,
            String title,
            String body,
            String primaryLabel,
            Runnable primary,
            String secondaryLabel,
            Runnable secondary
    ) {
        bootPanel.setVisibility(VISIBLE);
        bootKicker.setText(kicker.toUpperCase(Locale.getDefault()));
        bootTitle.setText(title);
        bootBody.setText(body);
        bootActions.removeAllViews();
        if (primaryLabel != null) {
            TextView first = style.primaryButton(
                    primaryLabel,
                    primary == null ? () -> { } : primary
            );
            first.setEnabled(primary != null);
            first.setAlpha(primary == null ? 0.68f : 1f);
            bootActions.addView(first);
        }
        if (secondaryLabel != null && secondary != null) {
            TextView second = style.outlinedButton(secondaryLabel, p.accent, secondary);
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = style.dp(9);
            bootActions.addView(second, lp);
        }
        updateEmptyState();
    }

    public void hideBootPanel() {
        bootPanel.setVisibility(GONE);
        updateEmptyState();
    }

    /**
     * Drives the execution row. The prompt field stays live while a turn runs — the next prompt
     * queues rather than being refused — but the send button dims to say so.
     */
    public void setBusy(boolean busy, String label) {
        if (busy) executionRow.start(label);
        else executionRow.stop();
        updateSendAffordance();
    }

    public void setQueueCount(int count) {
        queuedPromptCount = Math.max(0, Math.min(1, count));
        updateSendAffordance();
    }

    /** Enables only interactions the current boot state can actually honour. */
    public void setComposerAvailability(boolean available, boolean willWarm) {
        composerAvailable = available;
        composerWillWarm = available && willWarm;
        updateSendAffordance();
    }

    public void setComposerDispatchPending(boolean pending) {
        composerDispatchPending = pending;
        updateSendAffordance();
    }

    public void acknowledgePrompt(String prompt) {
        if (prompt != null && promptInput.getText().toString().trim().equals(prompt.trim())) {
            promptInput.setText("");
        }
        composerDispatchPending = false;
        updateSendAffordance();
    }

    public void setContextUsage(
            SessionContextUsage usage,
            boolean canManage,
            boolean compacting
    ) {
        contextAvailable = usage != null && usage.known();
        if (!contextAvailable) {
            contextRow.setVisibility(GONE);
            return;
        }
        String prefix = usage.estimated
                ? t("Контекст ≈", "Context ≈")
                : t("Контекст ", "Context ");
        contextLabel.setText(
                prefix + usage.percent + "% · "
                        + String.format(Locale.getDefault(), "%,d", usage.tokens)
                        + " / "
                        + String.format(Locale.getDefault(), "%,d", usage.contextWindow)
        );
        contextLabel.setTextColor(
                usage.percent >= 85 ? p.errorText : usage.percent >= 70 ? p.warn : p.muted
        );
        boolean suggest = usage.percent >= 60 && canManage;
        compactContextAction.setText(compacting
                ? t("Сжимаю…", "Compacting…")
                : t("Ускорить", "Speed up"));
        compactContextAction.setVisibility(suggest ? VISIBLE : GONE);
        compactContextAction.setEnabled(!compacting);
        newSessionAction.setVisibility(suggest ? VISIBLE : GONE);
        contextRow.setVisibility(
                activeTab() == TabBarView.TAB_CONSOLE ? VISIBLE : GONE
        );
    }

    public void setGenerationSpeed(GenerationSpeed speed) {
        if (speed == null) {
            generationRateLabel.setText("");
            generationRateLabel.setContentDescription(null);
            generationRateLabel.setVisibility(GONE);
            return;
        }
        generationRateLabel.setText(speed.label(language.locale, language));
        generationRateLabel.setContentDescription(
                speed.contentDescription(language.locale, language)
        );
        generationRateLabel.setTextColor(speed.estimated ? p.muted : p.accent);
        generationRateLabel.setVisibility(VISIBLE);
    }

    /** Displays an elapsed inference phase before the provider can report generated tokens. */
    public void setGenerationProgress(String label) {
        if (label == null || label.isBlank()) {
            generationRateLabel.setText("");
            generationRateLabel.setContentDescription(null);
            generationRateLabel.setVisibility(GONE);
            return;
        }
        generationRateLabel.setText(label);
        generationRateLabel.setContentDescription(label);
        generationRateLabel.setTextColor(p.muted);
        generationRateLabel.setVisibility(VISIBLE);
    }

    /** Every JSONL event moves the execution row, not just the first one. */
    public void setExecutionLabel(String label) {
        if (executionRow.isRunning()) executionRow.setOperation(label);
    }

    public void setEntries(List<ConsoleEntry> restored) {
        clearStreamingState();
        entries.clear();
        blocks.clear();
        stream.removeAllViews();
        openTrace = null;
        lastWrittenPath = "";
        for (ConsoleEntry entry : restored) renderEntry(entry, false);
        updateEmptyState();
        scrollToEnd();
    }

    public void addEntry(ConsoleEntry entry) {
        renderEntry(entry, true);
        updateEmptyState();
        scrollToEnd();
    }

    /**
     * A failure card takes over the stream: everything above it drops to 60% so the card is read
     * first. The next thing the user says brings the history back.
     */
    public void addFailure(FailureCardView.Failure failure) {
        openTrace = null;
        ConsoleEntry entry = new ConsoleEntry(
                ConsoleEntry.Channel.ERROR, failure.transcriptText()
        );
        View card = new FailureCardView(getContext(), style, failure);
        for (int index = 0; index < stream.getChildCount(); index++) {
            stream.getChildAt(index).setAlpha(0.6f);
        }
        attachBlock(card, true);
        entries.add(entry);
        blocks.add(card);
        trimToCap();
        updateEmptyState();
        scrollToEnd();
    }

    /**
     * A pending overwrite, drawn where the agent stopped. It stays in the stream until it is
     * answered or expires, so the transcript records that the pause happened.
     */
    public void addDecision(DecisionCardView.Decision decision, DecisionCardView.Listener owner) {
        dismissDecision();
        openTrace = null;
        decisionCard = new DecisionCardView(
                getContext(), style, decision, language, (approvalId, confirmed) -> {
            dismissDecision();
            owner.onDecision(approvalId, confirmed);
        });
        attachBlock(decisionCard, true);
        entries.add(new ConsoleEntry(
                ConsoleEntry.Channel.SYSTEM, decision.transcriptText(language)
        ));
        blocks.add(decisionCard);
        trimToCap();
        updateEmptyState();
        scrollToEnd();
    }

    /** Removes the pending card when the approval is resolved elsewhere, or expires. */
    public void dismissDecision() {
        if (decisionCard == null) return;
        stream.removeView(decisionCard);
        blocks.remove(decisionCard);
        decisionCard = null;
    }

    public boolean hasPendingDecision() {
        return decisionCard != null;
    }

    /** A one-per-turn warning that is not a failure: the phone is hot and the deck is slower. */
    public void addThermalNotice(String text) {
        LinearLayout notice = new LinearLayout(getContext());
        notice.setOrientation(LinearLayout.HORIZONTAL);
        notice.setBackground(style.outlined(p.background, p.stroke, 7));
        notice.setPadding(style.dp(15), style.dp(13), style.dp(15), style.dp(13));
        notice.addView(style.monoTrace("!", p.warn), new LinearLayout.LayoutParams(
                style.dp(16), ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        notice.addView(style.caption(text), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        openTrace = null;
        attachBlock(notice, true);
        entries.add(new ConsoleEntry(ConsoleEntry.Channel.SYSTEM, text));
        blocks.add(notice);
        trimToCap();
        updateEmptyState();
        scrollToEnd();
    }

    /** Convenience for the bridge's tool events. */
    public void addTrace(String verb, String argument, String detail) {
        addEntry(ConsoleEntry.trace(verb, argument, detail));
    }

    /** The result column of the call that is still open — duration, size, or a fault marker. */
    public void completeTrace(String detail) {
        if (openTrace == null || detail == null || detail.isEmpty()) return;
        openTrace.completeLast(detail);
        int last = entries.size() - 1;
        if (last < 0 || !entries.get(last).isTrace()) return;
        ConsoleEntry entry = entries.get(last);
        entries.set(last, new ConsoleEntry(
                entry.channel, entry.text, entry.time, entry.verb, detail
        ));
    }

    private void renderEntry(ConsoleEntry entry, boolean animate) {
        View block;
        if (entry.isTrace()) {
            if (openTrace == null) {
                openTrace = new TraceFeedView(getContext(), style, language);
                attachBlock(openTrace, animate);
            }
            openTrace.add(entry.verb, entry.text, entry.detail);
            if (WRITING_VERBS.contains(entry.verb.toLowerCase(Locale.ROOT))) {
                lastWrittenPath = entry.text;
            }
            block = openTrace;
        } else {
            openTrace = null;
            block = switch (entry.channel) {
                case USER -> {
                    lastWrittenPath = "";
                    // A new turn ends the dimming and retires stale recovery actions. Blocking
                    // setup failures stay actionable because they cannot coexist with a prompt.
                    for (int index = 0; index < stream.getChildCount(); index++) {
                        View child = stream.getChildAt(index);
                        child.setAlpha(1f);
                        if (child instanceof FailureCardView failureCard) {
                            failureCard.resolveNonBlocking(
                                    t("Продолжено новым запросом", "Continued with a new request")
                            );
                        }
                    }
                    yield userBubble(entry.text);
                }
                case AGENT -> answerBlock(entry, true);
                case ERROR -> noticeBlock(entry.text, p.error, p.errorText);
                default -> noticeBlock(entry.text, p.ok, p.textSecondary);
            };
            attachBlock(block, animate);
        }
        entries.add(entry);
        blocks.add(block);
        trimToCap();
    }

    private void attachBlock(View block, boolean animate) {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = style.dp(20);
        stream.addView(block, lp);
        if (!animate || !style.animationsEnabled()) return;
        block.setAlpha(0f);
        block.setTranslationY(style.dpf(10));
        block.animate().alpha(1f).translationY(0f).setDuration(500).start();
    }

    /**
     * The transcript is capped at what preferences will persist, and the stream is trimmed with
     * it — a block only leaves once no surviving entry is still drawn into it.
     */
    private void trimToCap() {
        while (entries.size() > 60) {
            entries.remove(0);
            View dropped = blocks.remove(0);
            if (!blocks.contains(dropped)) stream.removeView(dropped);
            if (streamingEntryIndex >= 0) streamingEntryIndex--;
        }
    }

    private View userBubble(String text) {
        TextView bubble = style.body(text);
        bubble.setBackground(bubbleBackground());
        bubble.setPadding(style.dp(16), style.dp(13), style.dp(16), style.dp(13));
        bubble.setMaxWidth(Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.82f - style.dpf(44)
        ));
        bubble.setTextIsSelectable(true);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.addView(bubble);
        return row;
    }

    private android.graphics.drawable.GradientDrawable bubbleBackground() {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setColor(p.panel);
        float large = style.dpf(12);
        float small = style.dpf(3);
        drawable.setCornerRadii(new float[]{
                large, large, large, large, small, small, large, large
        });
        return drawable;
    }

    private LinearLayout answerBlock(String text, boolean withActions) {
        return answerBlock(new ConsoleEntry(ConsoleEntry.Channel.AGENT, text), withActions);
    }

    private LinearLayout answerBlock(ConsoleEntry entry, boolean withActions) {
        String text = entry.text;
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        TextView answer = style.body("");
        answer.setText(highlightPaths(text));
        answer.setTextIsSelectable(true);
        answer.setOnLongClickListener(view -> {
            copyToClipboard(((TextView) view).getText().toString());
            return true;
        });
        block.addView(answer, matchWidth());
        if (withActions) block.addView(answerFooter(entry), chipsLp());
        return block;
    }

    private View answerFooter(ConsoleEntry entry) {
        if (!entry.hasExactSpeed()) return actionChips(entry.text);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GenerationSpeed speed = GenerationSpeed.exact(
                entry.tokensPerSecond, entry.outputTokens
        );
        TextView rate = style.monoAt(
                speed.label(language.locale, language), 10f, p.accent, true
        );
        rate.setSingleLine(true);
        rate.setContentDescription(speed.contentDescription(language.locale, language));
        row.addView(rate, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        row.addView(actionChips(entry.text));
        return row;
    }

    private LinearLayout.LayoutParams chipsLp() {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.topMargin = style.dp(14);
        return lp;
    }

    /** The chip set follows what the turn actually touched. */
    private View actionChips(String answer) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        String written = lastWrittenPath;
        if (!written.isEmpty()) {
            row.addView(style.chip(
                    t("Открыть файл", "Open file"),
                    () -> listener.onOpenFile(written)
            ), chipLp());
        }
        row.addView(style.chip(
                t("Копировать", "Copy"), () -> copyToClipboard(answer)
        ), chipLp());
        row.addView(style.chip(
                t("Отправить", "Share"), () -> share(answer)
        ), chipLp());
        return row;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.rightMargin = style.dp(8);
        return lp;
    }

    /** System notes and faults: a stripe carries the severity, the text stays plain. */
    private View noticeBlock(String text, int stripeColor, int textColor) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);

        View stripe = new View(getContext());
        stripe.setBackgroundColor(stripeColor);
        LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(
                style.dp(3), ViewGroup.LayoutParams.MATCH_PARENT
        );
        row.addView(stripe, stripeLp);

        TextView message = style.bodySecondary(text);
        message.setTextColor(textColor);
        message.setBackground(style.stripedCard(p.panel, 8));
        message.setPadding(style.dp(15), style.dp(13), style.dp(15), style.dp(13));
        message.setTextIsSelectable(true);
        message.setOnLongClickListener(view -> {
            copyToClipboard(((TextView) view).getText().toString());
            return true;
        });
        row.addView(message, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        return row;
    }

    private SpannableString highlightPaths(String text) {
        SpannableString value = new SpannableString(text);
        Matcher matcher = PATH_LIKE.matcher(text);
        while (matcher.find()) {
            value.setSpan(
                    new TypefaceSpan("monospace"),
                    matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            value.setSpan(
                    new ForegroundColorSpan(p.accent),
                    matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return value;
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PI//DECK", text));
        Toast.makeText(
                getContext(), t("Скопировано", "Copied"), Toast.LENGTH_SHORT
        ).show();
    }

    private void share(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        getContext().startActivity(Intent.createChooser(
                intent, t("Отправить ответ", "Share answer")
        ));
    }

    public void beginStreaming() {
        if (streamingMessage != null) return;
        streamingText.setLength(0);
        renderedStreamingLength = 0;
        streamingEntryTime = System.currentTimeMillis();
        streamingEntryIndex = entries.size();
        openTrace = null;
        streamingAnswer = answerBlock("…", false);
        streamingMessage = (TextView) streamingAnswer.getChildAt(0);
        // A fade/translation while the first letters alter this block looks like display flicker.
        attachBlock(streamingAnswer, false);
        entries.add(new ConsoleEntry(ConsoleEntry.Channel.AGENT, "…", streamingEntryTime));
        blocks.add(streamingAnswer);
        trimToCap();
        updateEmptyState();
        scrollToEnd();
    }

    public void appendStreaming(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (streamingMessage == null) beginStreaming();
        int remaining = 256 * 1024 - streamingText.length();
        if (remaining > 0) {
            streamingText.append(delta, 0, Math.min(delta.length(), remaining));
        }
        if (!streamingRenderScheduled) {
            streamingRenderScheduled = true;
            postDelayed(renderStreamingFrame, 32L);
        }
    }

    /** Draws any deltas queued for the next frame before transcript persistence or a tool row. */
    public void flushStreaming() {
        if (!streamingRenderScheduled) return;
        removeCallbacks(renderStreamingFrame);
        streamingRenderScheduled = false;
        renderStreamingText();
    }

    private void renderStreamingText() {
        if (streamingMessage == null) return;
        boolean follow = isNearStreamEnd();
        String value = streamingText.length() == 0 ? "…" : streamingText.toString();
        // Path regex + spans over the whole growing answer are deferred until completion.
        // Appending only the new suffix avoids re-laying out the entire answer every frame.
        if (renderedStreamingLength == 0 && streamingText.length() > 0) {
            streamingMessage.setText("");
        }
        if (streamingText.length() > renderedStreamingLength) {
            streamingMessage.append(
                    streamingText.substring(renderedStreamingLength, streamingText.length())
            );
            renderedStreamingLength = streamingText.length();
        }
        if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
            entries.set(
                    streamingEntryIndex,
                    new ConsoleEntry(ConsoleEntry.Channel.AGENT, value, streamingEntryTime)
            );
        }
        if (follow) scrollToEnd();
    }

    public String finishStreaming(String terminalAnswer) {
        return finishStreaming(terminalAnswer, null);
    }

    public String finishStreaming(String terminalAnswer, GenerationSpeed exactSpeed) {
        boolean follow = isNearStreamEnd();
        if (streamingRenderScheduled) {
            removeCallbacks(renderStreamingFrame);
            streamingRenderScheduled = false;
        }
        String value = TurnOutputContract.reconcileTerminal(
                streamingText.toString(),
                terminalAnswer,
                t(
                        "Задача завершена без текстового ответа.",
                        "The task completed without a text response."
                )
        );
        if (streamingMessage == null) {
            addEntry(completedAnswer(value, System.currentTimeMillis(), exactSpeed));
        } else {
            streamingMessage.setText(highlightPaths(value));
            ConsoleEntry completed = completedAnswer(value, streamingEntryTime, exactSpeed);
            streamingAnswer.addView(answerFooter(completed), chipsLp());
            if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
                entries.set(streamingEntryIndex, completed);
            }
        }
        clearStreamingState();
        if (follow) scrollToEnd();
        return value;
    }

    private ConsoleEntry completedAnswer(
            String value,
            long time,
            GenerationSpeed exactSpeed
    ) {
        if (exactSpeed == null || exactSpeed.estimated) {
            return new ConsoleEntry(ConsoleEntry.Channel.AGENT, value, time);
        }
        return new ConsoleEntry(
                ConsoleEntry.Channel.AGENT,
                value,
                time,
                "",
                "",
                exactSpeed.tokensPerSecond,
                exactSpeed.outputTokens
        );
    }

    public void discardStreaming() {
        if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
            entries.remove(streamingEntryIndex);
            blocks.remove(streamingEntryIndex);
        }
        if (streamingAnswer != null) stream.removeView(streamingAnswer);
        clearStreamingState();
        updateEmptyState();
    }

    private void clearStreamingState() {
        if (streamingRenderScheduled) removeCallbacks(renderStreamingFrame);
        streamingRenderScheduled = false;
        streamingMessage = null;
        streamingAnswer = null;
        streamingEntryIndex = -1;
        renderedStreamingLength = 0;
        streamingEntryTime = 0L;
        streamingText.setLength(0);
    }

    public Palette palette() {
        return p;
    }

    public DeckStyle style() {
        return style;
    }

    public List<ConsoleEntry> entries() {
        return TurnOutputContract.durableSnapshot(entries, streamingEntryIndex);
    }

    public void clearEntries() {
        entries.clear();
        blocks.clear();
        stream.removeAllViews();
        openTrace = null;
        lastWrittenPath = "";
        clearStreamingState();
        updateEmptyState();
    }

    /** The scenario cards only make sense when nothing has happened yet. */
    private void updateEmptyState() {
        boolean empty = entries.isEmpty() && bootPanel.getVisibility() == GONE;
        emptyState.setVisibility(empty ? VISIBLE : GONE);
    }

    public void setPrompt(String value) {
        promptInput.setText(value);
        promptInput.setSelection(promptInput.getText().length());
        promptInput.requestFocus();
    }

    /** Restores working text without opening the keyboard on launch. */
    public void restorePrompt(String value) {
        promptInput.setText(value == null ? "" : value);
        promptInput.setSelection(promptInput.getText().length());
        clearInitialComposerFocus();
    }

    public String prompt() {
        return promptInput.getText().toString();
    }

    /** The deck opens as a status surface; the keyboard appears only after an explicit tap. */
    public void clearInitialComposerFocus() {
        promptInput.clearFocus();
        requestFocus();
    }

    private void emitPrompt() {
        if (composerDispatchPending
                || (executionRow.isRunning() && queuedPromptCount > 0)) return;
        String value = promptInput.getText().toString().trim();
        if (value.isEmpty()) return;
        listener.onSend(value);
    }

    private boolean isNearStreamEnd() {
        if (streamScroll == null || streamScroll.getChildCount() == 0) return true;
        View content = streamScroll.getChildAt(0);
        int remaining = content.getHeight() - streamScroll.getHeight() - streamScroll.getScrollY();
        return remaining <= style.dp(72);
    }

    private String t(String russian, String english) {
        return language.pick(russian, english);
    }

    private String[][] scenarios() {
        return language == UiLanguage.ENGLISH ? SCENARIOS_EN : SCENARIOS_RU;
    }

    private void scrollToEnd() {
        if (scrollScheduled) return;
        scrollScheduled = true;
        streamScroll.postOnAnimation(scrollToEndFrame);
    }

    private View divider() {
        View view = new View(getContext());
        view.setBackgroundColor(p.strokeFaint);
        return view;
    }

    private LinearLayout.LayoutParams dividerLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(1)
        );
    }

    private LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    /** The 6 dp core indicator in the header. */
    private static final class StatusDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StatusDot(Context context, int color) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }
    }
}
