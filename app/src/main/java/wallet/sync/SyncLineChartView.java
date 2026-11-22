package wallet.sync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.main.R;

/** Theme-aware live chart with readable value and time axes. */
public final class SyncLineChartView extends View {
    private static final int TICK_COUNT = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String title = "";
    private String unit = "";
    private String[] seriesNames = new String[0];
    private float[][] values = new float[0][];
    private long[] timestamps = new long[0];
    private int primaryColor;
    private int secondarySeriesColor;
    private int gridColor;
    private int labelColor;
    private float density;
    private float graphLeft;
    private float graphRight;
    private float graphTop;
    private float graphBottom;
    private float axisMin;
    private float axisMax;

    public SyncLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setChartData(String chartTitle, String[] names, float[][] chartValues,
                             String valueUnit, long[] sampleTimes) {
        title = chartTitle == null ? "" : chartTitle;
        unit = valueUnit == null ? "" : valueUnit;
        seriesNames = names == null ? new String[0] : names.clone();
        if (chartValues == null) {
            values = new float[0][];
        } else {
            values = new float[chartValues.length][];
            for (int i = 0; i < chartValues.length; i++) {
                values[i] = chartValues[i] == null ? new float[0] : chartValues[i].clone();
            }
        }
        timestamps = sampleTimes == null ? new long[0] : sampleTimes.clone();
        setContentDescription(buildAccessibilityDescription());
        invalidate();
    }

    private String buildAccessibilityDescription() {
        StringBuilder description = new StringBuilder(title).append(". ").append(unit);
        for (int i = 0; i < values.length; i++) {
            description.append(". ");
            if (i < seriesNames.length) description.append(seriesNames[i]).append(": ");
            float[] data = values[i];
            if (data.length > 0 && Float.isFinite(data[data.length - 1])) {
                description.append(formatAxisValue(data[data.length - 1]));
            } else {
                description.append(getResources().getString(R.string.sync_chart_no_sample));
            }
        }
        return description.toString();
    }

    private int resolveThemeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getContext().getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId, getContext().getTheme());
            } catch (Exception ignored) {
            }
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        return fallback;
    }

    private void refreshColors() {
        // Use only colors supplied by the active Android theme.
        // A zero fallback avoids introducing a fixed RGB color when an attribute is absent.
        primaryColor = resolveThemeColor(android.R.attr.colorControlActivated,
                resolveThemeColor(android.R.attr.colorAccent,
                        resolveThemeColor(android.R.attr.textColorPrimary, 0)));

        // Keep the same theme color for both series. Their line patterns and endpoint
        // markers (solid/circle versus dashed/square) provide the distinction.
        secondarySeriesColor = primaryColor;

        labelColor = resolveThemeColor(android.R.attr.textColorSecondary, primaryColor);
        gridColor = resolveThemeColor(android.R.attr.textColorSecondary, labelColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        refreshColors();
        float width = getWidth();
        float height = getHeight();
        if (width <= dp(100) || height <= dp(90)) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD));
        paint.setTextSize(sp(13));
        paint.setColor(resolveThemeColor(android.R.attr.textColorPrimary, labelColor));
        canvas.drawText(title, dp(2), dp(15), paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(sp(10));
        paint.setColor(labelColor);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(unit, width - dp(2), dp(15), paint);

        drawLegend(canvas);
        calculateRange();
        paint.setTextSize(sp(9));
        float widest = 0f;
        for (int i = 0; i <= TICK_COUNT; i++) {
            widest = Math.max(widest, paint.measureText(formatAxisValue(axisMax
                    - (axisMax - axisMin) * i / TICK_COUNT)));
        }
        graphLeft = dp(8) + widest + dp(8);
        graphRight = width - dp(8);
        graphTop = dp(47);
        graphBottom = height - dp(25);
        if (graphRight <= graphLeft || graphBottom <= graphTop) return;

        drawGrid(canvas);
        drawVerticalLabels(canvas);
        drawTimeLabels(canvas);
        drawSeries(canvas);
    }

    private void drawLegend(Canvas canvas) {
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(sp(9));
        paint.setTextAlign(Paint.Align.LEFT);
        float x = dp(2);
        float y = dp(32);
        for (int i = 0; i < seriesNames.length; i++) {
            String name = seriesNames[i] == null ? "" : seriesNames[i];
            paint.setColor(seriesColor(i));
            paint.setStrokeWidth(dp(2));
            paint.setPathEffect(i == 1 ? new DashPathEffect(
                    new float[]{dp(4), dp(2)}, 0) : null);
            canvas.drawLine(x, y - dp(3), x + dp(12), y - dp(3), paint);
            paint.setPathEffect(null);
            x += dp(17);
            paint.setColor(labelColor);
            canvas.drawText(name, x, y, paint);
            x += paint.measureText(name) + dp(12);
            if (x > getWidth() - dp(55)) break;
        }
    }

    private void calculateRange() {
        axisMin = Float.POSITIVE_INFINITY;
        axisMax = Float.NEGATIVE_INFINITY;
        for (float[] series : values) {
            for (float value : series) {
                if (!Float.isFinite(value)) continue;
                axisMin = Math.min(axisMin, value);
                axisMax = Math.max(axisMax, value);
            }
        }
        if (!Float.isFinite(axisMin) || !Float.isFinite(axisMax)) {
            axisMin = 0f;
            axisMax = 1f;
        }
        if (axisMin >= 0f) axisMin = 0f;
        if (axisMax == axisMin) axisMax = axisMin + Math.max(1f, Math.abs(axisMin) * 0.1f);
        else axisMax += (axisMax - axisMin) * 0.10f;
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        paint.setColor(gridColor);
        paint.setAlpha(56);
        for (int i = 0; i <= TICK_COUNT; i++) {
            float y = graphTop + (graphBottom - graphTop) * i / TICK_COUNT;
            float x = graphLeft + (graphRight - graphLeft) * i / TICK_COUNT;
            canvas.drawLine(graphLeft, y, graphRight, y, paint);
            canvas.drawLine(x, graphTop, x, graphBottom, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawVerticalLabels(Canvas canvas) {
        paint.setColor(labelColor);
        paint.setTextSize(sp(9));
        paint.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        for (int i = 0; i <= TICK_COUNT; i++) {
            float value = axisMax - (axisMax - axisMin) * i / TICK_COUNT;
            float y = graphTop + (graphBottom - graphTop) * i / TICK_COUNT;
            canvas.drawText(formatAxisValue(value), graphLeft - dp(5),
                    y - (metrics.ascent + metrics.descent) / 2f, paint);
        }
    }

    private void drawTimeLabels(Canvas canvas) {
        paint.setColor(labelColor);
        paint.setTextSize(sp(9));
        for (int i = 0; i <= TICK_COUNT; i++) {
            int sampleIndex = timestamps.length <= 1 ? 0
                    : Math.round((timestamps.length - 1) * i / (float) TICK_COUNT);
            String label;
            if (timestamps.length > 0 && sampleIndex < timestamps.length) {
                label = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(timestamps[sampleIndex]));
            } else {
                label = getResources().getString(i == TICK_COUNT
                        ? R.string.sync_chart_now : R.string.sync_chart_seconds_ago,
                        Math.max(0, TICK_COUNT - i) * 15);
            }
            float x = graphLeft + (graphRight - graphLeft) * i / TICK_COUNT;
            if (i == 0) paint.setTextAlign(Paint.Align.LEFT);
            else if (i == TICK_COUNT) paint.setTextAlign(Paint.Align.RIGHT);
            else paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, x, getHeight() - dp(5), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSeries(Canvas canvas) {
        int count = 0;
        for (float[] series : values) count = Math.max(count, series.length);
        if (count < 2) return;
        for (int s = 0; s < values.length; s++) {
            float[] series = values[s];
            Path path = new Path();
            boolean started = false;
            for (int i = 0; i < series.length; i++) {
                float sample = series[i];
                if (!Float.isFinite(sample)) { started = false; continue; }
                float x = graphLeft + (graphRight - graphLeft) * i / Math.max(1f, count - 1f);
                float y = graphBottom - (sample - axisMin) / (axisMax - axisMin)
                        * (graphBottom - graphTop);
                if (!started) { path.moveTo(x, y); started = true; }
                else path.lineTo(x, y);
            }
            paint.setColor(seriesColor(s));
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setPathEffect(s == 1 ? new DashPathEffect(
                    new float[]{dp(6), dp(3)}, 0) : null);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint);
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            if (series.length > 0 && Float.isFinite(series[series.length - 1])) {
                float x = graphLeft + (graphRight - graphLeft)
                        * (series.length - 1) / Math.max(1f, count - 1f);
                float y = graphBottom - (series[series.length - 1] - axisMin)
                        / (axisMax - axisMin) * (graphBottom - graphTop);
                if (s == 1) {
                    float radius = dp(3.2f);
                    canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint);
                } else {
                    canvas.drawCircle(x, y, dp(2.8f), paint);
                }
            }
        }
    }

    private int seriesColor(int index) {
        return index == 0 ? primaryColor : secondarySeriesColor;
    }

    private String formatAxisValue(float value) {
        float absolute = Math.abs(value);
        if (absolute >= 1_000_000f) return String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f);
        if (absolute >= 10_000f) return String.format(Locale.getDefault(), "%.0fk", value / 1_000f);
        if (absolute >= 1_000f) return String.format(Locale.getDefault(), "%.1fk", value / 1_000f);
        if (Math.abs(value - Math.round(value)) < 0.05f) return String.format(Locale.getDefault(), "%.0f", value);
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
