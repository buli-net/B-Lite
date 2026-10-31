package wallet.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import wallet.main.R;

/** Theme-aware charts for the local expense summary. */
public final class ExpenseAnalyticsChartView extends View {
    public static final int MODE_DAILY = 1;
    public static final int MODE_MONTHLY = 2;
    public static final int MODE_CUMULATIVE = 3;
    public static final int MODE_CATEGORY = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> labels = new ArrayList<>();
    private final List<BigDecimal> primary = new ArrayList<>();
    private final List<BigDecimal> secondary = new ArrayList<>();
    private final List<String> itemLabels = new ArrayList<>();
    private int mode = MODE_DAILY;
    private String primaryLabel;
    private String secondaryLabel;
    private String centerLabel;
    private String currencyCode = "USD";
    private Locale locale = Locale.getDefault();
    private int textPrimary;
    private int textSecondary;
    private int accent;
    private int control;
    private int[] palette;

    public ExpenseAnalyticsChartView(Context context) { super(context); init(); }
    public ExpenseAnalyticsChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ExpenseAnalyticsChartView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setWillNotDraw(false);
        refreshThemeColors();
    }

    private int resolveColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getContext().getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try { return androidx.core.content.ContextCompat.getColor(getContext(), value.resourceId); }
            catch (RuntimeException ignored) { }
        }
        return value.data;
    }

    private void refreshThemeColors() {
        textPrimary = resolveColor(android.R.attr.textColorPrimary,
                resolveColor(android.R.attr.colorForeground, Color.TRANSPARENT));
        textSecondary = resolveColor(android.R.attr.textColorSecondary, textPrimary);
        control = resolveColor(android.R.attr.colorControlActivated,
                resolveColor(android.R.attr.colorAccent, textPrimary));
        accent = resolveColor(android.R.attr.colorAccent, control);
        palette = new int[]{accent, textPrimary, control, textSecondary,
                blend(accent, textPrimary, 0.28f), blend(control, textSecondary, 0.32f),
                blend(accent, control, 0.50f), blend(accent, textSecondary, 0.55f),
                blend(control, textPrimary, 0.55f)};
    }

    private int blend(int from, int to, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(from) * inverse + Color.red(to) * amount),
                Math.round(Color.green(from) * inverse + Color.green(to) * amount),
                Math.round(Color.blue(from) * inverse + Color.blue(to) * amount));
    }

    public void setChartData(int chartMode, List<String> xLabels,
                             List<BigDecimal> firstSeries, List<BigDecimal> secondSeries,
                             List<String> segmentLabels, String firstLabel, String secondLabel,
                             String center, String code, Locale activeLocale) {
        mode = chartMode;
        labels.clear(); if (xLabels != null) labels.addAll(xLabels);
        primary.clear(); if (firstSeries != null) primary.addAll(firstSeries);
        secondary.clear(); if (secondSeries != null) secondary.addAll(secondSeries);
        itemLabels.clear(); if (segmentLabels != null) itemLabels.addAll(segmentLabels);
        primaryLabel = firstLabel;
        secondaryLabel = secondLabel;
        centerLabel = center;
        currencyCode = code == null ? "USD" : code;
        locale = activeLocale == null ? Locale.getDefault() : activeLocale;
        refreshThemeColors();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mode == MODE_CATEGORY) drawCategoryChart(canvas);
        else drawPlotChart(canvas);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    private void configureText(float size, int color, Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTextAlign(align);
        paint.setStrokeWidth(dp(1));
    }

    private void drawPlotChart(Canvas canvas) {
        int count = Math.min(labels.size(), primary.size());
        if (count == 0) return;
        float left = dp(46);
        float right = getWidth() - dp(10);
        float top = dp(34);
        float bottom = getHeight() - dp(30);
        if (right <= left || bottom <= top) return;

        drawLegend(canvas, left, dp(12));
        double dataMin = 0d;
        double dataMax = 0d;
        for (BigDecimal value : primary) {
            dataMin = Math.min(dataMin, value.doubleValue());
            dataMax = Math.max(dataMax, value.doubleValue());
        }
        if (mode == MODE_DAILY || mode == MODE_MONTHLY) {
            for (BigDecimal value : secondary) dataMax = Math.max(dataMax, value.doubleValue());
        }
        double range = dataMax - dataMin;
        if (range <= 0d) range = Math.max(1d, Math.abs(dataMax));
        double padding = mode == MODE_CUMULATIVE ? range * 0.10d : dataMax * 0.12d;
        double axisMin = mode == MODE_CUMULATIVE ? dataMin - padding : 0d;
        double axisMax = Math.max(1d, dataMax + padding);
        double axisRange = Math.max(1d, axisMax - axisMin);

        for (int i = 0; i <= 4; i++) {
            float y = bottom - (bottom - top) * i / 4f;
            paint.setColor(withAlpha(textSecondary, 68));
            paint.setStrokeWidth(dp(0.7f));
            canvas.drawLine(left, y, right, y, paint);
            configureText(9, textSecondary, Paint.Align.RIGHT);
            double axisValue = axisMin + axisRange * i / 4d;
            canvas.drawText(formatAxis(axisValue), left - dp(6), y + dp(3), paint);
        }
        if (mode == MODE_CUMULATIVE && axisMin < 0d && axisMax > 0d) {
            float zeroY = bottom - (float) ((0d - axisMin) / axisRange) * (bottom - top);
            paint.setColor(withAlpha(textPrimary, 125));
            paint.setStrokeWidth(dp(1.2f));
            canvas.drawLine(left, zeroY, right, zeroY, paint);
        }

        if (mode == MODE_MONTHLY) {
            drawGroupedBars(canvas, left, right, top, bottom, count, axisMax);
        } else {
            drawLineSeries(canvas, left, right, top, bottom, count, axisMin, axisRange, primary, palette[0], dp(2.4f));
            if (mode == MODE_DAILY && !secondary.isEmpty()) {
                drawLineSeries(canvas, left, right, top, bottom, Math.min(count, secondary.size()), axisMin, axisRange, secondary, palette[1], dp(2.1f));
            }
        }
        drawXAxisLabels(canvas, left, right, bottom, count);
    }

    private void drawLegend(Canvas canvas, float left, float baseline) {
        if (mode == MODE_CUMULATIVE) {
            drawLegendItem(canvas, left, baseline, palette[0], centerLabel == null ? primaryLabel : centerLabel);
            return;
        }
        if (primaryLabel != null) drawLegendItem(canvas, left, baseline, palette[0], primaryLabel);
        if (secondaryLabel != null) {
            float x = left + dp(130);
            drawLegendItem(canvas, x, baseline, palette[1], secondaryLabel);
        }
    }

    private void drawLegendItem(Canvas canvas, float x, float baseline, int color, String label) {
        if (label == null) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        canvas.drawLine(x, baseline - dp(3), x + dp(15), baseline - dp(3), paint);
        configureText(10, textPrimary, Paint.Align.LEFT);
        canvas.drawText(label, x + dp(21), baseline, paint);
    }

    private void drawLineSeries(Canvas canvas, float left, float right, float top, float bottom,
                                int count, double min, double range, List<BigDecimal> values, int color, float stroke) {
        if (count <= 0) return;
        Path path = new Path();
        float step = count == 1 ? 0f : (right - left) / (count - 1f);
        for (int i = 0; i < count; i++) {
            double value = values.get(i).doubleValue();
            float x = left + step * i;
            float y = bottom - (float) ((value - min) / range) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        int markerStep = count > 14 ? Math.max(1, count / 8) : 1;
        for (int i = 0; i < count; i += markerStep) {
            double value = values.get(i).doubleValue();
            float x = left + step * i;
            float y = bottom - (float) ((value - min) / range) * (bottom - top);
            paint.setColor(color);
            canvas.drawCircle(x, y, dp(2.5f), paint);
        }
    }

    private void drawGroupedBars(Canvas canvas, float left, float right, float top, float bottom,
                                 int count, double max) {
        float groupWidth = (right - left) / Math.max(1, count);
        float barWidth = Math.min(dp(13), groupWidth * 0.28f);
        for (int i = 0; i < count; i++) {
            float center = left + groupWidth * (i + 0.5f);
            double income = Math.max(0d, primary.get(i).doubleValue());
            double expense = i < secondary.size() ? Math.max(0d, secondary.get(i).doubleValue()) : 0d;
            float incomeHeight = (float) (income / max) * (bottom - top);
            float expenseHeight = (float) (expense / max) * (bottom - top);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette[0]);
            canvas.drawRoundRect(center - barWidth - dp(1), bottom - incomeHeight,
                    center - dp(1), bottom, dp(2), dp(2), paint);
            paint.setColor(palette[1]);
            canvas.drawRoundRect(center + dp(1), bottom - expenseHeight,
                    center + barWidth + dp(1), bottom, dp(2), dp(2), paint);
        }
    }

    private void drawXAxisLabels(Canvas canvas, float left, float right, float bottom, int count) {
        configureText(9, textSecondary, Paint.Align.CENTER);
        int step = Math.max(1, (int) Math.ceil(count / 6f));
        for (int i = 0; i < count; i++) {
            if (i != 0 && i != count - 1 && i % step != 0) continue;
            float x = count == 1 ? (left + right) / 2f : left + (right - left) * i / (count - 1f);
            String label = labels.get(i);
            if (mode == MODE_MONTHLY) {
                float groupWidth = (right - left) / Math.max(1, count);
                x = left + groupWidth * (i + 0.5f);
            }
            canvas.drawText(label, x, bottom + dp(18), paint);
        }
    }

    private void drawCategoryChart(Canvas canvas) {
        double total = 0d;
        for (BigDecimal value : primary) total += Math.max(0d, value.doubleValue());
        float centerX = getWidth() * 0.30f;
        float centerY = getHeight() * 0.49f;
        float radius = Math.min(dp(67), getHeight() * 0.34f);
        RectF bounds = new RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        float start = -90f;
        if (total > 0d) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(22));
            paint.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < primary.size(); i++) {
                float sweep = (float) (Math.max(0d, primary.get(i).doubleValue()) / total * 360d);
                paint.setColor(palette[i % palette.length]);
                canvas.drawArc(bounds, start, sweep, false, paint);
                start += sweep;
            }
        }
        configureText(9, textSecondary, Paint.Align.CENTER);
        canvas.drawText(centerLabel == null ? "" : centerLabel, centerX, centerY - dp(5), paint);
        configureText(12, textPrimary, Paint.Align.CENTER);
        canvas.drawText(formatCompactCurrency(total), centerX, centerY + dp(13), paint);

        float legendX = getWidth() * 0.57f;
        float rowHeight = Math.min(dp(21), (getHeight() - dp(12)) / Math.max(1, itemLabels.size()));
        float y = (getHeight() - rowHeight * itemLabels.size()) / 2f + rowHeight * 0.68f;
        float maxTextWidth = getWidth() - legendX - dp(8);
        for (int i = 0; i < itemLabels.size(); i++) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(palette[i % palette.length]);
            canvas.drawCircle(legendX, y - dp(3), dp(4), paint);
            String name = itemLabels.get(i);
            String amount = formatAxis(primary.get(i).doubleValue());
            configureText(9, textPrimary, Paint.Align.LEFT);
            String text = ellipsize(name, Math.max(dp(20), maxTextWidth - dp(40)));
            canvas.drawText(text, legendX + dp(10), y, paint);
            configureText(9, textSecondary, Paint.Align.RIGHT);
            canvas.drawText(amount, getWidth() - dp(7), y, paint);
            y += rowHeight;
        }
    }

    private String ellipsize(String value, float maxWidth) {
        if (value == null) return "";
        configureText(9, textPrimary, Paint.Align.LEFT);
        if (paint.measureText(value) <= maxWidth) return value;
        String result = value;
        while (result.length() > 1 && paint.measureText(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    private String formatAxis(double value) {
        double magnitude = Math.abs(value);
        double divisor = magnitude >= 1000000000d ? 1000000000d
                : magnitude >= 1000000d ? 1000000d : magnitude >= 10000d ? 1000d : 1d;
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(divisor == 1d ? 0 : 1);
        String suffix = divisor == 1000000000d ? getResources().getString(R.string.expense_chart_suffix_billion)
                : divisor == 1000000d ? getResources().getString(R.string.expense_chart_suffix_million)
                : divisor == 1000d ? getResources().getString(R.string.expense_chart_suffix_thousand) : "";
        return format.format(value / divisor) + suffix;
    }

    private String formatCompactCurrency(double value) {
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(locale);
            Currency currency = Currency.getInstance(currencyCode);
            format.setCurrency(currency);
            int digits = Math.max(0, currency.getDefaultFractionDigits());
            format.setMinimumFractionDigits(digits);
            format.setMaximumFractionDigits(digits);
            return format.format(value);
        } catch (RuntimeException ignored) {
            return formatAxis(value) + " " + currencyCode;
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
