package wallet.main;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import org.bitcoinj.base.Coin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.model.BalanceHistoryPoint;
import wallet.util.MoneyFormatter;

/** Theme-aware balance-over-time chart. Values remain exact satoshis internally. */
public final class BalanceHistoryChartView extends View {

    public static final int RANGE_1_DAY = 0;
    public static final int RANGE_7_DAYS = 1;
    public static final int RANGE_1_MONTH = 2;
    public static final int RANGE_3_MONTHS = 3;
    public static final int RANGE_6_MONTHS = 4;
    public static final int RANGE_1_YEAR = 5;
    public static final int RANGE_ALL = 6;

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tooltipRect = new RectF();

    private List<BalanceHistoryPoint> history = new ArrayList<>();
    private boolean historyAvailable;
    private long walletCreationTimeMillis;
    private int range = RANGE_1_YEAR;
    private float selectedX = -1f;
    private BalanceHistoryPoint selectedPoint;
    private long visibleStartMillis;
    private long visibleEndMillis;
    private float graphLeft;
    private float graphRight;
    private float graphTop;
    private float graphBottom;
    private long minimumBalance;
    private long maximumBalance;
    private List<BalanceHistoryPoint> visiblePoints = new ArrayList<>();
    private int accentColor;
    private int secondaryColor;
    private int backgroundColor;

    public BalanceHistoryChartView(Context context) {
        super(context);
        initialize();
    }

    public BalanceHistoryChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public BalanceHistoryChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(dp(2f));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));

        labelPaint.setTextSize(sp(10f));
        labelPaint.setStyle(Paint.Style.FILL);

        pointPaint.setStyle(Paint.Style.FILL);

        tooltipBackgroundPaint.setStyle(Paint.Style.FILL);
        tooltipTextPaint.setTextSize(sp(11f));
        tooltipTextPaint.setStyle(Paint.Style.FILL);

        refreshThemeColors();
        setFocusable(true);
        setClickable(true);
    }

    public void setHistory(List<BalanceHistoryPoint> points,
                           long creationTimeMillis,
                           boolean available) {
        history = points == null ? new ArrayList<>() : new ArrayList<>(points);
        walletCreationTimeMillis = creationTimeMillis;
        historyAvailable = available;
        selectedX = -1f;
        selectedPoint = null;
        rebuildVisiblePoints();
        invalidate();
    }

    public void setRange(int selectedRange) {
        if (selectedRange < RANGE_1_DAY || selectedRange > RANGE_ALL) {
            selectedRange = RANGE_1_YEAR;
        }
        if (range == selectedRange) return;
        range = selectedRange;
        selectedX = -1f;
        selectedPoint = null;
        rebuildVisiblePoints();
        invalidate();
    }

    public void refreshDisplayUnit() {
        invalidate();
    }

    private void refreshThemeColors() {
        accentColor = resolveColor(android.R.attr.colorControlActivated, Color.TRANSPARENT);
        secondaryColor = resolveColor(android.R.attr.textColorSecondary, accentColor);
        backgroundColor = resolveColor(android.R.attr.colorBackground, Color.TRANSPARENT);
        linePaint.setColor(accentColor);
        pointPaint.setColor(accentColor);
        gridPaint.setColor(withAlpha(secondaryColor, 55));
        labelPaint.setColor(secondaryColor);
        tooltipTextPaint.setColor(resolveColor(android.R.attr.textColorPrimary, secondaryColor));
        tooltipBackgroundPaint.setColor(backgroundColor);
    }

    private int resolveColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return getResources().getColor(value.resourceId, getContext().getTheme());
                } catch (Exception ignored) {
                }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return fallback;
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 0xff) << 24);
    }

    private void rebuildVisiblePoints() {
        visiblePoints.clear();
        if (!historyAvailable || history.isEmpty()) return;

        long now = System.currentTimeMillis();
        Calendar rangeCalendar = Calendar.getInstance();
        rangeCalendar.setTimeInMillis(now);
        switch (range) {
            case RANGE_1_DAY:
                rangeCalendar.add(Calendar.DAY_OF_YEAR, -1);
                break;
            case RANGE_7_DAYS:
                rangeCalendar.add(Calendar.DAY_OF_YEAR, -7);
                break;
            case RANGE_1_MONTH:
                rangeCalendar.add(Calendar.MONTH, -1);
                break;
            case RANGE_3_MONTHS:
                rangeCalendar.add(Calendar.MONTH, -3);
                break;
            case RANGE_6_MONTHS:
                rangeCalendar.add(Calendar.MONTH, -6);
                break;
            case RANGE_ALL:
                rangeCalendar.setTimeInMillis(walletCreationTimeMillis);
                break;
            case RANGE_1_YEAR:
            default:
                rangeCalendar.add(Calendar.YEAR, -1);
                break;
        }
        long rangeStart = rangeCalendar.getTimeInMillis();
        visibleStartMillis = Math.max(walletCreationTimeMillis, rangeStart);
        visibleEndMillis = Math.max(now, visibleStartMillis + 1L);

        BalanceHistoryPoint startingPoint = history.get(0);
        for (BalanceHistoryPoint point : history) {
            if (point.timestampMillis > visibleStartMillis) break;
            startingPoint = point;
        }
        visiblePoints.add(new BalanceHistoryPoint(visibleStartMillis,
                startingPoint.balanceSatoshis));
        for (BalanceHistoryPoint point : history) {
            if (point.timestampMillis > visibleStartMillis
                    && point.timestampMillis <= visibleEndMillis) {
                visiblePoints.add(point);
            }
        }
        BalanceHistoryPoint last = visiblePoints.get(visiblePoints.size() - 1);
        if (last.timestampMillis < visibleEndMillis) {
            visiblePoints.add(new BalanceHistoryPoint(visibleEndMillis,
                    last.balanceSatoshis));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        refreshThemeColors();
        if (!historyAvailable || visiblePoints.isEmpty()) {
            drawUnavailable(canvas);
            return;
        }

        float width = getWidth();
        float height = getHeight();
        graphLeft = dp(6f);
        graphRight = Math.max(graphLeft + 1f, width - dp(6f));
        graphTop = dp(12f);
        graphBottom = Math.max(graphTop + 1f, height - dp(23f));

        calculateVerticalRange();
        drawGrid(canvas);
        drawBalanceLine(canvas);
        drawAxisLabels(canvas);
        if (selectedPoint != null && selectedX >= graphLeft && selectedX <= graphRight) {
            drawSelection(canvas);
        }
    }

    private void drawUnavailable(Canvas canvas) {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(getContext().getString(R.string.balance_history_unavailable),
                getWidth() / 2f, getHeight() / 2f, labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void calculateVerticalRange() {
        minimumBalance = Long.MAX_VALUE;
        maximumBalance = Long.MIN_VALUE;
        for (BalanceHistoryPoint point : visiblePoints) {
            minimumBalance = Math.min(minimumBalance, point.balanceSatoshis);
            maximumBalance = Math.max(maximumBalance, point.balanceSatoshis);
        }
        if (minimumBalance == Long.MAX_VALUE) {
            minimumBalance = 0L;
            maximumBalance = 0L;
        }
        long span = maximumBalance - minimumBalance;
        if (span <= 0L) {
            long margin = Math.max(1L, Math.abs(maximumBalance) / 10L);
            minimumBalance = Math.max(0L, minimumBalance - margin);
            maximumBalance = maximumBalance + margin;
        } else {
            long margin = Math.max(1L, span / 10L);
            minimumBalance = Math.max(0L, minimumBalance - margin);
            maximumBalance = maximumBalance + margin;
        }
    }

    private void drawGrid(Canvas canvas) {
        float rowHeight = (graphBottom - graphTop) / 2f;
        canvas.drawLine(graphLeft, graphTop, graphRight, graphTop, gridPaint);
        canvas.drawLine(graphLeft, graphTop + rowHeight, graphRight,
                graphTop + rowHeight, gridPaint);
        canvas.drawLine(graphLeft, graphBottom, graphRight, graphBottom, gridPaint);
    }

    private void drawBalanceLine(Canvas canvas) {
        Path path = new Path();
        BalanceHistoryPoint first = visiblePoints.get(0);
        float previousX = xForTime(first.timestampMillis);
        float previousY = yForBalance(first.balanceSatoshis);
        path.moveTo(previousX, previousY);

        for (int i = 1; i < visiblePoints.size(); i++) {
            BalanceHistoryPoint point = visiblePoints.get(i);
            float x = xForTime(point.timestampMillis);
            float y = yForBalance(point.balanceSatoshis);
            path.lineTo(x, previousY);
            path.lineTo(x, y);
            previousX = x;
            previousY = y;
        }
        canvas.drawPath(path, linePaint);
    }

    private void drawAxisLabels(Canvas canvas) {
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(formatShortDate(visibleStartMillis), graphLeft,
                getHeight() - dp(5f), labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatShortDate(visibleEndMillis), graphRight,
                getHeight() - dp(5f), labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSelection(Canvas canvas) {
        float pointY = yForBalance(selectedPoint.balanceSatoshis);
        Paint crosshair = gridPaint;
        canvas.drawLine(selectedX, graphTop, selectedX, graphBottom, crosshair);
        canvas.drawCircle(selectedX, pointY, dp(4f), pointPaint);

        String amount = MoneyFormatter.format(getContext(),
                Coin.valueOf(selectedPoint.balanceSatoshis));
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(selectedPoint.timestampMillis));
        String label = amount + "  •  " + date;
        float textWidth = tooltipTextPaint.measureText(label);
        float horizontalPadding = dp(8f);
        float verticalPadding = dp(6f);
        float boxWidth = Math.min(getWidth() - dp(8f), textWidth + horizontalPadding * 2f);
        float boxHeight = tooltipTextPaint.getTextSize() + verticalPadding * 2f;
        float boxLeft = Math.max(dp(4f), Math.min(selectedX - boxWidth / 2f,
                getWidth() - boxWidth - dp(4f)));
        float boxTop = graphTop + dp(2f);
        tooltipRect.set(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);
        canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), tooltipBackgroundPaint);
        canvas.drawRoundRect(tooltipRect, dp(6f), dp(6f), gridPaint);
        canvas.drawText(label, boxLeft + horizontalPadding,
                boxTop + verticalPadding + tooltipTextPaint.getTextSize() * 0.82f,
                tooltipTextPaint);
    }

    private float xForTime(long time) {
        long duration = Math.max(1L, visibleEndMillis - visibleStartMillis);
        return graphLeft + (float) (time - visibleStartMillis) / duration
                * (graphRight - graphLeft);
    }

    private float yForBalance(long balance) {
        double span = Math.max(1d, (double) maximumBalance - (double) minimumBalance);
        double fraction = ((double) balance - (double) minimumBalance) / span;
        return graphBottom - (float) fraction * (graphBottom - graphTop);
    }

    private String formatShortDate(long time) {
        String pattern = range == RANGE_1_DAY ? "HH:mm" : "dd/MM";
        if (range == RANGE_ALL && walletCreationTimeMillis > 0L
                && visibleEndMillis - visibleStartMillis > 365L * DAY_MS) {
            pattern = "MM/yyyy";
        }
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(time));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!historyAvailable || visiblePoints.isEmpty()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                selectedX = Math.max(graphLeft, Math.min(graphRight, event.getX()));
                long selectedTime = visibleStartMillis
                        + (long) ((selectedX - graphLeft)
                        / Math.max(1f, graphRight - graphLeft)
                        * (visibleEndMillis - visibleStartMillis));
                selectedPoint = nearestPoint(selectedTime);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                selectedX = -1f;
                selectedPoint = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private BalanceHistoryPoint nearestPoint(long time) {
        BalanceHistoryPoint selected = visiblePoints.get(0);
        for (BalanceHistoryPoint point : visiblePoints) {
            if (point.timestampMillis > time) break;
            selected = point;
        }
        return selected;
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
}
