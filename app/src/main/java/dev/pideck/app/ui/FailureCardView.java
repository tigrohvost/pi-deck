package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Every failure the deck can hit, in one shape.
 *
 * <p>The order is fixed because it is the order a stuck user needs: what class of thing broke,
 * what happened in plain words, what it cost, what the deck already saved, and the one action
 * that continues. No error code in the headline and no blame — a corrupt download and a killed
 * Termux are the same kind of event to the person holding the phone.
 */
@SuppressLint("ViewConstructor")
public final class FailureCardView extends LinearLayout {
    /** One failure, ready to render. */
    public static final class Failure {
        public final String category;
        public final String title;
        public final String cause;
        /** What survived — listed so the user does not have to guess. */
        public final List<String> recovered = new ArrayList<>();
        /** Blocking failures take the error stripe; choices take the warn stripe. */
        public final boolean blocking;
        public String primaryLabel = "";
        public Runnable primary;
        public String secondaryLabel = "";
        public Runnable secondary;

        public Failure(String category, String title, String cause, boolean blocking) {
            this.category = category;
            this.title = title;
            this.cause = cause;
            this.blocking = blocking;
        }

        public Failure recovered(String... items) {
            for (String item : items) recovered.add(item);
            return this;
        }

        public Failure primary(String label, Runnable action) {
            this.primaryLabel = label;
            this.primary = action;
            return this;
        }

        public Failure secondary(String label, Runnable action) {
            this.secondaryLabel = label;
            this.secondary = action;
            return this;
        }

        /** Flattened for the stored transcript, where the buttons cannot survive. */
        public String transcriptText() {
            StringBuilder text = new StringBuilder(category).append(". ").append(title);
            if (!cause.isEmpty()) text.append('\n').append(cause);
            for (String item : recovered) text.append("\n· ").append(item);
            return text.toString();
        }
    }

    public FailureCardView(Context context, DeckStyle style, Failure failure) {
        super(context);
        Palette palette = style.palette;
        int accentColor = failure.blocking ? palette.error : palette.warn;
        setOrientation(HORIZONTAL);

        View stripe = new View(context);
        stripe.setBackgroundColor(accentColor);
        addView(stripe, new LayoutParams(style.dp(3), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setBackground(style.stripedCard(palette.panel, 8));
        body.setPadding(style.dp(18), style.dp(18), style.dp(18), style.dp(18));
        addView(body, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        body.addView(style.monoLabel(
                failure.category, failure.blocking ? palette.errorText : palette.warn
        ));

        TextView title = style.cardTitle(failure.title);
        LinearLayout.LayoutParams titleLp = matchWidth();
        titleLp.topMargin = style.dp(14);
        body.addView(title, titleLp);

        if (!failure.cause.isEmpty()) {
            TextView cause = style.bodySecondary(failure.cause);
            LinearLayout.LayoutParams causeLp = matchWidth();
            causeLp.topMargin = style.dp(11);
            body.addView(cause, causeLp);
        }

        if (!failure.recovered.isEmpty()) {
            LinearLayout saved = new LinearLayout(context);
            saved.setOrientation(VERTICAL);
            saved.setBackground(style.round(palette.background, 6));
            saved.setPadding(style.dp(13), style.dp(13), style.dp(13), style.dp(13));
            for (int index = 0; index < failure.recovered.size(); index++) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.addView(style.monoTrace("✓", palette.ok), new LayoutParams(
                        style.dp(16), ViewGroup.LayoutParams.WRAP_CONTENT
                ));
                row.addView(style.caption(failure.recovered.get(index)), new LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ));
                LinearLayout.LayoutParams rowLp = matchWidth();
                if (index > 0) rowLp.topMargin = style.dp(6);
                saved.addView(row, rowLp);
            }
            LinearLayout.LayoutParams savedLp = matchWidth();
            savedLp.topMargin = style.dp(14);
            body.addView(saved, savedLp);
        }

        if (failure.primary != null) {
            TextView primary = style.primaryButton(failure.primaryLabel, failure.primary);
            LinearLayout.LayoutParams primaryLp = matchWidth();
            primaryLp.topMargin = style.dp(14);
            body.addView(primary, primaryLp);
        }

        if (failure.secondary != null) {
            TextView secondary = style.monoLabel(failure.secondaryLabel, palette.muted);
            secondary.setGravity(Gravity.CENTER);
            secondary.setPadding(style.dp(11), style.dp(14), style.dp(11), style.dp(4));
            secondary.setMinHeight(style.dp(44));
            style.clickable(secondary, failure.secondary);
            body.addView(secondary, matchWidth());
        }
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
