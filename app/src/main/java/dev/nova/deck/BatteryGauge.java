package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class BatteryGauge extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int level = -1;
    private boolean charging;
    BatteryGauge(Context context) {
        super(context);
        paint.setTextAlign(Paint.Align.CENTER);
    }
    void update(int level, boolean charging) {
        this.level = Math.max(-1, Math.min(100, level));
        this.charging = charging;
        invalidate();
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(4.5f), top = (getHeight() - dp(17)) / 2f, right = getWidth() - dp(8.5f),
              bottom = top + dp(17), cap = dp(3.5f);
        RectF body = new RectF(left, top, right, bottom);
        float radius = dp(3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff29282e);
        canvas.drawRoundRect(body, radius, radius, paint);
        float inner = dp(1.5f);
        RectF fill = new RectF(body.left + inner, body.top + inner,
                body.left + inner
                        + Math.max(0, body.width() - 2 * inner) * Math.max(0, level) / 100f,
                body.bottom - inner);
        int fillColor = level >= 0 && level < 20 ? 0xffb26036 : 0xff7e6a8d;
        if (fill.width() > 0) {
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(body.left + inner, body.top + inner, body.right - inner,
                                      body.bottom - inner),
                    dp(1.5f), dp(1.5f), Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(fillColor);
            canvas.drawRect(fill, paint);
            canvas.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xffaaa6b0);
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        RectF nub = new RectF(right, top + dp(5), right + cap, bottom - dp(5));
        canvas.drawRoundRect(nub, dp(1), dp(1), paint);
        String digits = level >= 0 ? Integer.toString(level) : "";
        paint.setTypeface(MEDIUM);
        paint.setTextSize(dp(digits.length() >= 3 ? 7.2f : 8f));
        paint.setColor(TEXT);
        float numberWidth = paint.measureText(digits), boltWidth = charging ? dp(4.5f) : 0,
              gap = charging && !digits.isEmpty() ? dp(1.2f) : 0;
        float groupWidth = numberWidth + gap + boltWidth,
              start = (body.centerX() - groupWidth / 2f);
        float baseline = body.centerY() - (paint.ascent() + paint.descent()) / 2f;
        if (!digits.isEmpty())
            canvas.drawText(digits, start + numberWidth / 2f, baseline, paint);
        if (charging) {
            float x = start + numberWidth + gap, y = body.centerY(), u = dp(1);
            Path bolt = new Path();
            bolt.moveTo(x + 4 * u, y - 6 * u);
            bolt.lineTo(x + 1.5f * u, y - 1 * u);
            bolt.lineTo(x + 4 * u, y - 1 * u);
            bolt.lineTo(x + 1 * u, y + 6 * u);
            bolt.lineTo(x + 6 * u, y - 0.5f * u);
            bolt.lineTo(x + 3.5f * u, y - 0.5f * u);
            bolt.close();
            paint.setColor(TEXT);
            canvas.drawPath(bolt, paint);
        }
    }
    private int dp(float value) {
        return ControlStyle.dp(getContext(), value);
    }
}
