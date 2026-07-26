package dev.pideck.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * The type ramp, spacing scale and shapes of the deck, in one place.
 *
 * <p>Every screen builds its views through these factories rather than spelling out sizes, so a
 * kerning or scale decision is made once. Colours still come from {@link Palette}; nothing here
 * introduces a literal.
 */
public final class DeckStyle {
    /** Text size multipliers offered in CORE, applied to every sp value below. */
    public static final float[] TEXT_SCALES = {1.0f, 1.15f, 1.3f};

    public final Palette palette;

    private final Context context;
    private final float density;
    private final float textScale;
    private final Typeface sans;
    private final Typeface sansMedium;
    private final Typeface mono;
    private final Typeface monoMedium;

    public DeckStyle(Context context, Palette palette, float textScale) {
        this.context = context;
        this.palette = palette;
        this.density = context.getResources().getDisplayMetrics().density;
        this.textScale = normalizeScale(textScale);
        this.sans = Typeface.SANS_SERIF;
        this.sansMedium = weighted(Typeface.SANS_SERIF, "sans-serif-medium", 600);
        this.mono = Typeface.MONOSPACE;
        this.monoMedium = weighted(Typeface.MONOSPACE, "monospace", 600);
    }

    public static float normalizeScale(float value) {
        float closest = TEXT_SCALES[0];
        for (float candidate : TEXT_SCALES) {
            if (Math.abs(candidate - value) < Math.abs(closest - value)) closest = candidate;
        }
        return closest;
    }

    public float textScale() {
        return textScale;
    }

    /** «Дека готова», «Сессии», «Ядро». */
    public TextView screenTitle(String value) {
        return build(value, sansMedium, 24f, 1.2f, -0.01f, palette.text, false);
    }

    /** Heading of a decision or failure card. */
    public TextView cardTitle(String value) {
        return build(value, sansMedium, 16f, 1.3f, 0f, palette.text, false);
    }

    /** List row or scenario headline. */
    public TextView rowTitle(String value) {
        return build(value, sansMedium, 14.5f, 1.4f, 0f, palette.text, false);
    }

    /** Human replies and agent answers. */
    public TextView body(String value) {
        return build(value, sans, 14.5f, 1.55f, 0f, palette.text, false);
    }

    /** Explanations inside cards. */
    public TextView bodySecondary(String value) {
        return build(value, sans, 13.5f, 1.5f, 0f, palette.textSecondary, false);
    }

    /** Footnotes: «Агент видит только…». */
    public TextView caption(String value) {
        return build(value, sans, 12.5f, 1.45f, 0f, palette.muted, false);
    }

    /** «МОДЕЛЬ», «С ЧЕГО НАЧАТЬ» — the smallest type the deck draws. */
    public TextView monoLabel(String value, int color) {
        return build(value, monoMedium, 11f, 1.2f, 0.18f, color, true);
    }

    /** Tool trace lines and their metadata. */
    public TextView monoTrace(String value, int color) {
        return build(value, mono, 11.5f, 1.5f, 0f, color, false);
    }

    /** Paths, sizes, status line. */
    public TextView monoMeta(String value, int color) {
        return build(value, mono, 12f, 1.4f, 0f, color, false);
    }

    /** Button faces. */
    public TextView monoButton(String value, int color) {
        return build(value, monoMedium, 12.5f, 1.2f, 0.12f, color, true);
    }

    /** π//DECK in the header. */
    public TextView wordmark(String value) {
        return build(value, monoMedium, 16f, 1.2f, 0.02f, palette.accent, false);
    }

    /** A label at an explicit size, for the few places the ramp does not cover. */
    public TextView monoAt(String value, float sizeSp, int color, boolean upper) {
        return build(value, monoMedium, sizeSp, 1.2f, 0.1f, color, upper);
    }

    private TextView build(
            String value,
            Typeface typeface,
            float sizeSp,
            float lineMultiplier,
            float tracking,
            int color,
            boolean upper
    ) {
        TextView view = new TextView(context);
        view.setText(upper ? value.toUpperCase(Locale.getDefault()) : value);
        view.setTypeface(typeface);
        view.setTextSize(sizeSp * textScale);
        view.setTextColor(color);
        view.setLineSpacing(0f, lineMultiplier);
        view.setLetterSpacing(tracking);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** Flat rounded fill. */
    public GradientDrawable round(int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dpf(radiusDp));
        return drawable;
    }

    /** Rounded fill with the 1 dp border the deck uses everywhere. */
    public GradientDrawable outlined(int fill, int stroke, float radiusDp) {
        GradientDrawable drawable = round(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    /** Input field and action chips: a capsule whose radius is half its height. */
    public GradientDrawable pill(int fill, int stroke, float radiusDp) {
        return stroke == fill ? round(fill, radiusDp) : outlined(fill, stroke, radiusDp);
    }

    /**
     * Body of a failure card: square on the leading edge, rounded on the trailing one, so the
     * 3 dp stripe view sitting in front of it reads as part of the same shape.
     */
    public GradientDrawable stripedCard(int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        float radius = dpf(radiusDp);
        drawable.setCornerRadii(new float[]{0f, 0f, radius, radius, radius, radius, 0f, 0f});
        return drawable;
    }

    /**
     * Pressed feedback for rows and cards. Android draws the pressed entry of the list, so the
     * 180 ms the spec asks for is the platform's own state transition rather than an animator.
     */
    public StateListDrawable pressable(int fill, int pressedFill, int stroke, float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.setEnterFadeDuration(180);
        states.setExitFadeDuration(180);
        states.addState(
                new int[]{android.R.attr.state_pressed},
                outlined(pressedFill, palette.accent, radiusDp)
        );
        states.addState(new int[0], outlined(fill, stroke, radiusDp));
        return states;
    }

    /** Primary button: accent fill, background-coloured text, 1 dp travel when pressed. */
    public TextView primaryButton(String label, Runnable action) {
        TextView view = monoButton(label, palette.background);
        view.setBackground(round(palette.accent, 5));
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(14), dp(15), dp(14), dp(15));
        view.setMinHeight(dp(44));
        clickable(view, action);
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN -> {
                    target.setAlpha(0.88f);
                    target.setTranslationY(dpf(1));
                }
                case android.view.MotionEvent.ACTION_UP,
                     android.view.MotionEvent.ACTION_CANCEL -> {
                    target.setAlpha(1f);
                    target.setTranslationY(0f);
                }
                default -> {
                }
            }
            return false;
        });
        return view;
    }

    /** Outlined button: the border colour carries the meaning, the fill is a 13% wash of it. */
    public TextView outlinedButton(String label, int color, Runnable action) {
        TextView view = monoButton(label, color);
        view.setBackground(pressable(
                palette.fill(color, 0.05f, 0x00),
                Palette.withAlpha(color, 0x22),
                color,
                5
        ));
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setMinHeight(dp(44));
        clickable(view, action);
        return view;
    }

    /** Action chip above an answer: 20 dp capsule whose border lights up on press. */
    public TextView chip(String label, Runnable action) {
        TextView view = monoLabel(label, palette.textSecondary);
        view.setBackground(pressable(
                palette.background,
                palette.cardFillHover,
                palette.stroke,
                20
        ));
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        clickable(view, action);
        return view;
    }

    /** Inline text action such as «СТОП»: small type, but a 44 dp target. */
    public TextView inlineAction(String label, int color, Runnable action) {
        TextView view = monoLabel(label, color);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(11), dp(13), dp(11), dp(13));
        view.setMinHeight(dp(44));
        view.setMinWidth(dp(44));
        clickable(view, action);
        return view;
    }

    public void clickable(View view, Runnable action) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(ignored -> action.run());
    }

    /**
     * Honours the system animation scale: with animations off, infinite loops never start and
     * one-shot entrances jump to their end state.
     */
    public boolean animationsEnabled() {
        float scale = Settings.Global.getFloat(
                context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
        );
        return scale > 0f;
    }

    public int dp(float value) {
        return Math.round(value * density);
    }

    public float dpf(float value) {
        return value * density;
    }

    private static Typeface weighted(Typeface base, String family, int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(base, weight, false);
        return Typeface.create(family, Typeface.NORMAL);
    }
}
