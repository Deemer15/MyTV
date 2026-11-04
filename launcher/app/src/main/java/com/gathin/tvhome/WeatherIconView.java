package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** The weather widget's condition icon, drawn on a 24 by 24 grid: no image assets. */
public class WeatherIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int kind = WeatherClient.CLEAR;
    private boolean night;

    public WeatherIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int kind, boolean night) {
        if (this.kind == kind && this.night == night) return;
        this.kind = kind;
        this.night = night;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvasRef = canvas;
        float s = Math.min(getWidth(), getHeight()) / 24f;
        canvas.save();
        canvas.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        canvas.scale(s, s);
        switch (kind) {
            case WeatherClient.CLEAR:
                if (night) moon(12, 12, 7); else sun(12, 12, 5, 1f);
                break;
            case WeatherClient.PARTLY:
                if (night) moon(9, 9, 5.5f); else sun(9, 9, 4, 0.9f);
                cloud(2f, 4f);
                break;
            case WeatherClient.FOG:
                cloud(0f, -3f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setColor(0xCCFFFFFF);
                canvas.drawLine(5, 17, 19, 17, paint);
                canvas.drawLine(7, 21, 17, 21, paint);
                break;
            case WeatherClient.RAIN:
                cloud(0f, -3f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setColor(0xFF6EC1FF);
                canvas.drawLine(8, 16, 6.5f, 20, paint);
                canvas.drawLine(12.5f, 16, 11, 20, paint);
                canvas.drawLine(17, 16, 15.5f, 20, paint);
                break;
            case WeatherClient.SNOW:
                cloud(0f, -3f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(8, 18, 1.5f, paint);
                canvas.drawCircle(12.5f, 20.5f, 1.5f, paint);
                canvas.drawCircle(17, 18, 1.5f, paint);
                break;
            case WeatherClient.STORM:
                cloud(0f, -3f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFD54A);
                path.reset();
                path.moveTo(13.5f, 14);
                path.lineTo(9, 19.5f);
                path.lineTo(12, 19.5f);
                path.lineTo(10.5f, 24);
                path.lineTo(16, 17.5f);
                path.lineTo(12.8f, 17.5f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            default:
                cloud(0f, 0f);
                break;
        }
        canvas.restore();
        canvasRef = null;
    }

    private Canvas canvasRef;


    // The helpers draw on the canvas of the current onDraw, kept in a field so they stay short.
    private void sun(float cx, float cy, float r, float alpha) {
        Canvas c = canvasRef;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFC83D);
        c.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * alpha);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = cx + (float) Math.cos(a) * (r + 2f), y1 = cy + (float) Math.sin(a) * (r + 2f);
            float x2 = cx + (float) Math.cos(a) * (r + 4f), y2 = cy + (float) Math.sin(a) * (r + 4f);
            c.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private void moon(float cx, float cy, float r) {
        Canvas c = canvasRef;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE8EAF6);
        // A disc minus an offset disc: a crescent that stays see-through over any wallpaper.
        Path bite = new Path();
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        bite.addCircle(cx + r * 0.5f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW);
        path.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(path, paint);
    }

    private void cloud(float dx, float dy) {
        Canvas c = canvasRef;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE6EBF2);
        c.drawCircle(9 + dx, 15 + dy, 4.5f, paint);
        c.drawCircle(14 + dx, 12.5f + dy, 5.5f, paint);
        c.drawCircle(18.5f + dx, 15.5f + dy, 4f, paint);
        c.drawRect(9 + dx, 15 + dy, 18.5f + dx, 19.5f + dy, paint);
    }
}
