package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

final class ControlEditDecoration extends Drawable {
    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float arm, inset;
    ControlEditDecoration(Context context) {
        this.context = context;
        arm = dp(11);
        inset = dp(2);
        paint.setColor(0xffe0c6ec);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setStyle(Paint.Style.STROKE);
    }
    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float l = b.left + inset, t = b.top + inset, r = b.right - inset, bt = b.bottom - inset;
        canvas.drawLine(l, t, l + arm, t, paint);
        canvas.drawLine(l, t, l, t + arm, paint);
        canvas.drawLine(r, t, r - arm, t, paint);
        canvas.drawLine(r, t, r, t + arm, paint);
        canvas.drawLine(l, bt, l + arm, bt, paint);
        canvas.drawLine(l, bt, l, bt - arm, paint);
        canvas.drawLine(r, bt, r - arm, bt, paint);
        canvas.drawLine(r, bt, r, bt - arm, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff312d36);
        float radius = dp(REMOVE_BADGE_RADIUS_DP), target = dp(REMOVE_BADGE_TARGET_DP),
              cx = b.left + ControlLogic.removeBadgeCenterX(b.width(), b.height(), target),
              cy = b.top + ControlLogic.removeBadgeCenterY(b.width(), b.height(), target);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(0xfff3d9f9);
        paint.setStrokeWidth(dp(2));
        canvas.drawLine(cx - dp(4), cy, cx + dp(4), cy, paint);
    }
    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }
    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {
        paint.setColorFilter(filter);
    }
    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
    private int dp(float value) {
        return ControlStyle.dp(context, value);
    }
}
