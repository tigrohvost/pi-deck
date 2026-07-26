package dev.pideck.app.ui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The answer to "is it still alive?".
 *
 * <p>Pi 0.1 delivers a turn's text only when the turn ends, so this row is the only thing running
 * between a prompt and its answer: a pulsing dot, the operation the deck last heard about, the
 * elapsed time, and the stop control — which lives here rather than in a menu, because that is
 * where a user looks for it when a command has gone on too long.
 */
@SuppressLint("ViewConstructor")
public final class ExecutionRowView extends LinearLayout {
    private final DeckStyle style;
    private final PulseDot dot;
    private final TextView operation;
    private final TextView elapsed;
    private ObjectAnimator pulse;
    private long startedAtUptimeMs;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            elapsed.setText(format(SystemClock.uptimeMillis() - startedAtUptimeMs));
            postDelayed(this, 1_000L);
        }
    };

    public ExecutionRowView(Context context, DeckStyle style, Runnable onStop) {
        super(context);
        this.style = style;
        setOrientation(VERTICAL);
        setBackground(style.round(style.palette.cardFill, 8));
        setPadding(style.dp(15), style.dp(13), style.dp(15), style.dp(13));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        dot = new PulseDot(context, style.palette.accent);
        LayoutParams dotLp = new LayoutParams(style.dp(7), style.dp(7));
        dotLp.rightMargin = style.dp(8);
        head.addView(dot, dotLp);

        operation = style.monoMeta("", style.palette.text);
        operation.setSingleLine(true);
        operation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(operation, new LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        elapsed = style.monoMeta("0:00", style.palette.muted);
        LayoutParams elapsedLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        elapsedLp.leftMargin = style.dp(8);
        head.addView(elapsed, elapsedLp);

        head.addView(style.inlineAction("Стоп", style.palette.errorText, onStop));
        addView(head, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView note = style.caption(
                "Можно свернуть — пришлю уведомление, когда закончит."
        );
        LayoutParams noteLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        noteLp.topMargin = style.dp(6);
        addView(note, noteLp);
        setVisibility(GONE);
    }

    /** Shows the row and restarts the clock; a second call with a new label keeps the clock. */
    public void start(String label) {
        if (!running) {
            running = true;
            startedAtUptimeMs = SystemClock.uptimeMillis();
            elapsed.setText(format(0));
            removeCallbacks(tick);
            postDelayed(tick, 1_000L);
            startPulse();
        }
        setOperation(label);
        setVisibility(VISIBLE);
    }

    /** Every JSONL event moves this line — that is what makes it worth trusting. */
    public void setOperation(String label) {
        operation.setText(label == null || label.isBlank() ? "Работаю" : label);
    }

    public void stop() {
        running = false;
        removeCallbacks(tick);
        stopPulse();
        setVisibility(GONE);
    }

    public boolean isRunning() {
        return running;
    }

    private void startPulse() {
        if (pulse != null || !style.animationsEnabled()) return;
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f);
        pulse.setDuration(1_200);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse == null) return;
        pulse.cancel();
        pulse = null;
        dot.setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        stopPulse();
        super.onDetachedFromWindow();
    }

    private static String format(long millis) {
        long seconds = Math.max(0L, millis / 1_000L);
        if (seconds < 3_600L) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(
                Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, (seconds % 3_600) / 60, seconds % 60
        );
    }

    /** The 7 dp dot that says work is in flight. */
    private static final class PulseDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PulseDot(Context context, int color) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, paint);
        }
    }
}
