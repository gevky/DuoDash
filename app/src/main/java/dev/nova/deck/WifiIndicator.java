package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class WifiIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean connected;
    WifiIndicator(Context context) {
        super(context);
        setFocusable(false);
        setClickable(false);
        update(false);
    }
    void update(boolean connected) {
        this.connected = connected;
        String state = connected ? "Wi-Fi connected" : "Wi-Fi disconnected";
        setContentDescription(state);
        setTooltipText(state);
        invalidate();
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = dp(20);
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(connected ? AQUA : MUTED);
        paint.setStyle(Paint.Style.FILL);
        Path outer = new Path();
        outer.moveTo(1.5f, 8.5f);
        outer.quadTo(12, -1, 22.5f, 8.5f);
        outer.lineTo(19.1f, 11.8f);
        outer.quadTo(12, 5.2f, 4.9f, 11.8f);
        outer.close();
        canvas.drawPath(outer, paint);
        Path inner = new Path();
        inner.moveTo(6.2f, 13.4f);
        inner.quadTo(12, 8.1f, 17.8f, 13.4f);
        inner.lineTo(14.9f, 16.2f);
        inner.quadTo(12, 13.5f, 9.1f, 16.2f);
        inner.close();
        canvas.drawPath(inner, paint);
        canvas.drawCircle(12, 20.2f, 1.7f, paint);
        if (!connected) {
            paint.setColor(0xff716f76);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(5, 5, 19, 19, paint);
        }
        canvas.restore();
    }
    private int dp(float value) {
        return ControlStyle.dp(getContext(), value);
    }
}
