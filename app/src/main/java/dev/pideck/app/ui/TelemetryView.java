package dev.pideck.app.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.pideck.app.core.UiLanguage;

/** Three compact graphs; sampling and background history belong to RuntimeTelemetry. */
@SuppressLint("ViewConstructor")
public final class TelemetryView extends LinearLayout {
    private static final double GIB = 1_073_741_824d;
    private final UiLanguage language;
    private final Metric speed;
    private final Metric memory;
    private final Metric temperature;

    public TelemetryView(Context context, DeckStyle style, Palette palette, UiLanguage language) {
        super(context);
        this.language = language;
        setOrientation(HORIZONTAL);
        setBaselineAligned(false);
        setPadding(style.dp(14), style.dp(5), style.dp(14), style.dp(5));
        setBackgroundColor(palette.panel);
        speed = new Metric(context, style, palette, language.pick("СКОРОСТЬ", "SPEED"), palette.accent);
        memory = new Metric(context, style, palette, language.pick("RAM ТЕЛЕФОНА", "PHONE RAM"), palette.accentAlt);
        temperature = new Metric(context, style, palette, language.pick("БАТАРЕЯ", "BATTERY"), palette.warn);
        for (Metric metric : new Metric[]{speed, memory, temperature}) {
            LayoutParams params = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = params.rightMargin = style.dp(2);
            addView(metric, params);
        }
    }

    public void clearHistory() {
        speed.graph.clear();
        memory.graph.clear();
        temperature.graph.clear();
    }

    public void addSample(long timeMs, double tokensPerSecond, boolean estimated,
                          long usedBytes, long totalBytes, double batteryCelsius) {
        boolean knownSpeed = Double.isFinite(tokensPerSecond) && tokensPerSecond >= 0;
        String speedText = knownSpeed
                ? (estimated && tokensPerSecond > 0 ? "≈" : "") + number(tokensPerSecond)
                    + language.pick(" ток/с", " tok/s") : "—";
        speed.update(timeMs, knownSpeed ? tokensPerSecond : Double.NaN, 0, 10,
                speedText, language.pick("Скорость генерации", "Generation speed") + ": " + speedText
                        + (estimated ? language.pick(". Оценка по тексту.", ". Estimated from text.")
                        : language.pick(". По данным движка.", ". Reported by the engine.")));
        boolean knownMemory = totalBytes > 0 && usedBytes >= 0 && usedBytes <= totalBytes;
        String memoryText = knownMemory ? number(usedBytes / GIB) + language.pick(" ГБ", " GiB") : "—";
        memory.update(timeMs, knownMemory ? usedBytes / GIB : Double.NaN, 0,
                knownMemory ? totalBytes / GIB : 1,
                memoryText, language.pick("Занятая RAM всего телефона: ", "Whole-phone used RAM: ")
                        + memoryText + (knownMemory ? language.pick(" из ", " of ")
                        + number(totalBytes / GIB) + language.pick(" ГБ", " GiB") : ""));
        boolean knownTemperature = Double.isFinite(batteryCelsius)
                && batteryCelsius >= -40 && batteryCelsius <= 100;
        String temperatureText = knownTemperature ? number(batteryCelsius) + " °C" : "—";
        temperature.update(timeMs, knownTemperature ? batteryCelsius : Double.NaN, 20, 50,
                temperatureText, language.pick("Температура батареи: ", "Battery temperature: ")
                        + temperatureText + language.pick(". Это не температура CPU.", ". This is not CPU temperature."));
    }

    private String number(double value) {
        return String.format(language.locale, "%.1f", value);
    }

    private static final class Metric extends LinearLayout {
        private final TextView value;
        private final Sparkline graph;

        Metric(Context context, DeckStyle style, Palette palette, String title, int color) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(style.dp(4), style.dp(2), style.dp(4), style.dp(2));
            setBackground(style.round(palette.fill(color, .055f, 0xff), 4));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            TextView label = style.monoAt(title, 8.5f, palette.muted, false);
            label.setSingleLine(true);
            label.setLetterSpacing(0f);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, new LayoutParams(-1, -2));
            value = style.monoAt("—", 11.5f, color, true);
            value.setMaxLines(1);
            value.setHorizontallyScrolling(false);
            value.setLetterSpacing(0f);
            value.setAutoSizeTextTypeUniformWithConfiguration(8,
                    Math.max(9, Math.round(11.5f * style.textScale())), 1,
                    TypedValue.COMPLEX_UNIT_SP);
            value.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(value, new LayoutParams(-1, style.dp(Math.round(17 * style.textScale()))));
            graph = new Sparkline(context, style, palette, color);
            graph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(graph, new LayoutParams(-1, style.dp(16)));
        }

        void update(long timeMs, double sample, double minimum, double maximum,
                    String text, String description) {
            value.setText(text);
            setContentDescription(description);
            setTooltipText(description);
            graph.add(timeMs, sample, minimum, maximum);
        }
    }

    private static final class Sparkline extends View {
        private static final int CAPACITY = 61;
        private static final long WINDOW_MS = 120_000L;
        private final long[] times = new long[CAPACITY];
        private final double[] values = new double[CAPACITY];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int color;
        private final int guideColor;
        private final float stroke;
        private int next;
        private int count;
        private long now;
        private double minimum;
        private double maximum = 1;

        Sparkline(Context context, DeckStyle style, Palette palette, int color) {
            super(context);
            this.color = color;
            guideColor = Palette.withAlpha(palette.muted, 0x20);
            stroke = style.dp(1);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void clear() {
            next = count = 0;
            invalidate();
        }

        void add(long timeMs, double sample, double minimum, double maximum) {
            now = timeMs;
            times[next] = timeMs;
            values[next] = sample;
            next = (next + 1) % CAPACITY;
            count = Math.min(CAPACITY, count + 1);
            this.minimum = minimum;
            this.maximum = maximum;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = Math.max(1, getWidth() - 2 * stroke);
            float height = Math.max(1, getHeight() - 2 * stroke);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke * .5f);
            paint.setColor(guideColor);
            for (int i = 0; i <= 4; i++) {
                float x = stroke + width * i / 4f;
                canvas.drawLine(x, stroke, x, stroke + height, paint);
            }
            for (int i = 0; i <= 2; i++) {
                float y = stroke + height * i / 2f;
                canvas.drawLine(stroke, y, stroke + width, y, paint);
            }
            double low = minimum, high = maximum;
            for (int i = 0; i < count; i++) {
                int index = (next - count + i + CAPACITY) % CAPACITY;
                if (now - times[index] > WINDOW_MS || !Double.isFinite(values[index])) continue;
                low = Math.min(low, values[index]);
                high = Math.max(high, values[index] * 1.05);
            }
            path.reset();
            boolean connected = false;
            long previous = 0;
            float lastX = 0, lastY = 0;
            boolean hasPoint = false;
            for (int i = 0; i < count; i++) {
                int index = (next - count + i + CAPACITY) % CAPACITY;
                long age = now - times[index];
                if (age < 0 || age > WINDOW_MS || !Double.isFinite(values[index])) {
                    connected = false;
                    continue;
                }
                float x = stroke + width * (1 - (float) age / WINDOW_MS);
                float y = stroke + height * (float) (1 - (values[index] - low) / Math.max(.01, high - low));
                if (connected && times[index] - previous <= 6_000L) path.lineTo(x, y);
                else path.moveTo(x, y);
                connected = true;
                previous = times[index];
                lastX = x;
                lastY = y;
                hasPoint = true;
            }
            paint.setStrokeWidth(stroke * 1.5f);
            paint.setColor(color);
            canvas.drawPath(path, paint);
            if (hasPoint && now - previous <= 6_000L) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(lastX, lastY, stroke * 1.5f, paint);
            }
        }
    }
}
