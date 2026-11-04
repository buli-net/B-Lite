package wallet.tools;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import wallet.main.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** Theme-aware interest projection chart with dynamic axes and touch inspection. */
public final class InterestChartView extends View {
    private static final int AXIS_INTERVALS = 5;
    private static final int MAX_TOOLTIP_SERIES = 4;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tooltipRect = new RectF();
    private final List<Series> series = new ArrayList<>();

    private int primaryTextColor;
    private int secondaryTextColor;
    private int accentColor;
    private int gridColor;
    private int backgroundColor;

    private LocalDate startDate;
    private LocalDate maturityDate;
    private Locale displayLocale = Locale.getDefault();
    private String currencyCode = "USD";
    private double verticalAxisMaximum = 1d;
    private float graphLeft;
    private float graphRight;
    private float graphTop;
    private float graphBottom;
    private int selectedIndex = -1;
    private float downX;
    private float downY;

    public InterestChartView(Context context) {
        super(context);
        initialize(context);
    }

    public InterestChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    public InterestChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(Context context) {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));

        labelPaint.setTextSize(sp(10f));
        labelPaint.setStyle(Paint.Style.FILL);

        pointPaint.setStyle(Paint.Style.FILL);

        tooltipBackgroundPaint.setStyle(Paint.Style.FILL);
        tooltipBorderPaint.setStyle(Paint.Style.STROKE);
        tooltipBorderPaint.setStrokeWidth(dp(1f));
        tooltipTextPaint.setTextSize(sp(10.5f));
        tooltipTextPaint.setStyle(Paint.Style.FILL);

        refreshThemeColors();
        setContentDescription(context.getString(R.string.calculator_growth_chart));
        setFocusable(true);
        setClickable(true);
    }

    /** Supplies the date and currency context used by both chart axes and the tooltip. */
    public void setProjectionPeriod(@Nullable LocalDate start,
                                    @Nullable LocalDate maturity,
                                    @Nullable Locale locale,
                                    @Nullable String selectedCurrencyCode) {
        startDate = start;
        maturityDate = maturity;
        if (locale != null) displayLocale = locale;
        if (selectedCurrencyCode != null && !selectedCurrencyCode.trim().isEmpty()) {
            currencyCode = selectedCurrencyCode;
        }
        selectedIndex = -1;
        invalidate();
    }

    public void setSeries(@Nullable List<Series> newSeries) {
        series.clear();
        if (newSeries != null) series.addAll(newSeries);
        selectedIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        refreshThemeColors();
        if (series.isEmpty() || pointCount() < 2) return;

        float width = getWidth();
        float height = getHeight();
        graphTop = dp(12f);
        graphBottom = Math.max(graphTop + dp(1f), height - dp(29f));
        calculateVerticalRange();
        calculateGraphBounds(width);
        if (graphRight <= graphLeft || graphBottom <= graphTop) return;

        drawGrid(canvas);
        drawVerticalAxisLabels(canvas);
        drawSeries(canvas);
        drawDateAxis(canvas);
        if (selectedIndex >= 0) drawSelection(canvas);
    }

    private void refreshThemeColors() {
        int[] attributes = {
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                androidx.appcompat.R.attr.colorAccent,
                androidx.appcompat.R.attr.colorControlNormal,
                android.R.attr.colorBackground
        };
        TypedArray values = getContext().obtainStyledAttributes(attributes);
        primaryTextColor = values.getColor(0, 0xff000000);
        secondaryTextColor = values.getColor(1, primaryTextColor);
        accentColor = values.getColor(2, primaryTextColor);
        gridColor = values.getColor(3, secondaryTextColor);
        backgroundColor = values.getColor(4, 0xffffffff);
        values.recycle();

        labelPaint.setColor(secondaryTextColor);
        gridPaint.setColor(withAlpha(gridColor, 75));
        tooltipTextPaint.setColor(primaryTextColor);
        tooltipBackgroundPaint.setColor(backgroundColor);
        tooltipBorderPaint.setColor(withAlpha(secondaryTextColor, 150));
        pointPaint.setColor(accentColor);
    }

    private void calculateVerticalRange() {
        double maximum = 0d;
        for (Series line : series) {
            for (Double value : line.values) {
                if (value != null && Double.isFinite(value)) {
                    maximum = Math.max(maximum, Math.max(0d, value));
                }
            }
        }
        if (maximum <= 0d) {
            verticalAxisMaximum = 1d;
        } else {
            // Leave 10% headroom above the largest projected balance or comparison scenario.
            verticalAxisMaximum = maximum * 1.10d;
            if (!Double.isFinite(verticalAxisMaximum) || verticalAxisMaximum <= maximum) {
                verticalAxisMaximum = maximum;
            }
        }
    }

    private void calculateGraphBounds(float width) {
        float widestLabel = 0f;
        for (int tick = 0; tick <= AXIS_INTERVALS; tick++) {
            widestLabel = Math.max(widestLabel,
                    labelPaint.measureText(formatAxisValue(axisValueAt(tick))));
        }
        float maxAxisWidth = Math.max(dp(24f), Math.min(width * 0.40f, width - dp(100f)));
        graphLeft = dp(6f) + Math.min(widestLabel, maxAxisWidth) + dp(8f);
        graphRight = Math.max(graphLeft + dp(1f), width - dp(6f));
    }

    private void drawGrid(Canvas canvas) {
        float graphHeight = graphBottom - graphTop;
        for (int tick = 0; tick <= AXIS_INTERVALS; tick++) {
            float y = graphBottom - graphHeight * tick / (float) AXIS_INTERVALS;
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint);
        }

        float graphWidth = graphRight - graphLeft;
        for (int tick = 0; tick <= 4; tick++) {
            float x = graphLeft + graphWidth * tick / 4f;
            if (tick > 0 && tick < 4) {
                canvas.drawLine(x, graphTop, x, graphBottom, gridPaint);
            }
            canvas.drawLine(x, graphBottom, x, graphBottom + dp(4f), gridPaint);
        }
    }

    private void drawVerticalAxisLabels(Canvas canvas) {
        Paint.Align previous = labelPaint.getTextAlign();
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float right = graphLeft - dp(6f);
        float height = graphBottom - graphTop;
        for (int tick = 0; tick <= AXIS_INTERVALS; tick++) {
            float y = graphBottom - height * tick / (float) AXIS_INTERVALS;
            float baseline = y - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(formatAxisValue(axisValueAt(tick)), right, baseline, labelPaint);
        }
        labelPaint.setTextAlign(previous);
    }

    private double axisValueAt(int tick) {
        return verticalAxisMaximum * tick / (double) AXIS_INTERVALS;
    }

    private void drawSeries(Canvas canvas) {
        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            Series line = series.get(seriesIndex);
            if (line.values.size() < 2) continue;

            linePaint.setColor(colorForSeries(seriesIndex, line.invested));
            linePaint.setStrokeWidth(line.invested ? dp(1.6f) : dp(2.4f));
            linePaint.setPathEffect(line.invested
                    ? new android.graphics.DashPathEffect(new float[]{dp(5f), dp(4f)}, 0f)
                    : null);

            Path path = new Path();
            int count = line.values.size();
            for (int index = 0; index < count; index++) {
                float x = xForIndex(index, count);
                float y = yForValue(safeValue(line.values.get(index)));
                if (index == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            canvas.drawPath(path, linePaint);
        }
        linePaint.setPathEffect(null);
    }

    private void drawDateAxis(Canvas canvas) {
        if (startDate == null || maturityDate == null) {
            drawFallbackDateAxis(canvas);
            return;
        }
        long durationDays = Math.max(0L, ChronoUnit.DAYS.between(startDate, maturityDate));
        DateTimeFormatter formatter = dateAxisFormatter(durationDays);
        Paint.Align previous = labelPaint.getTextAlign();
        float graphWidth = graphRight - graphLeft;
        float baseline = getHeight() - dp(5f);
        for (int tick = 0; tick <= 4; tick++) {
            long dayOffset = Math.round(durationDays * tick / 4d);
            String label = startDate.plusDays(dayOffset).format(formatter);
            float x = graphLeft + graphWidth * tick / 4f;
            if (tick == 0) labelPaint.setTextAlign(Paint.Align.LEFT);
            else if (tick == 4) labelPaint.setTextAlign(Paint.Align.RIGHT);
            else labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, x, baseline, labelPaint);
        }
        labelPaint.setTextAlign(previous);
    }

    private DateTimeFormatter dateAxisFormatter(long durationDays) {
        String pattern;
        if (durationDays <= 45L) pattern = "d/M";
        else if (durationDays <= 400L) pattern = "d/M/yy";
        else pattern = "MMM yy";
        return DateTimeFormatter.ofPattern(pattern, displayLocale);
    }

    private void drawFallbackDateAxis(Canvas canvas) {
        Paint.Align previous = labelPaint.getTextAlign();
        float baseline = getHeight() - dp(5f);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(getResources().getString(R.string.calculator_chart_start), graphLeft, baseline, labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(getResources().getString(R.string.calculator_chart_end), graphRight, baseline, labelPaint);
        labelPaint.setTextAlign(previous);
    }

    private void drawSelection(Canvas canvas) {
        int count = pointCount();
        if (count < 2) return;
        float selectedX = xForIndex(selectedIndex, count);
        canvas.drawLine(selectedX, graphTop, selectedX, graphBottom, gridPaint);

        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            Series line = series.get(seriesIndex);
            if (line.values.isEmpty()) continue;
            int index = mappedIndex(selectedIndex, count, line.values.size());
            float y = yForValue(safeValue(line.values.get(index)));
            pointPaint.setColor(colorForSeries(seriesIndex, line.invested));
            canvas.drawCircle(selectedX, y, dp(3.5f), pointPaint);
        }
        drawTooltip(canvas, selectedX, count);
    }

    private void drawTooltip(Canvas canvas, float selectedX, int count) {
        List<String> rows = new ArrayList<>();
        List<Integer> rowColors = new ArrayList<>();
        if (startDate != null && maturityDate != null) {
            long days = ChronoUnit.DAYS.between(startDate, maturityDate);
            long selectedDays = Math.round(days * selectedIndex / (double) Math.max(1, count - 1));
            rows.add(startDate.plusDays(selectedDays).format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(displayLocale)));
            rowColors.add(primaryTextColor);
        }
        int displayedSeries = Math.min(series.size(), MAX_TOOLTIP_SERIES);
        for (int i = 0; i < displayedSeries; i++) {
            Series line = series.get(i);
            if (line.values.isEmpty()) continue;
            int index = mappedIndex(selectedIndex, count, line.values.size());
            rows.add(line.name + ": " + formatMoney(safeValue(line.values.get(index))));
            rowColors.add(colorForSeries(i, line.invested));
        }
        if (rows.isEmpty()) return;

        float horizontalPadding = dp(8f);
        float verticalPadding = dp(6f);
        float lineHeight = tooltipTextPaint.getTextSize() + dp(4f);
        float maxTextWidth = Math.max(dp(40f), getWidth() - dp(20f) - horizontalPadding * 2f);
        float contentWidth = 0f;
        List<String> fittedRows = new ArrayList<>();
        for (String row : rows) {
            String fitted = fitText(row, maxTextWidth, tooltipTextPaint);
            fittedRows.add(fitted);
            contentWidth = Math.max(contentWidth, tooltipTextPaint.measureText(fitted));
        }
        float boxWidth = Math.min(getWidth() - dp(8f), contentWidth + horizontalPadding * 2f);
        float boxHeight = verticalPadding * 2f + lineHeight * fittedRows.size();
        float boxLeft = Math.max(dp(4f), Math.min(selectedX - boxWidth / 2f,
                getWidth() - boxWidth - dp(4f)));
        float boxTop = graphTop + dp(3f);
        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);
        canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipBackgroundPaint);
        canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipBorderPaint);

        float baseline = boxTop + verticalPadding + tooltipTextPaint.getTextSize();
        for (int i = 0; i < fittedRows.size(); i++) {
            tooltipTextPaint.setColor(rowColors.get(i));
            canvas.drawText(fittedRows.get(i), boxLeft + horizontalPadding, baseline, tooltipTextPaint);
            baseline += lineHeight;
        }
    }

    private String fitText(String value, float maxWidth, Paint paint) {
        if (paint.measureText(value) <= maxWidth) return value;
        String ellipsis = "…";
        int end = value.length();
        while (end > 1 && paint.measureText(value.substring(0, end) + ellipsis) > maxWidth) end--;
        return value.substring(0, Math.max(1, end)) + ellipsis;
    }

    private float xForIndex(int index, int count) {
        float fraction = count <= 1 ? 0f : index / (float) (count - 1);
        return graphLeft + fraction * (graphRight - graphLeft);
    }

    private float yForValue(double value) {
        double fraction = verticalAxisMaximum <= 0d ? 0d
                : Math.max(0d, Math.min(1d, value / verticalAxisMaximum));
        return graphBottom - (float) fraction * (graphBottom - graphTop);
    }

    private int pointCount() {
        int maximum = 0;
        for (Series line : series) maximum = Math.max(maximum, line.values.size());
        return maximum;
    }

    private int mappedIndex(int index, int sourceCount, int targetCount) {
        if (targetCount <= 1 || sourceCount <= 1) return 0;
        return Math.max(0, Math.min(targetCount - 1,
                Math.round(index * (targetCount - 1f) / (sourceCount - 1f))));
    }

    private double safeValue(Double value) {
        if (value == null || !Double.isFinite(value)) return 0d;
        return Math.max(0d, value);
    }

    private String formatAxisValue(double value) {
        Currency currency = currencyForDisplay();
        NumberFormat formatter = NumberFormat.getCurrencyInstance(displayLocale);
        formatter.setCurrency(currency);
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        digits = Math.min(digits, 2);
        formatter.setMinimumFractionDigits(digits);
        formatter.setMaximumFractionDigits(digits);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(BigDecimal.valueOf(value));
    }

    private String formatMoney(double value) {
        Currency currency = currencyForDisplay();
        NumberFormat formatter = NumberFormat.getCurrencyInstance(displayLocale);
        formatter.setCurrency(currency);
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) digits = 2;
        digits = Math.min(digits, 4);
        formatter.setMinimumFractionDigits(digits);
        formatter.setMaximumFractionDigits(digits);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP));
    }

    private Currency currencyForDisplay() {
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException ex) {
            return Currency.getInstance("USD");
        }
    }

    private int colorForSeries(int index, boolean invested) {
        if (invested) return secondaryTextColor;
        switch (index % 3) {
            case 0: return accentColor;
            case 1: return primaryTextColor;
            default: return gridColor;
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 0xff) << 24);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (series.isEmpty() || pointCount() < 2) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                requestParentInterception(false);
                updateSelectionFromX(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY)) {
                    requestParentInterception(true);
                    updateSelectionFromX(event.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
                updateSelectionFromX(event.getX());
                requestParentInterception(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                requestParentInterception(false);
                selectedIndex = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void requestParentInterception(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void updateSelectionFromX(float touchX) {
        if (graphRight <= graphLeft) return;
        float x = Math.max(graphLeft, Math.min(graphRight, touchX));
        float fraction = (x - graphLeft) / Math.max(dp(1f), graphRight - graphLeft);
        int count = pointCount();
        selectedIndex = Math.max(0, Math.min(count - 1,
                Math.round(fraction * (count - 1))));
        invalidate();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    public static final class Series {
        public final String name;
        public final List<Double> values;
        public final boolean invested;

        public Series(String name, List<Double> values, boolean invested) {
            this.name = name == null ? "" : name;
            this.values = values == null ? new ArrayList<>() : new ArrayList<>(values);
            this.invested = invested;
        }
    }
}
