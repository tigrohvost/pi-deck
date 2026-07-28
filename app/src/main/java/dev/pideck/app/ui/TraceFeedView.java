package dev.pideck.app.ui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.pideck.app.core.UiLanguage;

/**
 * The live tool trace: what the agent is touching, one line per call.
 *
 * <p>A long run collapses to its first three calls and its last one, because those are the two
 * things worth reading at a glance — where it started and where it is now. Everything between
 * stays one tap away rather than being dropped.
 */
@SuppressLint("ViewConstructor")
public final class TraceFeedView extends LinearLayout {
    /** Verbs that create or change something, and so are drawn in the success colour. */
    private static final List<String> WRITING_VERBS = List.of("write", "create", "edit", "delete");

    private static final int ALWAYS_LEADING = 3;

    private final DeckStyle style;
    private final LinearLayout rows;
    private final TextView expander;
    private final List<View> rowViews = new ArrayList<>();
    private ObjectAnimator expanderPulse;
    private boolean expanded;
    private final UiLanguage language;

    public TraceFeedView(Context context, DeckStyle style, UiLanguage language) {
        super(context);
        this.style = style;
        this.language = language == null ? UiLanguage.RUSSIAN : language;
        setOrientation(HORIZONTAL);

        View rule = new View(context);
        rule.setBackgroundColor(style.palette.stroke);
        addView(rule, new LayoutParams(style.dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        LayoutParams rowsLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rowsLp.leftMargin = style.dp(14);
        addView(rows, rowsLp);

        expander = style.monoTrace("", style.palette.traceIdle);
        expander.setPadding(0, style.dp(9), 0, style.dp(9));
        expander.setVisibility(GONE);
        style.clickable(expander, () -> {
            expanded = !expanded;
            applyCollapse();
        });
    }

    /** Appends one call and re-applies the collapse rule. */
    public void add(String verb, String argument, String detail) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, style.dp(4), 0, style.dp(5));

        TextView verbView = style.monoTrace(
                verb, isWriting(verb) ? style.palette.ok : style.palette.accentAlt
        );
        row.addView(verbView, new LayoutParams(
                style.dp(40 * style.textScale()), ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView argumentView = style.monoTrace(argument, style.palette.muted);
        argumentView.setSingleLine(true);
        argumentView.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams argumentLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        argumentLp.leftMargin = style.dp(6);
        row.addView(argumentView, argumentLp);

        if (detail != null && !detail.isEmpty()) {
            TextView detailView = style.monoTrace(detail, style.palette.traceIdle);
            detailView.setGravity(Gravity.END);
            LayoutParams detailLp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            detailLp.leftMargin = style.dp(8);
            row.addView(detailView, detailLp);
        }

        rows.removeView(expander);
        rows.addView(row);
        rowViews.add(row);
        applyCollapse();
    }

    /** Fills in the result column of the call that is still open. */
    public void completeLast(String detail) {
        if (rowViews.isEmpty() || detail == null || detail.isEmpty()) return;
        LinearLayout row = (LinearLayout) rowViews.get(rowViews.size() - 1);
        TextView detailView = style.monoTrace(detail, style.palette.traceIdle);
        detailView.setGravity(Gravity.END);
        if (row.getChildCount() > 2) row.removeViewAt(2);
        LayoutParams detailLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailLp.leftMargin = style.dp(8);
        row.addView(detailView, detailLp);
    }

    public int size() {
        return rowViews.size();
    }

    private void applyCollapse() {
        int hidden = Math.max(0, rowViews.size() - ALWAYS_LEADING - 1);
        if (hidden == 0 || expanded) {
            for (View row : rowViews) row.setVisibility(VISIBLE);
            rows.removeView(expander);
            stopExpanderPulse();
            if (hidden > 0) {
                // Expanded: keep the affordance so the list can be folded back up.
                showExpander(hidden, true);
            }
            return;
        }
        for (int index = 0; index < rowViews.size(); index++) {
            boolean visible = index < ALWAYS_LEADING || index == rowViews.size() - 1;
            rowViews.get(index).setVisibility(visible ? VISIBLE : GONE);
        }
        showExpander(hidden, false);
    }

    private void showExpander(int hidden, boolean open) {
        expander.setText(open
                ? language.pick("⋯ свернуть", "⋯ collapse")
                : String.format(
                        language.locale,
                        language.pick("⋯ ещё %d · развернуть", "⋯ %d more · expand"),
                        hidden
                ));
        expander.setVisibility(VISIBLE);
        rows.removeView(expander);
        // The last call always stays in view, so the affordance sits above it.
        int index = open ? rows.getChildCount() : Math.min(ALWAYS_LEADING, rows.getChildCount());
        rows.addView(expander, index);
        if (open) {
            stopExpanderPulse();
        } else {
            startExpanderPulse();
        }
    }

    private void startExpanderPulse() {
        if (expanderPulse != null || !style.animationsEnabled()) return;
        expanderPulse = ObjectAnimator.ofFloat(expander, View.ALPHA, 0.2f, 1f);
        expanderPulse.setDuration(1_800);
        expanderPulse.setRepeatMode(ObjectAnimator.REVERSE);
        expanderPulse.setRepeatCount(ObjectAnimator.INFINITE);
        expanderPulse.start();
    }

    private void stopExpanderPulse() {
        if (expanderPulse == null) return;
        expanderPulse.cancel();
        expanderPulse = null;
        expander.setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopExpanderPulse();
        super.onDetachedFromWindow();
    }

    private static boolean isWriting(String verb) {
        return WRITING_VERBS.contains(verb.toLowerCase(Locale.ROOT));
    }
}
