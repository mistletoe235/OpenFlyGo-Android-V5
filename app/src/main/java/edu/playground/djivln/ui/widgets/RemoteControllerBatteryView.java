package edu.playground.djivln.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import edu.playground.djivln.R;

/** Phone-style RC battery icon with the effective percentage rendered inside. */
public final class RemoteControllerBatteryView extends View {
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF fill = new RectF();
    private final RectF terminal = new RectF();
    private final int successColor;
    private final int warningColor;
    private final int dangerColor;
    private int level = -1;

    public RemoteControllerBatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeCap(Paint.Cap.ROUND);
        outlinePaint.setStrokeJoin(Paint.Join.ROUND);
        outlinePaint.setColor(0x66FFFFFF);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xF2FFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        successColor = context.getColor(R.color.of_hud_success);
        warningColor = context.getColor(R.color.of_hud_warning);
        dangerColor = context.getColor(R.color.of_hud_danger);
        setContentDescription(context.getString(R.string.rc_battery_unknown));
    }

    public void setLevel(int percent) {
        int clamped = Math.max(-1, Math.min(100, percent));
        if (clamped == level) return;
        level = clamped;
        setContentDescription(level >= 0
                ? getContext().getString(R.string.rc_battery_percent, level)
                : getContext().getString(R.string.rc_battery_unknown));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float stroke = Math.max(1.5f, height * 0.075f);
        float terminalWidth = Math.max(2.5f, width * 0.065f);
        float rightGap = terminalWidth + stroke * 1.6f;

        outlinePaint.setStrokeWidth(stroke);
        body.set(stroke, stroke, width - rightGap, height - stroke);
        float radius = height * 0.18f;
        canvas.drawRoundRect(body, radius, radius, outlinePaint);

        terminal.set(
            body.right + stroke * 1.15f,
            height * 0.34f,
            width - stroke * 0.25f,
            height * 0.66f
        );
        fillPaint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(terminal, stroke, stroke, fillPaint);

        if (level >= 0) {
            int color = level > 50 ? successColor : level > 20 ? warningColor : dangerColor;
            fillPaint.setColor(color);
            float inset = stroke * 1.7f;
            float availableWidth = body.width() - inset * 2f;
            fill.set(
                body.left + inset,
                body.top + inset,
                body.left + inset + availableWidth * level / 100f,
                body.bottom - inset
            );
            if (fill.width() > 0f) {
                canvas.drawRoundRect(fill, radius * 0.55f, radius * 0.55f, fillPaint);
            }
        }

        textPaint.setTextSize(height * 0.40f);
        String text = level >= 0 ? String.valueOf(level) : "--";
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float centreX = (body.left + body.right) / 2f;
        float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, centreX, baseline, textPaint);
    }
}
