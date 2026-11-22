package wallet.sync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.main.R;

/** Theme-aware live chart with readable axes, touch inspection and current-value labels. */
public final class SyncLineChartView extends View {
    private static final int TICK_COUNT = 4;
    private static final long SELECTION_DISPLAY_DURATION_MS = 3_000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF overlayRect = new RectF();
    private final Runnable hideSelectionRunnable = new Runnable() {
        @Override
        public void run() {
            clearSelection();
            invalidate();
        }
    };

    private String title = "";
    private String unit = "";
    private String[] seriesNames = new String[0];
    private float[][] values = new float[0][];
    private long[] timestamps = new long[0];
    private int primaryColor;
    private int secondarySeriesColor;
    private int gridColor;
    private int labelColor;
    private int textPrimaryColor;
    private int backgroundColor;
    private float density;
    private float graphLeft;
    private float graphRight;
    private float graphTop;
    private float graphBottom;
    private float axisMin;
    private float axisMax;
    private float endpointLabelAreaWidth;
    private long chartStartMillis;
    private long chartEndMillis;
    private int selectedIndex = -1;
    private long selectedTimeMillis = -1L;
    private float selectedX = -1f;

    public SyncLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
    }

    public void setChartData(String chartTitle, String[] names, float[][] chartValues,
                             String valueUnit, long[] sampleTimes) {
        long start = sampleTimes != null && sampleTimes.length > 0 ? sampleTimes[0] : 0L;
        long end = sampleTimes != null && sampleTimes.length > 0
                ? sampleTimes[sampleTimes.length - 1] : 0L;
        setChartData(chartTitle, names, chartValues, valueUnit, sampleTimes, start, end);
    }

    public void setChartData(String chartTitle, String[] names, float[][] chartValues,
                             String valueUnit, long[] sampleTimes,
                             long rangeStartMillis, long rangeEndMillis) {
        boolean preserveSelection = selectedIndex >= 0 && selectedTimeMillis > 0L;
        long previousSelectedTime = selectedTimeMillis;

        chartStartMillis = rangeStartMillis;
        chartEndMillis = rangeEndMillis;
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

        if (preserveSelection && timestamps.length > 0 && hasAnyFiniteValue()) {
            selectedIndex = findNearestSampleIndex(previousSelectedTime);
            if (selectedIndex >= 0) {
                selectedTimeMillis = timestamps[selectedIndex];
            } else {
                removeCallbacks(hideSelectionRunnable);
                clearSelection();
            }
        } else {
            removeCallbacks(hideSelectionRunnable);
            clearSelection();
        }
        setContentDescription(buildAccessibilityDescription());
        invalidate();
    }

    private boolean hasAnyFiniteValue() {
        for (float[] series : values) {
            for (float value : series) {
                if (Float.isFinite(value)) return true;
            }
        }
        return false;
    }

    private String buildAccessibilityDescription() {
        StringBuilder description = new StringBuilder(title).append(". ").append(unit);
        for (int i = 0; i < values.length; i++) {
            int latest = latestFiniteIndex(values[i]);
            description.append(". ");
            if (i < seriesNames.length) description.append(seriesNames[i]).append(": ");
            if (latest >= 0) {
                description.append(formatCurrentValue(values[i][latest]));
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
        textPrimaryColor = resolveThemeColor(android.R.attr.textColorPrimary, labelColor);
        backgroundColor = resolveThemeColor(android.R.attr.colorBackground, textPrimaryColor);
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
        paint.setColor(textPrimaryColor);
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
        endpointLabelAreaWidth = calculateEndpointLabelAreaWidth(width);
        graphLeft = dp(8) + widest + dp(8);
        graphRight = width - dp(8) - endpointLabelAreaWidth;
        graphTop = dp(47);
        graphBottom = height - dp(25);
        if (graphRight <= graphLeft || graphBottom <= graphTop) return;

        drawGrid(canvas);
        drawVerticalLabels(canvas);
        drawTimeLabels(canvas);
        drawSeries(canvas);
        drawEndpointLabels(canvas);
        if (selectedIndex >= 0 && selectedIndex < timestamps.length) {
            selectedX = xForTime(selectedTimeMillis);
            if (selectedX >= graphLeft && selectedX <= graphRight) drawSelection(canvas);
        }
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

    private float calculateEndpointLabelAreaWidth(float viewWidth) {
        paint.setTextSize(sp(9));
        float widestValue = 0f;
        for (float[] series : values) {
            int latest = latestFiniteIndex(series);
            if (latest >= 0) widestValue = Math.max(widestValue,
                    paint.measureText(formatCurrentValue(series[latest])));
        }
        if (widestValue <= 0f) return dp(2);
        float desired = widestValue + dp(20);
        return Math.min(desired, Math.max(dp(34), viewWidth * 0.30f));
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
        long start = chartStartMillis;
        long end = chartEndMillis;
        if (start <= 0L || end <= start) {
            start = timestamps.length > 0 ? timestamps[0] : System.currentTimeMillis() - 60_000L;
            end = timestamps.length > 0 ? timestamps[timestamps.length - 1] : System.currentTimeMillis();
        }
        long span = Math.max(0L, end - start);
        String pattern = span > 6L * 60L * 60L * 1000L ? "MM/dd HH:mm"
                : span > 15L * 60L * 1000L ? "HH:mm" : "HH:mm:ss";
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.getDefault());
        for (int i = 0; i <= TICK_COUNT; i++) {
            long tickTime = start + span * i / TICK_COUNT;
            String label = timestamps.length > 0 || chartStartMillis > 0L
                    ? formatter.format(new Date(tickTime))
                    : getResources().getString(i == TICK_COUNT
                            ? R.string.sync_chart_now : R.string.sync_chart_seconds_ago,
                            Math.max(0, TICK_COUNT - i) * 15);
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
                if (!Float.isFinite(sample)) {
                    started = false;
                    continue;
                }
                float x = xForSampleIndex(i, count);
                float y = yForValue(sample);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
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
            int lastIndex = latestFiniteIndex(series);
            if (lastIndex >= 0) {
                float x = xForSampleIndex(lastIndex, count);
                float y = yForValue(series[lastIndex]);
                drawSeriesMarker(canvas, s, x, y, dp(2.8f));
            }
        }
    }

    private void drawEndpointLabels(Canvas canvas) {
        if (endpointLabelAreaWidth <= dp(3)) return;
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(sp(9));
        paint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        float tagHeight = Math.max(dp(16), textHeight + dp(5));
        float labelLeft = graphRight + dp(5);
        float maxLabelRight = getWidth() - dp(3);
        float[] centerY = new float[values.length];
        int[] latestIndices = new int[values.length];

        for (int s = 0; s < values.length; s++) {
            latestIndices[s] = latestFiniteIndex(values[s]);
            centerY[s] = latestIndices[s] >= 0
                    ? yForValue(values[s][latestIndices[s]]) : -1f;
        }

        // Keep two current-value tags legible when their line endpoints are close.
        if (centerY.length > 1 && centerY[0] >= 0f && centerY[1] >= 0f
                && Math.abs(centerY[0] - centerY[1]) < tagHeight + dp(2)) {
            float direction = centerY[1] >= centerY[0] ? 1f : -1f;
            centerY[1] = clamp(centerY[0] + direction * (tagHeight + dp(2)),
                    graphTop + tagHeight / 2f, graphBottom - tagHeight / 2f);
            if (Math.abs(centerY[0] - centerY[1]) < tagHeight) {
                centerY[0] = clamp(centerY[1] - direction * (tagHeight + dp(2)),
                        graphTop + tagHeight / 2f, graphBottom - tagHeight / 2f);
            }
        }

        for (int s = 0; s < values.length; s++) {
            int latest = latestIndices[s];
            if (latest < 0) continue;
            float actualY = yForValue(values[s][latest]);
            float labelY = centerY[s];
            String value = formatCurrentValue(values[s][latest]);
            float markerSpace = dp(8);
            float desiredWidth = markerSpace + dp(4) + paint.measureText(value) + dp(6);
            float right = Math.min(maxLabelRight, labelLeft + desiredWidth);
            if (right <= labelLeft) continue;

            paint.setColor(backgroundColor);
            paint.setStyle(Paint.Style.FILL);
            overlayRect.set(labelLeft, labelY - tagHeight / 2f, right, labelY + tagHeight / 2f);
            canvas.drawRoundRect(overlayRect, dp(2), dp(2), paint);

            paint.setColor(gridColor);
            paint.setAlpha(190);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.7f));
            canvas.drawRoundRect(overlayRect, dp(2), dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);

            // A short connector makes an offset tag still point to its real endpoint.
            if (Math.abs(labelY - actualY) > dp(1)) {
                paint.setColor(seriesColor(s));
                paint.setAlpha(180);
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(graphRight, actualY, labelLeft, labelY, paint);
                paint.setAlpha(255);
            }
            drawSeriesMarker(canvas, s, labelLeft + dp(4), labelY, dp(2.2f));
            paint.setColor(textPrimaryColor);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(fitText(value,
                            Math.max(0f, right - labelLeft - markerSpace - dp(7))),
                    labelLeft + markerSpace + dp(1),
                    labelY - (metrics.ascent + metrics.descent) / 2f, paint);
        }
    }

    private void drawSelection(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(gridColor);
        paint.setAlpha(210);
        canvas.drawLine(selectedX, graphTop, selectedX, graphBottom, paint);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);

        for (int s = 0; s < values.length; s++) {
            if (selectedIndex >= 0 && selectedIndex < values[s].length
                    && Float.isFinite(values[s][selectedIndex])) {
                float y = yForValue(values[s][selectedIndex]);
                drawSeriesMarker(canvas, s, selectedX, y, dp(4));
            }
        }
        drawSelectionTooltip(canvas);
    }

    private void drawSelectionTooltip(Canvas canvas) {
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(sp(10));
        paint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float lineHeight = Math.max(dp(12), metrics.descent - metrics.ascent + dp(2));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(selectedTimeMillis));
        int finiteSeriesCount = 0;
        float widest = paint.measureText(time);
        for (int s = 0; s < values.length; s++) {
            if (selectedIndex >= 0 && selectedIndex < values[s].length
                    && Float.isFinite(values[s][selectedIndex])) {
                finiteSeriesCount++;
                String line = selectionValueLabel(s, values[s][selectedIndex]);
                widest = Math.max(widest, paint.measureText(line));
            }
        }

        float horizontalPadding = dp(7);
        float verticalPadding = dp(5);
        float boxWidth = Math.min(getWidth() - dp(8), widest + horizontalPadding * 2);
        float boxHeight = (finiteSeriesCount + 1) * lineHeight + verticalPadding * 2;
        float boxLeft = clamp(selectedX - boxWidth / 2f, dp(4),
                getWidth() - boxWidth - dp(4));
        float boxTop = graphTop + dp(3);
        overlayRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);

        paint.setColor(backgroundColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(overlayRect, dp(3), dp(3), paint);
        paint.setColor(gridColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        canvas.drawRoundRect(overlayRect, dp(3), dp(3), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(textPrimaryColor);
        float baseline = boxTop + verticalPadding - metrics.ascent;
        drawTooltipText(canvas, time, boxLeft + horizontalPadding, baseline,
                boxWidth - horizontalPadding * 2);
        baseline += lineHeight;
        for (int s = 0; s < values.length; s++) {
            if (selectedIndex >= 0 && selectedIndex < values[s].length
                    && Float.isFinite(values[s][selectedIndex])) {
                paint.setColor(seriesColor(s));
                String line = selectionValueLabel(s, values[s][selectedIndex]);
                drawTooltipText(canvas, line, boxLeft + horizontalPadding, baseline,
                        boxWidth - horizontalPadding * 2);
                baseline += lineHeight;
            }
        }
    }

    private void drawTooltipText(Canvas canvas, String text, float x, float baseline,
                                 float availableWidth) {
        canvas.drawText(fitText(text, Math.max(0f, availableWidth)), x, baseline, paint);
    }


    private String fitText(String text, float availableWidth) {
        if (text == null || availableWidth <= 0f) return "";
        if (paint.measureText(text) <= availableWidth) return text;
        String ellipsis = "…";
        float ellipsisWidth = paint.measureText(ellipsis);
        if (ellipsisWidth > availableWidth) return "";
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > availableWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private String selectionValueLabel(int seriesIndex, float value) {
        String name = seriesIndex < seriesNames.length && seriesNames[seriesIndex] != null
                ? seriesNames[seriesIndex] : "";
        String formatted = formatCurrentValue(value);
        return name.isEmpty() ? formatted + " " + unit
                : name + ": " + formatted + " " + unit;
    }

    private void drawSeriesMarker(Canvas canvas, int seriesIndex, float x, float y, float radius) {
        paint.setColor(seriesColor(seriesIndex));
        paint.setStyle(Paint.Style.FILL);
        if (seriesIndex == 1) {
            canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint);
        } else {
            canvas.drawCircle(x, y, radius, paint);
        }
    }

    private float xForSampleIndex(int index, int fallbackCount) {
        if (timestamps.length > index && chartEndMillis > chartStartMillis
                && chartStartMillis > 0L) {
            return xForTime(timestamps[index]);
        }
        return graphLeft + (graphRight - graphLeft) * index
                / Math.max(1f, fallbackCount - 1f);
    }

    private float xForTime(long time) {
        long duration = Math.max(1L, chartEndMillis - chartStartMillis);
        return graphLeft + (graphRight - graphLeft)
                * (time - chartStartMillis) / (float) duration;
    }

    private float yForValue(float value) {
        return graphBottom - (value - axisMin) / (axisMax - axisMin)
                * (graphBottom - graphTop);
    }

    private int latestFiniteIndex(float[] series) {
        if (series == null) return -1;
        for (int i = series.length - 1; i >= 0; i--) {
            if (Float.isFinite(series[i])) return i;
        }
        return -1;
    }

    private int findNearestSampleIndex(long timeMillis) {
        int nearest = -1;
        long nearestDistance = Long.MAX_VALUE;
        for (int i = 0; i < timestamps.length; i++) {
            boolean hasValue = false;
            for (float[] series : values) {
                if (i < series.length && Float.isFinite(series[i])) {
                    hasValue = true;
                    break;
                }
            }
            if (!hasValue) continue;
            long distance = timestamps[i] >= timeMillis
                    ? timestamps[i] - timeMillis : timeMillis - timestamps[i];
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void selectNearestSample(float touchX) {
        if (timestamps.length == 0 || chartEndMillis <= chartStartMillis
                || graphRight <= graphLeft) {
            int count = 0;
            for (float[] series : values) count = Math.max(count, series.length);
            if (count <= 0) {
                clearSelection();
                return;
            }
            float fraction = (clamp(touchX, graphLeft, graphRight) - graphLeft)
                    / Math.max(1f, graphRight - graphLeft);
            selectedIndex = Math.round(fraction * Math.max(0, count - 1));
            selectedTimeMillis = -1L;
            selectedX = xForSampleIndex(selectedIndex, count);
            return;
        }

        float clampedX = clamp(touchX, graphLeft, graphRight);
        double fraction = (clampedX - graphLeft) / Math.max(1f, graphRight - graphLeft);
        long requestedTime = chartStartMillis
                + (long) (fraction * (chartEndMillis - chartStartMillis));
        selectedIndex = findNearestSampleIndex(requestedTime);
        if (selectedIndex < 0) {
            clearSelection();
            return;
        }
        selectedTimeMillis = timestamps[selectedIndex];
        selectedX = clamp(xForTime(selectedTimeMillis), graphLeft, graphRight);
    }

    private void clearSelection() {
        selectedIndex = -1;
        selectedTimeMillis = -1L;
        selectedX = -1f;
    }

    private void scheduleSelectionDismissal() {
        removeCallbacks(hideSelectionRunnable);
        postDelayed(hideSelectionRunnable, SELECTION_DISPLAY_DURATION_MS);
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int seriesColor(int index) {
        return index == 0 ? primaryColor : secondarySeriesColor;
    }

    private String formatCurrentValue(float value) {
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.getDefault());
        formatter.setGroupingUsed(true);
        formatter.setMinimumFractionDigits(0);
        formatter.setMaximumFractionDigits(2);
        return formatter.format(value);
    }

    private String formatAxisValue(float value) {
        float absolute = Math.abs(value);
        if (absolute >= 1_000_000f) return String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f);
        if (absolute >= 10_000f) return String.format(Locale.getDefault(), "%.0fk", value / 1_000f);
        if (absolute >= 1_000f) return String.format(Locale.getDefault(), "%.1fk", value / 1_000f);
        if (Math.abs(value - Math.round(value)) < 0.05f) return String.format(Locale.getDefault(), "%.0f", value);
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (timestamps.length == 0 || !hasAnyFiniteValue()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                removeCallbacks(hideSelectionRunnable);
                selectNearestSample(event.getX());
                invalidate();
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    performClick();
                    scheduleSelectionDismissal();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                scheduleSelectionDismissal();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(hideSelectionRunnable);
        clearSelection();
        super.onDetachedFromWindow();
    }

    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
