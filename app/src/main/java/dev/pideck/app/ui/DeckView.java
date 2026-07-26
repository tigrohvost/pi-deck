package dev.pideck.app.ui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The shell of the deck: header, the three roots the tab bar switches between, the prompt field
 * and the tab bar itself.
 *
 * <p>Only the console root owns a prompt field, so the field and the deck's busy state hide with
 * it. Each root keeps its own scroll position and content across switches — switching is free.
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
    }

    private final DeckStyle style;
    private final Listener listener;
    private final Palette p;

    private final TextView coreStatusLabel;
    private final StatusDot coreStatusDot;
    private final FrameLayout contentHost;
    private final LinearLayout consoleRoot;
    private final CoreRootView coreRoot;
    private final SessionsRootView sessionsRoot;
    private final LinearLayout inputRow;
    private final TabBarView tabBar;

    private LinearLayout bootPanel;
    private TextView bootKicker;
    private TextView bootTitle;
    private TextView bootBody;
    private LinearLayout bootActions;
    private ScrollView terminalScroll;
    private LinearLayout transcript;
    private TextView busyLine;
    private EditText promptInput;
    private TextView sendButton;

    private final List<ConsoleEntry> entries = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private ObjectAnimator pulse;
    private TextView streamingMessage;
    private int streamingEntryIndex = -1;
    private long streamingEntryTime;
    private final StringBuilder streamingText = new StringBuilder();

    public DeckView(Context context, Listener listener, Palette palette, float textScale) {
        super(context);
        this.listener = listener;
        this.p = palette;
        this.style = new DeckStyle(context, palette, textScale);
        setBackgroundColor(p.background);

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
        coreStatusLabel = style.monoAt("Ядро спит", 11f, p.error, true);
        header.addView(coreStatusLabel);
        root.addView(header, matchWidth());
        root.addView(divider(), dividerLp());

        contentHost = new FrameLayout(context);
        root.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        consoleRoot = buildConsoleRoot(context);
        contentHost.addView(consoleRoot, match());
        coreRoot = new CoreRootView(context, style, this);
        coreRoot.setVisibility(GONE);
        contentHost.addView(coreRoot, match());
        sessionsRoot = new SessionsRootView(context, style);
        sessionsRoot.setVisibility(GONE);
        contentHost.addView(sessionsRoot, match());

        inputRow = buildInput(context);
        root.addView(inputRow, matchWidth());

        root.addView(divider(), dividerLp());
        tabBar = new TabBarView(context, style, listener::onTabSelected);
        root.addView(tabBar, matchWidth());

        addView(new ScanlineView(context, p), match());

        if (Build.VERSION.SDK_INT >= 35) {
            setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets safe = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                root.setPadding(safe.left, safe.top, safe.right, 0);
                tabBar.setPadding(0, 0, 0, Math.max(safe.bottom, style.dp(22)));
                return windowInsets;
            });
        } else {
            tabBar.setPadding(0, 0, 0, style.dp(22));
        }
        setCoreStatus(CoreStatus.SLEEPING, null);
    }

    private LinearLayout buildConsoleRoot(Context context) {
        LinearLayout console = new LinearLayout(context);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setPadding(style.dp(22), style.dp(14), style.dp(22), 0);

        bootPanel = new LinearLayout(context);
        bootPanel.setOrientation(LinearLayout.VERTICAL);
        bootPanel.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14));
        bootPanel.setBackground(style.outlined(p.panel, p.accent, 7));

        bootKicker = style.monoLabel("BOOT SEQUENCE // 00", p.accent);
        bootPanel.addView(bootKicker);

        bootTitle = style.cardTitle("ЛОКАЛЬНОЕ ЯДРО");
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
        bootLp.bottomMargin = style.dp(14);
        console.addView(bootPanel, bootLp);

        FrameLayout terminalFrame = new FrameLayout(context);
        console.addView(terminalFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        terminalScroll = new ScrollView(context);
        terminalScroll.setFillViewport(true);
        terminalScroll.setVerticalScrollBarEnabled(false);
        terminalScroll.setClipToPadding(false);
        transcript = new LinearLayout(context);
        transcript.setOrientation(LinearLayout.VERTICAL);
        terminalScroll.addView(transcript, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        terminalFrame.addView(terminalScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        busyLine = style.monoLabel("◆ PI CORE THINKING", p.ok);
        busyLine.setPadding(style.dp(13), style.dp(11), style.dp(13), style.dp(11));
        busyLine.setBackground(style.round(p.cardFill, 8));
        busyLine.setVisibility(GONE);
        terminalFrame.addView(busyLine, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));
        return console;
    }

    private LinearLayout buildInput(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(style.dp(18), style.dp(14), style.dp(18), style.dp(14));

        EditText field = new EditText(context);
        promptInput = field;
        field.setTextColor(p.text);
        field.setHintTextColor(p.muted);
        field.setHint("Что сделать?");
        field.setTextSize(14.5f * style.textScale());
        field.setLineSpacing(0f, 1.4f);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setPadding(style.dp(16), style.dp(13), style.dp(16), style.dp(13));
        field.setMinHeight(style.dp(44));
        field.setMaxHeight(style.dp(128));
        field.setSingleLine(false);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        );
        field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setBackground(style.round(p.panel, 22));
        field.setOnEditorActionListener((view, actionId, event) -> {
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
        row.addView(field, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        TextView send = style.monoAt("↑", 17f, p.muted, false);
        sendButton = send;
        send.setGravity(Gravity.CENTER);
        send.setBackground(style.round(p.panel, 22));
        style.clickable(send, this::emitPrompt);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                style.dp(44), style.dp(44)
        );
        sendLp.leftMargin = style.dp(11);
        row.addView(send, sendLp);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                updateSendAffordance(editable.toString().trim().isEmpty());
            }
        });
        return row;
    }

    private void updateSendAffordance(boolean empty) {
        sendButton.setBackground(style.round(empty ? p.panel : p.accent, 22));
        sendButton.setTextColor(empty ? p.muted : p.background);
    }

    public void setActiveTab(int tab) {
        tabBar.setSelectedTab(tab);
        consoleRoot.setVisibility(tab == TabBarView.TAB_CONSOLE ? VISIBLE : GONE);
        coreRoot.setVisibility(tab == TabBarView.TAB_CORE ? VISIBLE : GONE);
        sessionsRoot.setVisibility(tab == TabBarView.TAB_SESSIONS ? VISIBLE : GONE);
        inputRow.setVisibility(tab == TabBarView.TAB_CONSOLE ? VISIBLE : GONE);
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

    @Override
    public void onSchemeChosen(String schemeId) {
        listener.onSchemeChosen(schemeId);
    }

    @Override
    public void onTextScaleChosen(float scale) {
        listener.onTextScaleChosen(scale);
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
            case READY -> detail == null || detail.isBlank() ? "Ядро живо" : detail;
            case BUSY -> "Работает";
            case STARTING -> "Поднимаю ядро";
            case AWAITING_USER -> "Ждёт вас";
            case FAILED -> "Ядро упало";
            case SLEEPING -> "Ядро спит";
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
        if (primaryLabel != null && primary != null) {
            bootActions.addView(style.primaryButton(primaryLabel, primary));
        }
        if (secondaryLabel != null && secondary != null) {
            TextView second = style.outlinedButton(secondaryLabel, p.accent, secondary);
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = style.dp(9);
            bootActions.addView(second, lp);
        }
    }

    public void hideBootPanel() {
        bootPanel.setVisibility(GONE);
    }

    public boolean isBootPanelVisible() {
        return bootPanel.getVisibility() == VISIBLE;
    }

    public void setBusy(boolean busy, String label) {
        busyLine.setText(String.format(
                Locale.ROOT,
                "◆ %s",
                label == null ? "PI CORE THINKING" : label.toUpperCase(Locale.ROOT)
        ));
        busyLine.setVisibility(busy ? VISIBLE : GONE);
        promptInput.setEnabled(!busy);
        sendButton.setEnabled(!busy);
        sendButton.setAlpha(busy ? 0.4f : 1f);
        if (pulse != null) pulse.cancel();
        if (busy && style.animationsEnabled()) {
            pulse = ObjectAnimator.ofFloat(busyLine, View.ALPHA, 0.35f, 1f);
            pulse.setDuration(1_200);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
        } else {
            pulse = null;
            busyLine.setAlpha(1f);
        }
    }

    public void setEntries(List<ConsoleEntry> restored) {
        entries.clear();
        transcript.removeAllViews();
        for (ConsoleEntry entry : restored) addEntryInternal(entry, false);
        scrollToEnd();
    }

    public void addEntry(ConsoleEntry entry) {
        addEntryInternal(entry, true);
    }

    private TextView addEntryInternal(ConsoleEntry entry, boolean animate) {
        entries.add(entry);
        while (entries.size() > 60) {
            entries.remove(0);
            if (streamingEntryIndex >= 0) streamingEntryIndex--;
            if (transcript.getChildCount() > 0) transcript.removeViewAt(0);
        }

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(style.dp(11), style.dp(11), style.dp(13), style.dp(11));
        int color = channelColor(entry.channel);
        box.setBackground(style.outlined(p.fill(color, 0.09f, 0xBC), color, 7));

        View stripe = new View(getContext());
        stripe.setBackgroundColor(color);
        LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(
                style.dp(2), ViewGroup.LayoutParams.MATCH_PARENT
        );
        stripeLp.rightMargin = style.dp(11);
        box.addView(stripe, stripeLp);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        box.addView(content, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        TextView label = style.monoLabel(
                channelLabel(entry.channel) + "  " + time(entry.time),
                channelTextColor(entry.channel)
        );
        content.addView(label);

        TextView message = style.body(entry.text);
        if (entry.channel == ConsoleEntry.Channel.ERROR) message.setTextColor(p.errorText);
        message.setTextIsSelectable(true);
        message.setPadding(0, style.dp(6), 0, 0);
        message.setOnLongClickListener(view -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "PI//DECK", ((TextView) view).getText()
            ));
            Toast.makeText(getContext(), "Скопировано", Toast.LENGTH_SHORT).show();
            return true;
        });
        content.addView(message);

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        boxLp.bottomMargin = style.dp(8);
        transcript.addView(box, boxLp);
        if (animate && style.animationsEnabled()) {
            box.setAlpha(0f);
            box.setTranslationY(style.dpf(10));
            box.animate().alpha(1f).translationY(0).setDuration(500).start();
        }
        scrollToEnd();
        return message;
    }

    public void beginStreaming() {
        if (streamingMessage != null) return;
        streamingText.setLength(0);
        streamingEntryTime = System.currentTimeMillis();
        streamingEntryIndex = entries.size();
        streamingMessage = addEntryInternal(
                new ConsoleEntry(ConsoleEntry.Channel.AGENT, "…", streamingEntryTime),
                true
        );
    }

    public void appendStreaming(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (streamingMessage == null) beginStreaming();
        int remaining = 256 * 1024 - streamingText.length();
        if (remaining > 0) {
            streamingText.append(delta, 0, Math.min(delta.length(), remaining));
        }
        String value = streamingText.length() == 0 ? "…" : streamingText.toString();
        streamingMessage.setText(value);
        if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
            entries.set(
                    streamingEntryIndex,
                    new ConsoleEntry(ConsoleEntry.Channel.AGENT, value, streamingEntryTime)
            );
        }
        scrollToEnd();
    }

    public String finishStreaming(String fallback) {
        String value = streamingText.length() == 0
                ? (fallback == null || fallback.isBlank()
                ? "Задача завершена без текстового ответа."
                : fallback)
                : streamingText.toString();
        if (streamingMessage == null) {
            addEntry(new ConsoleEntry(ConsoleEntry.Channel.AGENT, value));
        } else {
            streamingMessage.setText(value);
            if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
                entries.set(
                        streamingEntryIndex,
                        new ConsoleEntry(ConsoleEntry.Channel.AGENT, value, streamingEntryTime)
                );
            }
        }
        streamingMessage = null;
        streamingEntryIndex = -1;
        streamingEntryTime = 0L;
        streamingText.setLength(0);
        return value;
    }

    public void discardStreaming() {
        if (streamingEntryIndex >= 0 && streamingEntryIndex < entries.size()) {
            entries.remove(streamingEntryIndex);
            if (streamingEntryIndex < transcript.getChildCount()) {
                transcript.removeViewAt(streamingEntryIndex);
            }
        }
        streamingMessage = null;
        streamingEntryIndex = -1;
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
        return new ArrayList<>(entries);
    }

    public void clearEntries() {
        entries.clear();
        transcript.removeAllViews();
        addEntry(new ConsoleEntry(
                ConsoleEntry.Channel.SYSTEM, "Терминал очищен. Сессия Pi сохранена."
        ));
    }

    public void setPrompt(String value) {
        promptInput.setText(value);
        promptInput.setSelection(promptInput.getText().length());
        promptInput.requestFocus();
    }

    private void emitPrompt() {
        String value = promptInput.getText().toString().trim();
        if (value.isEmpty() || !promptInput.isEnabled()) return;
        promptInput.setText("");
        listener.onSend(value);
    }

    private void scrollToEnd() {
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
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

    private int channelColor(ConsoleEntry.Channel channel) {
        return switch (channel) {
            case USER -> p.accentAlt;
            case AGENT -> p.accent;
            case SYSTEM -> p.ok;
            case TOOL -> p.warn;
            case ERROR -> p.error;
        };
    }

    private int channelTextColor(ConsoleEntry.Channel channel) {
        return channel == ConsoleEntry.Channel.ERROR ? p.errorText : channelColor(channel);
    }

    private String channelLabel(ConsoleEntry.Channel channel) {
        return switch (channel) {
            case USER -> "ВЫ";
            case AGENT -> "PI // LOCAL";
            case SYSTEM -> "SYS // DECK";
            case TOOL -> "TOOL // TRACE";
            case ERROR -> "FAULT // CORE";
        };
    }

    private String time(long value) {
        return clock.format(new Date(value));
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
