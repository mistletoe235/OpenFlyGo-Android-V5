package edu.playground.djivln.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import edu.playground.djivln.R;

/** Four-bar air-link indicator; colour thresholds follow DJI KeySignalQuality. */
public final class SignalQualityView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private int level = -1;

    public SignalQualityView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setContentDescription(context.getString(R.string.link_quality_unknown));
    }

    public void setLevel(int percent) {
        int clamped = Math.max(-1, Math.min(100, percent));
        if (clamped == level) return;
        level = clamped;
        setContentDescription(level >= 0
                ? getContext().getString(R.string.link_quality_percent, level)
                : getContext().getString(R.string.link_quality_unknown));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float gap = width * 0.08f;
        float barWidth = (width - gap * 5f) / 4f;
        float bottom = height * 0.86f;
        int activeBars = activeBarCount(level);
        int activeColor = level < 40 ? 0xFFFF8080 : level <= 60 ? 0xFFF2A33C : 0xFF82E6B4;
        for (int i = 0; i < 4; i++) {
            float barHeight = height * (0.24f + i * 0.17f);
            float left = gap + i * (barWidth + gap);
            bar.set(left, bottom - barHeight, left + barWidth, bottom);
            paint.setColor(i < activeBars ? activeColor : 0x33FFFFFF);
            canvas.drawRoundRect(bar, barWidth * 0.3f, barWidth * 0.3f, paint);
        }
    }

    private static int activeBarCount(int percent) {
        if (percent < 0) return 0;
        if (percent < 40) return 1;
        if (percent <= 60) return 2;
        if (percent <= 80) return 3;
        return 4;
    }
}
