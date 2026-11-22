package wallet.sync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import wallet.main.R;

/** Lightweight theme-aware live line chart used by the Sync screen. */
public final class SyncLineChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String title = "";
    private String[] seriesNames = new String[0];
    private float[][] values = new float[0][];
    private int primaryColor;
    private int secondaryColor;
    private int gridColor;
    private int textColor;
    private float density;

    public SyncLineChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        resolveThemeColors();
    }

    private void resolveThemeColors() {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            primaryColor = value.data;
        }
        if (getContext().getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true)) {
            secondaryColor = value.data;
        }
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorControlHighlight, value, true)) {
            gridColor = value.data;
        }
        if (getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            textColor = value.data;
        }
    }

    public void setChartData(String chartTitle, String[] names, float[][] chartValues) {
        title = chartTitle == null ? "" : chartTitle;
        seriesNames = names == null ? new String[0] : names.clone();
        if (chartValues == null) {
            values = new float[0][];
        } else {
            values = new float[chartValues.length][];
            for (int i = 0; i < chartValues.length; i++) {
                values[i] = chartValues[i] == null ? new float[0] : chartValues[i].clone();
            }
        }
        setContentDescription(title);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + 8f * density;
        float right = getWidth() - getPaddingRight() - 8f * density;
        float top = getPaddingTop() + 42f * density;
        float bottom = getHeight() - getPaddingBottom() - 24f * density;
        if (right <= left || bottom <= top) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(textColor);
        paint.setTextSize(13f * density);
        paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        canvas.drawText(title, left, getPaddingTop() + 16f * density, paint);

        float legendX = left;
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(10f * density);
        for (int i = 0; i < seriesNames.length; i++) {
            int color = seriesColor(i);
            paint.setColor(color);
            canvas.drawCircle(legendX + 3f * density, getPaddingTop() + 25f * density, 3f * density, paint);
            paint.setColor(secondaryColor);
            String label = seriesNames[i] == null ? "" : seriesNames[i];
            canvas.drawText(label, legendX + 10f * density, getPaddingTop() + 28f * density, paint);
            legendX += paint.measureText(label) + 24f * density;
        }

        paint.setColor(gridColor);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(secondaryColor);
        paint.setTextSize(9f * density);
        canvas.drawText("0", left, getHeight() - getPaddingBottom() - 5f * density, paint);
        String windowLabel = getResources().getString(R.string.sync_chart_history_window);
        canvas.drawText(windowLabel, right - paint.measureText(windowLabel), getHeight() - getPaddingBottom() - 5f * density, paint);

        int count = 0;
        for (float[] serie : values) count = Math.max(count, serie.length);
        if (count < 2 || values.length == 0) return;

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float[] serie : values) {
            for (float sample : serie) {
                if (Float.isNaN(sample) || Float.isInfinite(sample)) continue;
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
        }
        if (!Float.isFinite(min) || !Float.isFinite(max)) return;
        if (max == min) {
            float padding = Math.max(1f, Math.abs(max) * 0.1f);
            min -= padding;
            max += padding;
        } else {
            float padding = (max - min) * 0.08f;
            min -= padding;
            max += padding;
        }

        for (int s = 0; s < values.length; s++) {
            float[] serie = values[s];
            if (serie.length < 2) continue;
            Path path = new Path();
            boolean started = false;
            for (int i = 0; i < serie.length; i++) {
                float sample = serie[i];
                if (Float.isNaN(sample) || Float.isInfinite(sample)) {
                    started = false;
                    continue;
                }
                float x = left + (right - left) * i / Math.max(1f, count - 1f);
                float y = bottom - (sample - min) / (max - min) * (bottom - top);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            paint.setColor(seriesColor(s));
            paint.setStrokeWidth(2f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private int seriesColor(int index) {
        return index == 0 ? primaryColor : secondaryColor;
    }
}
