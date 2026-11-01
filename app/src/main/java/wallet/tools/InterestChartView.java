package wallet.tools;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import wallet.main.R;

import java.util.ArrayList;
import java.util.List;

/** Draws the interest projection chart using colors supplied by the active app theme. */
public final class InterestChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Series> series = new ArrayList<>();
    private int primaryTextColor;
    private int secondaryTextColor;
    private int accentColor;
    private int gridColor;

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
        refreshThemeColors(context);
        setContentDescription(context.getString(R.string.calculator_growth_chart));
    }

    private void refreshThemeColors(Context context) {
        int[] attributes = {
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                androidx.appcompat.R.attr.colorAccent,
                androidx.appcompat.R.attr.colorControlNormal
        };
        TypedArray values = context.obtainStyledAttributes(attributes);
        primaryTextColor = values.getColor(0, values.getColor(1, 0));
        secondaryTextColor = values.getColor(1, primaryTextColor);
        accentColor = values.getColor(2, primaryTextColor);
        gridColor = values.getColor(3, secondaryTextColor);
        values.recycle();
    }

    public void setSeries(@Nullable List<Series> newSeries) {
        series.clear();
        if (newSeries != null) series.addAll(newSeries);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isInEditMode()) refreshThemeColors(getContext());

        float left = dp(10);
        float right = getWidth() - dp(10);
        float top = dp(12);
        float bottom = getHeight() - dp(20);
        if (right <= left || bottom <= top || series.isEmpty()) return;

        double maximum = 1.0d;
        for (Series line : series) {
            for (Double value : line.values) {
                if (value != null && Double.isFinite(value)) maximum = Math.max(maximum, value);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setPathEffect(null);
        paint.setStrokeWidth(dp(1));
        paint.setColor(gridColor);
        paint.setAlpha(90);
        for (int index = 0; index <= 4; index++) {
            float y = top + (bottom - top) * index / 4.0f;
            canvas.drawLine(left, y, right, y, paint);
        }

        paint.setAlpha(255);
        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            Series line = series.get(seriesIndex);
            if (line.values.isEmpty()) continue;

            paint.setColor(colorForSeries(seriesIndex, line.invested));
            paint.setStrokeWidth(line.invested ? dp(1.5f) : dp(2.4f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(line.invested
                    ? new android.graphics.DashPathEffect(new float[]{dp(5), dp(4)}, 0)
                    : null);

            Path path = new Path();
            int count = line.values.size();
            for (int index = 0; index < count; index++) {
                Double value = line.values.get(index);
                double bounded = value == null ? 0.0d : Math.max(0.0d, Math.min(maximum, value));
                float x = left + (right - left) * (count <= 1 ? 0.0f : index / (float) (count - 1));
                float y = bottom - (float) (bounded / maximum) * (bottom - top);
                if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            canvas.drawPath(path, paint);
        }

        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(secondaryTextColor);
        paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        String startLabel = getResources().getString(R.string.calculator_chart_start);
        String endLabel = getResources().getString(R.string.calculator_chart_end);
        canvas.drawText(startLabel, left, getHeight() - dp(3), paint);
        canvas.drawText(endLabel, Math.max(left, right - paint.measureText(endLabel)), getHeight() - dp(3), paint);
    }

    private int colorForSeries(int index, boolean invested) {
        if (invested) return secondaryTextColor;
        switch (index % 3) {
            case 0: return accentColor;
            case 1: return primaryTextColor;
            default: return gridColor;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
