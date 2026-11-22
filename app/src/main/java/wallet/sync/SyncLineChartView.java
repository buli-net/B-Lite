package wallet.sync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

import wallet.main.R;

/** Theme-aware live chart with value and time axes for the Sync screen. */
public final class SyncLineChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String title = "";
    private String unit = "";
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

    public void setChartData(String chartTitle, String[] names, float[][] chartValues, String valueUnit) {
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
        setContentDescription(title + ", " + unit);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float left = getPaddingLeft() + 48f * density;
        final float right = getWidth() - getPaddingRight() - 8f * density;
        final float top = getPaddingTop() + 39f * density;
        final float bottom = getHeight() - getPaddingBottom() - 30f * density;
        if (right <= left || bottom <= top) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(textColor);
        paint.setTextSize(13f * density);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(title, getPaddingLeft() + 2f * density,
                getPaddingTop() + 15f * density, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(10f * density);
        paint.setColor(secondaryColor);
        canvas.drawText(unit, right - paint.measureText(unit),
                getPaddingTop() + 15f * density, paint);

        float legendX = getPaddingLeft() + 2f * density;
        for (int i = 0; i < seriesNames.length; i++) {
            String label = seriesNames[i] == null ? "" : seriesNames[i];
            paint.setColor(seriesColor(i));
            paint.setStrokeWidth(2f * density);
            canvas.drawLine(legendX, getPaddingTop() + 28f * density,
                    legendX + 12f * density, getPaddingTop() + 28f * density, paint);
            paint.setColor(secondaryColor);
            canvas.drawText(label, legendX + 16f * density,
                    getPaddingTop() + 31f * density, paint);
            legendX += 22f * density + paint.measureText(label) + 12f * density;
        }

        int count = 0;
        for (float[] serie : values) count = Math.max(count, serie.length);
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float[] serie : values) {
            for (float sample : serie) {
                if (!Float.isFinite(sample)) continue;
                min = Math.min(min, sample);
                max = Math.max(max, sample);
            }
        }
        if (!Float.isFinite(min) || !Float.isFinite(max)) {
            min = 0f;
            max = 1f;
        }
        if (max == min) {
            float padding = Math.max(1f, Math.abs(max) * 0.1f);
            min -= padding;
            max += padding;
        } else {
            float padding = (max - min) * 0.08f;
            min -= padding;
            max += padding;
        }

        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(gridColor);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
            float x = left + (right - left) * i / 4f;
            canvas.drawLine(x, top, x, bottom, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(secondaryColor);
        paint.setTextSize(9f * density);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float value = max - (max - min) * i / 4f;
            float y = top + (bottom - top) * i / 4f;
            canvas.drawText(formatAxisValue(value), left - 6f * density,
                    y + 3f * density, paint);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            String label;
            if (i == 4) {
                label = getResources().getString(R.string.sync_chart_now);
            } else {
                int secondsAgo = Math.max(0, Math.round(Math.max(0, count - 1) * (4 - i) / 4f));
                label = getResources().getString(R.string.sync_chart_seconds_ago, secondsAgo);
            }
            canvas.drawText(label, x, getHeight() - getPaddingBottom() - 7f * density, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);

        if (count < 2 || values.length == 0) return;
        for (int s = 0; s < values.length; s++) {
            float[] serie = values[s];
            if (serie.length < 2) continue;
            Path path = new Path();
            boolean started = false;
            for (int i = 0; i < serie.length; i++) {
                float sample = serie[i];
                if (!Float.isFinite(sample)) {
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

    private String formatAxisValue(float value) {
        float absolute = Math.abs(value);
        String number;
        if (absolute >= 1_000_000f) {
            number = String.format(Locale.getDefault(), "%.1fM", value / 1_000_000f);
        } else if (absolute >= 10_000f) {
            number = String.format(Locale.getDefault(), "%.0fk", value / 1_000f);
        } else if (absolute >= 1_000f) {
            number = String.format(Locale.getDefault(), "%.1fk", value / 1_000f);
        } else if (Math.abs(value - Math.round(value)) < 0.05f) {
            number = String.format(Locale.getDefault(), "%.0f", value);
        } else {
            number = String.format(Locale.getDefault(), "%.1f", value);
        }
        return number;
    }

    private int seriesColor(int index) {
        if (index == 0) return primaryColor;
        if (index == 1) return secondaryColor;
        return textColor;
    }
}
