package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * СЕССИИ: what is on disk, and how to get back to it.
 *
 * <p>The list is filled from a Termux-side probe, so the view renders whatever descriptors the
 * activity managed to collect and says so plainly when it collected none.
 */
@SuppressLint("ViewConstructor")
public final class SessionsRootView extends ScrollView {
    /** One saved conversation. */
    public static final class SessionRow {
        public final String title;
        public final String meta;
        public final boolean current;
        /** Sessions older than a week are dimmed rather than hidden. */
        public final boolean stale;
        public final Runnable open;

        public SessionRow(
                String title, String meta, boolean current, boolean stale, Runnable open
        ) {
            this.title = title;
            this.meta = meta;
            this.current = current;
            this.stale = stale;
            this.open = open;
        }
    }

    /** A titled group of rows: «СЕГОДНЯ», «РАНЬШЕ». */
    public static final class Group {
        public final String label;
        public final List<SessionRow> rows = new ArrayList<>();

        public Group(String label) {
            this.label = label;
        }
    }

    public static final class State {
        public final List<Group> groups = new ArrayList<>();
        public String emptyNote = "";
        public String footer = "";
        public String archiveLabel = "";
        public Runnable onArchive;
        public Runnable onNewSession;
    }

    private final DeckStyle style;
    private final LinearLayout column;

    public SessionsRootView(Context context, DeckStyle style) {
        super(context);
        this.style = style;
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setFillViewport(true);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(style.dp(22), style.dp(18), style.dp(22), style.dp(22));
        addView(column, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public void render(State state) {
        column.removeAllViews();

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(style.screenTitle("Сессии"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));
        if (state.onNewSession != null) {
            header.addView(style.inlineAction(
                    "+ Новая", style.palette.accent, state.onNewSession
            ));
        }
        column.addView(header);

        for (Group group : state.groups) {
            TextView label = style.monoLabel(group.label, style.palette.muted);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            labelLp.topMargin = style.dp(22);
            labelLp.bottomMargin = style.dp(11);
            column.addView(label, labelLp);
            for (SessionRow row : group.rows) column.addView(sessionRow(row));
        }

        if (!state.emptyNote.isEmpty()) {
            TextView note = style.bodySecondary(state.emptyNote);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            noteLp.topMargin = style.dp(18);
            column.addView(note, noteLp);
        }

        View spacer = new View(getContext());
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        View divider = new View(getContext());
        divider.setBackgroundColor(style.palette.strokeFaint);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.dp(1)
        );
        dividerLp.topMargin = style.dp(18);
        column.addView(divider, dividerLp);

        LinearLayout footer = new LinearLayout(getContext());
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, style.dp(11), 0, 0);
        footer.addView(style.monoTrace(state.footer, style.palette.muted),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (state.onArchive != null) {
            footer.addView(style.inlineAction(
                    state.archiveLabel, style.palette.accent, state.onArchive
            ));
        }
        column.addView(footer);
    }

    private View sessionRow(SessionRow row) {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(style.dp(15), style.dp(14), style.dp(15), style.dp(14));
        view.setMinimumHeight(style.dp(48));
        view.setBackground(row.current
                ? style.outlined(style.palette.panel, style.palette.accent, 7)
                : style.pressable(
                        style.palette.cardFill, style.palette.cardFillHover, style.palette.stroke, 7
                ));
        if (row.stale) view.setAlpha(0.6f);
        if (row.open != null) style.clickable(view, row.open);

        LinearLayout text = new LinearLayout(getContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(style.rowTitle(row.title));
        TextView meta = style.monoTrace(row.meta, style.palette.muted);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        metaLp.topMargin = style.dp(4);
        text.addView(meta, metaLp);
        view.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        view.addView(row.current
                ? style.monoAt("Сейчас", 10f, style.palette.ok, true)
                : style.monoTrace("›", style.palette.muted));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = style.dp(8);
        view.setLayoutParams(lp);
        return view;
    }
}
