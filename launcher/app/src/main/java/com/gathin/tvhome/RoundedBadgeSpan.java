package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

final class RoundedBadgeSpan extends ReplacementSpan {
    private final int horizontalPadding;
    private final float radius;
    private final int backgroundColor;
    private final int textColor;
    private final float textSize;

    RoundedBadgeSpan(Context context, boolean yellow) {
        float density = context.getResources().getDisplayMetrics().density;
        horizontalPadding = Math.round(3 * density);
        radius = 2 * density;
        textSize = 9 * density;
        backgroundColor = yellow ? 0xFFF5C518 : Color.WHITE;
        textColor = 0xFF111111;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        float savedSize = paint.getTextSize();
        paint.setTextSize(textSize);
        if (fm != null) {
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            fm.ascent = metrics.ascent;
            fm.descent = metrics.descent;
            fm.top = metrics.top;
            fm.bottom = metrics.bottom;
            fm.leading = metrics.leading;
        }
        int size = Math.round(paint.measureText(text, start, end)) + horizontalPadding * 2;
        paint.setTextSize(savedSize);
        return size;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                     int bottom, Paint paint) {
        float savedSize = paint.getTextSize();
        paint.setTextSize(textSize);
        float width = paint.measureText(text, start, end) + horizontalPadding * 2;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float topY = y + fm.ascent;
        float bottomY = y + fm.descent;
        int savedColor = paint.getColor();
        paint.setColor(backgroundColor);
        canvas.drawRoundRect(new RectF(x, topY, x + width, bottomY), radius, radius, paint);
        paint.setColor(textColor);
        canvas.drawText(text, start, end, x + horizontalPadding, y, paint);
        paint.setColor(savedColor);
        paint.setTextSize(savedSize);
    }
}
