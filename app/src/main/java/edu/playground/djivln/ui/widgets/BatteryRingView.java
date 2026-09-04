package edu.playground.djivln.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import edu.playground.djivln.R;

/** V4/DJI Fly style aircraft-battery ring with the percentage in the centre. */
public final class BatteryRingView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final int successColor;
    private final int warningColor;
    private final int dangerColor;
    private int level = -1;

    public BatteryRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0x66FFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(0xF2FFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        successColor = context.getColor(R.color.of_hud_success);
        warningColor = context.getColor(R.color.of_hud_warning);
        dangerColor = context.getColor(R.color.of_hud_danger);
        setContentDescription(context.getString(R.string.aircraft_battery_unknown));
    }

    public void setLevel(int percent) {
        int clamped = Math.max(-1, Math.min(100, percent));
        if (clamped == level) return;
        level = clamped;
        setContentDescription(level >= 0
                ? getContext().getString(R.string.aircraft_battery_percent, level)
                : getContext().getString(R.string.aircraft_battery_unknown));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 0.11f;
        trackPaint.setStrokeWidth(stroke);
        ringPaint.setStrokeWidth(stroke);
        arcBounds.set(stroke, stroke, size - stroke, size - stroke);
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint);
        if (level >= 0) {
            ringPaint.setColor(level > 50 ? successColor : level > 20 ? warningColor : dangerColor);
            canvas.drawArc(arcBounds, -90f, level * 3.6f, false, ringPaint);
        }
        textPaint.setTextSize(size * 0.28f);
        String text = level >= 0 ? String.valueOf(level) : "--";
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(text, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, textPaint);
    }
}
