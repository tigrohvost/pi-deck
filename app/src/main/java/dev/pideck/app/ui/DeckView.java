package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.pideck.app.core.ModelSpec;

@SuppressLint("ViewConstructor")
public final class DeckView extends FrameLayout {
    public interface Listener {
        void onSend(String prompt);

        void onModels();

        void onCore();

        void onTermux();

        void onClear();
    }

    private final float density;
    private final Listener listener;
    private final Palette p;
    private final LinearLayout statusRail;
    private final LinearLayout bootPanel;
    private final TextView bootKicker;
    private final TextView bootTitle;
    private final TextView bootBody;
    private final LinearLayout bootActions;
    private final ScrollView terminalScroll;
    private final LinearLayout transcript;
    private final TextView busyLine;
    private EditText promptInput;
    private TextView sendButton;
    private final TextView engineLine;
    private final List<ConsoleEntry> entries = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private ObjectAnimator pulse;

    public DeckView(Context context, Listener listener, Palette palette) {
        super(context);
        this.listener = listener;
        this.p = palette;
        this.density = getResources().getDisplayMetrics().density;
        setBackgroundColor(p.background);

        addView(new GridBackdropView(context, p), match());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(11), dp(14), dp(9));
        addView(root, match());

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusRail = new LinearLayout(context);
        statusRail.setOrientation(LinearLayout.HORIZONTAL);
        statusRail.setGravity(Gravity.CENTER_VERTICAL);
        statusRail.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusRail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        bootPanel = new LinearLayout(context);
        bootPanel.setOrientation(LinearLayout.VERTICAL);
        bootPanel.setPadding(dp(14), dp(12), dp(14), dp(13));
        bootPanel.setBackground(panel(p.accent, p.fill(0xD8), 1, 6));

        bootKicker = text("BOOT SEQUENCE // 00", 10, p.accent, Typeface.BOLD);
        bootKicker.setLetterSpacing(0.16f);
        bootPanel.addView(bootKicker);

        bootTitle = text("ЛОКАЛЬНОЕ ЯДРО", 17, p.text, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = wrap();
        titleLp.topMargin = dp(4);
        bootPanel.addView(bootTitle, titleLp);

        bootBody = text("", 12, p.muted, Typeface.NORMAL);
        bootBody.setLineSpacing(0, 1.17f);
        LinearLayout.LayoutParams bodyLp = wrap();
        bodyLp.topMargin = dp(6);
        bootPanel.addView(bootBody, bodyLp);

        bootActions = new LinearLayout(context);
        bootActions.setOrientation(LinearLayout.HORIZONTAL);
        bootActions.setGravity(Gravity.START);
        LinearLayout.LayoutParams actionsLp = wrap();
        actionsLp.topMargin = dp(10);
        bootPanel.addView(bootActions, actionsLp);

        LinearLayout.LayoutParams bootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bootLp.bottomMargin = dp(8);
        root.addView(bootPanel, bootLp);

        FrameLayout terminalFrame = new FrameLayout(context);
        terminalFrame.setBackground(panel(p.accent, p.fill(0xB8), 1, 4));
        LinearLayout.LayoutParams terminalLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        root.addView(terminalFrame, terminalLp);

        terminalScroll = new ScrollView(context);
        terminalScroll.setFillViewport(true);
        terminalScroll.setVerticalScrollBarEnabled(false);
        terminalScroll.setClipToPadding(false);
        terminalScroll.setPadding(dp(10), dp(7), dp(8), dp(9));
        transcript = new LinearLayout(context);
        transcript.setOrientation(LinearLayout.VERTICAL);
        terminalScroll.addView(transcript, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        terminalFrame.addView(terminalScroll, match());

        busyLine = text("◆ PI CORE THINKING", 10, p.ok, Typeface.BOLD);
        busyLine.setLetterSpacing(0.16f);
        busyLine.setPadding(dp(10), dp(6), dp(10), dp(6));
        busyLine.setBackground(panel(p.ok, p.fill(p.ok, 0.08f, 0xEA), 1, 0));
        busyLine.setVisibility(GONE);
        FrameLayout.LayoutParams busyLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        terminalFrame.addView(busyLine, busyLp);

        root.addView(buildQuickActions(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(buildInput(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        engineLine = text("NO LINK // SELECT RUNTIME", 9, p.muted, Typeface.BOLD);
        engineLine.setGravity(Gravity.CENTER_VERTICAL);
        engineLine.setLetterSpacing(0.12f);
        engineLine.setPadding(dp(2), dp(6), dp(2), 0);
        root.addView(engineLine);

        addView(new ScanlineView(context, p), match());
        setStatus(false, false, null, false, false);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout identity = new LinearLayout(getContext());
        identity.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        header.addView(identity, identityLp);

        SpannableString brandText = new SpannableString("π//DECK");
        brandText.setSpan(new ForegroundColorSpan(p.accentAlt), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        brandText.setSpan(new ForegroundColorSpan(p.accent), 1, brandText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView brand = text("", 24, p.accent, Typeface.BOLD);
        brand.setText(brandText);
        brand.setLetterSpacing(0.08f);
        identity.addView(brand);

        TextView subtitle = text("POCKET INTELLIGENCE TERMINAL", 9, p.muted, Typeface.BOLD);
        subtitle.setLetterSpacing(0.15f);
        identity.addView(subtitle);

        LinearLayout local = new LinearLayout(getContext());
        local.setOrientation(LinearLayout.VERTICAL);
        local.setGravity(Gravity.END);
        TextView offline = text("LOCAL", 11, p.ok, Typeface.BOLD);
        offline.setGravity(Gravity.END);
        TextView privacy = text("ZERO CLOUD", 8, p.muted, Typeface.BOLD);
        privacy.setGravity(Gravity.END);
        privacy.setLetterSpacing(0.12f);
        local.addView(offline);
        local.addView(privacy);
        header.addView(local);
        return header;
    }

    private View buildQuickActions() {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setPadding(0, dp(7), 0, dp(6));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(action("◫ MODELS", p.accent, listener::onModels));
        row.addView(action("⌁ CORE", p.accentAlt, listener::onCore));
        row.addView(action(">_ TERMUX", p.ok, listener::onTermux));
        row.addView(action("× CLEAR", p.muted, listener::onClear));
        scroll.addView(row);
        return scroll;
    }

    private View buildInput() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        promptInput = new EditText(getContext());
        promptInput.setTextColor(p.text);
        promptInput.setHintTextColor(p.hint);
        promptInput.setHint("› опиши, что нужно создать…");
        promptInput.setTextSize(14);
        promptInput.setTypeface(Typeface.MONOSPACE);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        promptInput.setPadding(dp(12), dp(10), dp(10), dp(9));
        promptInput.setMinHeight(dp(48));
        promptInput.setMaxHeight(dp(128));
        promptInput.setSingleLine(false);
        promptInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        );
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        promptInput.setBackground(panel(p.accent, p.fill(0xE8), 1, 5));
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
        row.addView(promptInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = button("RUN ↗", p.accent, this::emitPrompt);
        sendButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(78), dp(48));
        sendLp.leftMargin = dp(7);
        row.addView(sendButton, sendLp);
        return row;
    }

    public void setStatus(
            boolean termuxLinked,
            boolean coreReady,
            ModelSpec model,
            boolean serverReady,
            boolean busy
    ) {
        statusRail.removeAllViews();
        statusRail.addView(chip(termuxLinked ? "TERMUX LINK" : "NO LINK", termuxLinked ? p.ok : p.error));
        statusRail.addView(chip(coreReady ? "PI READY" : "PI ABSENT", coreReady ? p.accent : p.warn));
        statusRail.addView(chip(model == null ? "NO MODEL" : model.tier, model == null ? p.muted : p.accentAlt));
        statusRail.addView(chip(busy ? "WORKING" : serverReady ? "LLM LIVE" : "LLM COLD", busy ? p.ok : serverReady ? p.accent : p.muted));
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
        bootKicker.setText(kicker);
        bootTitle.setText(title);
        bootBody.setText(body);
        bootActions.removeAllViews();
        if (primaryLabel != null && primary != null) {
            bootActions.addView(button(primaryLabel, p.accent, primary));
        }
        if (secondaryLabel != null && secondary != null) {
            TextView second = button(secondaryLabel, p.accentAlt, secondary);
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = dp(8);
            bootActions.addView(second, lp);
        }
    }

    public void hideBootPanel() {
        bootPanel.setVisibility(GONE);
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
        if (busy) {
            pulse = ObjectAnimator.ofFloat(busyLine, View.ALPHA, 0.42f, 1f);
            pulse.setDuration(780);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
        } else {
            pulse = null;
            busyLine.setAlpha(1f);
        }
    }

    public void setEngineLine(String value) {
        engineLine.setText(value == null ? "" : value.toUpperCase(Locale.ROOT));
    }

    public void setEntries(List<ConsoleEntry> restored) {
        entries.clear();
        transcript.removeAllViews();
        for (ConsoleEntry entry : restored) addEntryInternal(entry, false);
        if (entries.isEmpty()) {
            addEntryInternal(new ConsoleEntry(
                    ConsoleEntry.Channel.SYSTEM,
                    "Канал открыт. Здесь будет жить локальный Pi: писать программы, запускать Python, читать файлы и работать с сетью."
            ), false);
        }
        scrollToEnd();
    }

    public void addEntry(ConsoleEntry entry) {
        addEntryInternal(entry, true);
    }

    private void addEntryInternal(ConsoleEntry entry, boolean animate) {
        entries.add(entry);
        while (entries.size() > 60) {
            entries.remove(0);
            if (transcript.getChildCount() > 0) transcript.removeViewAt(0);
        }

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(8), dp(8), dp(9), dp(8));
        int color = channelColor(entry.channel);
        box.setBackground(panel(color, channelFill(entry.channel), 1, 3));

        View stripe = new View(getContext());
        stripe.setBackgroundColor(color);
        LinearLayout.LayoutParams stripeLp = new LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT);
        stripeLp.rightMargin = dp(9);
        box.addView(stripe, stripeLp);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        box.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = text(
                channelLabel(entry.channel) + "  " + time(entry.time),
                9, channelTextColor(entry.channel), Typeface.BOLD
        );
        label.setLetterSpacing(0.11f);
        content.addView(label);

        TextView message = text(entry.text, 13, entry.channel == ConsoleEntry.Channel.ERROR ? p.errorText : p.text, Typeface.NORMAL);
        message.setTextIsSelectable(true);
        message.setLineSpacing(0, 1.16f);
        message.setPadding(0, dp(4), 0, 0);
        message.setOnLongClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("PI//DECK", entry.text));
            Toast.makeText(getContext(), "Скопировано", Toast.LENGTH_SHORT).show();
            return true;
        });
        content.addView(message);

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        boxLp.bottomMargin = dp(6);
        transcript.addView(box, boxLp);
        if (animate) {
            box.setAlpha(0f);
            box.setTranslationY(dp(8));
            box.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
        scrollToEnd();
    }

    public Palette palette() {
        return p;
    }

    public List<ConsoleEntry> entries() {
        return new ArrayList<>(entries);
    }

    public void clearEntries() {
        entries.clear();
        transcript.removeAllViews();
        addEntry(new ConsoleEntry(ConsoleEntry.Channel.SYSTEM, "Терминал очищен. Сессия Pi сохранена."));
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

    private TextView action(String label, int color, Runnable action) {
        TextView view = button(label, color, action);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(7);
        view.setLayoutParams(lp);
        return view;
    }

    public TextView button(String label, int color, Runnable action) {
        TextView view = text(label, 11, color, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setLetterSpacing(0.07f);
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setMinHeight(dp(38));
        view.setBackground(panel(color, p.fill(color, 0.06f, 0xD9), 1, 4));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    public TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    public GradientDrawable panel(int stroke, int fill, int strokeDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private TextView chip(String value, int color) {
        TextView chip = text("● " + value, 8, color, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setLetterSpacing(0.07f);
        chip.setPadding(dp(7), dp(5), dp(7), dp(5));
        chip.setBackground(panel(color, p.fill(color, 0.05f, 0xA6), 1, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(5);
        chip.setLayoutParams(lp);
        return chip;
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

    private int channelFill(ConsoleEntry.Channel channel) {
        return p.fill(channelColor(channel), 0.09f, 0xBC);
    }

    private int channelTextColor(ConsoleEntry.Channel channel) {
        return channel == ConsoleEntry.Channel.ERROR ? p.errorText : channelColor(channel);
    }

    private String channelLabel(ConsoleEntry.Channel channel) {
        return switch (channel) {
            case USER -> "YOU // UPLINK";
            case AGENT -> "PI // LOCAL";
            case SYSTEM -> "SYS // DECK";
            case TOOL -> "TOOL // TRACE";
            case ERROR -> "FAULT // CORE";
        };
    }

    private String time(long value) {
        return clock.format(new Date(value));
    }

    public int dp(int value) {
        return Math.round(value * density);
    }

    private LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
