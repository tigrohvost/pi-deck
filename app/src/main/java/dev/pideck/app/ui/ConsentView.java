package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The one screen where a person knowingly hands the agent the phone's shell.
 *
 * <p>It is shown once, between setting Termux up and the first run, and it is deliberately
 * symmetrical: what the agent can do and what it cannot, in the same voice and the same weight,
 * so the limits are as legible as the powers.
 */
@SuppressLint("ViewConstructor")
public final class ConsentView extends ScrollView {
    public interface Listener {
        void onConsentGranted(boolean askBeforeOverwrite);
    }

    private static final String[] CAN = {
            "читать, создавать и менять файлы в своей папке",
            "запускать bash, git, python внутри Termux",
            "ходить в сеть через curl, когда вы попросили",
    };

    private static final String[] CANNOT = {
            "получить root",
            "видеть данные других приложений",
            "отправить ваш промпт в интернет",
    };

    private final DeckStyle style;
    private final CheckBoxView checkBox;

    public ConsentView(Context context, DeckStyle style, String workspacePath, Listener listener) {
        super(context);
        this.style = style;
        setBackgroundColor(style.palette.background);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(style.dp(24), style.dp(24), style.dp(24), style.dp(24));
        addView(column, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        column.addView(style.monoLabel("Шаг 2 из 2 · доступ", style.palette.muted));

        TextView title = style.screenTitle("Что сможет агент на этом телефоне");
        LinearLayout.LayoutParams titleLp = matchWidth();
        titleLp.topMargin = style.dp(14);
        column.addView(title, titleLp);

        column.addView(capabilityBlock("Может", style.palette.ok, "+", CAN), blockLp());
        column.addView(capabilityBlock("Не может", style.palette.error, "−", CANNOT), blockLp());

        LinearLayout sandbox = new LinearLayout(context);
        sandbox.setOrientation(LinearLayout.VERTICAL);
        sandbox.setBackground(style.round(style.palette.panel, 6));
        sandbox.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14));
        sandbox.addView(style.caption("Песочница"));
        TextView path = style.monoMeta(workspacePath, style.palette.accent);
        LinearLayout.LayoutParams pathLp = matchWidth();
        pathLp.topMargin = style.dp(4);
        sandbox.addView(path, pathLp);
        column.addView(sandbox, blockLp());

        LinearLayout consentRow = new LinearLayout(context);
        consentRow.setOrientation(LinearLayout.HORIZONTAL);
        consentRow.setGravity(Gravity.CENTER_VERTICAL);
        consentRow.setBackground(style.outlined(style.palette.background, style.palette.stroke, 6));
        consentRow.setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14));
        consentRow.setMinimumHeight(style.dp(48));
        checkBox = new CheckBoxView(context, style);
        consentRow.addView(checkBox, new LinearLayout.LayoutParams(style.dp(19), style.dp(19)));
        TextView consentText = style.bodySecondary(
                "Спрашивать перед изменением файлов, которые агент не создавал сам"
        );
        LinearLayout.LayoutParams consentTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        consentTextLp.leftMargin = style.dp(14);
        consentRow.addView(consentText, consentTextLp);
        style.clickable(consentRow, checkBox::toggle);
        column.addView(consentRow, blockLp());

        View spacer = new View(context);
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        TextView enable = style.primaryButton(
                "Включить деку", () -> listener.onConsentGranted(checkBox.isChecked())
        );
        LinearLayout.LayoutParams enableLp = matchWidth();
        enableLp.topMargin = style.dp(22);
        column.addView(enable, enableLp);
    }

    private View capabilityBlock(String label, int color, String glyph, String[] lines) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(style.monoLabel(label, color));
        for (String line : lines) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView bullet = style.monoTrace(glyph, color);
            row.addView(bullet, new LinearLayout.LayoutParams(
                    style.dp(12), ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            row.addView(style.bodySecondary(line), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ));
            LinearLayout.LayoutParams rowLp = matchWidth();
            rowLp.topMargin = style.dp(9);
            block.addView(row, rowLp);
        }
        return block;
    }

    private LinearLayout.LayoutParams blockLp() {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.topMargin = style.dp(22);
        return lp;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    /** A 19 dp box that fills with the accent and carries a check cut in the background colour. */
    private static final class CheckBoxView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final DeckStyle style;
        private boolean checked = true;

        CheckBoxView(Context context, DeckStyle style) {
            super(context);
            this.style = style;
        }

        void toggle() {
            checked = !checked;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = style.dpf(4);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? style.palette.accent : style.palette.background);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            if (!checked) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(style.dpf(1));
                paint.setColor(style.palette.stroke);
                float inset = style.dpf(0.5f);
                canvas.drawRoundRect(
                        inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, paint
                );
                return;
            }
            check.reset();
            check.moveTo(getWidth() * 0.24f, getHeight() * 0.52f);
            check.lineTo(getWidth() * 0.43f, getHeight() * 0.71f);
            check.lineTo(getWidth() * 0.77f, getHeight() * 0.31f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(style.dpf(2));
            paint.setColor(style.palette.background);
            canvas.drawPath(check, paint);
        }
    }
}
