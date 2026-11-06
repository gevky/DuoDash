package dev.nova.deck;

import android.graphics.*;
import android.graphics.drawable.Drawable;

final class ControlIconDrawable extends Drawable {
    private final ControlIcon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    ControlIconDrawable(ControlIcon icon, int color, int size) {
        this.icon = icon;
        this.size = size;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override
    public int getIntrinsicWidth() {
        return size;
    }
    @Override
    public int getIntrinsicHeight() {
        return size;
    }
    @Override
    public void draw(Canvas c) {
        Rect bounds = getBounds();
        c.save();
        c.translate(bounds.left, bounds.top);
        c.scale(bounds.width() / 24f, bounds.height() / 24f);
        switch (icon) {
            case DUAL:
                c.drawRoundRect(3, 2, 21, 10, 2, 2, paint);
                c.drawRoundRect(3, 14, 21, 22, 2, 2, paint);
                c.drawLine(8, 6, 16, 6, paint);
                c.drawLine(8, 18, 16, 18, paint);
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, paint);
                c.drawLine(18, 6, 6, 18, paint);
                break;
            case CHEVRON_DOWN:
                path(c, 5, 9, 12, 16, 19, 9);
                break;
            case BATTERY:
                c.drawRoundRect(2, 6, 19.5f, 18, 2, 2, paint);
                c.drawLine(22, 10, 22, 14, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(5, 9, 16.5f, 15, 1, 1, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case SCREEN:
                c.drawRoundRect(3, 4, 21, 17, 2, 2, paint);
                c.drawLine(8, 21, 16, 21, paint);
                c.drawLine(12, 17, 12, 21, paint);
                break;
            case SWAP:
                path(c, 4, 7, 19, 7, 15, 3);
                c.drawLine(19, 7, 15, 11, paint);
                path(c, 20, 17, 5, 17, 9, 21);
                c.drawLine(5, 17, 9, 13, paint);
                break;
            case UP:
                c.drawRoundRect(3, 13, 21, 21, 2, 2, paint);
                c.drawLine(12, 15, 12, 3, paint);
                path(c, 7, 8, 12, 3, 17, 8);
                break;
            case DOWN:
                c.drawRoundRect(3, 3, 21, 11, 2, 2, paint);
                c.drawLine(12, 9, 12, 21, paint);
                path(c, 7, 16, 12, 21, 17, 16);
                break;
            case RESTORE:
                c.drawArc(4, 4, 20, 20, -100, 285, false, paint);
                path(c, 3, 6, 3, 12, 9, 12);
                c.drawLine(12, 8, 12, 12, paint);
                c.drawLine(12, 12, 15, 14, paint);
                break;
            case REFRESH:
                c.drawCircle(12, 12, 8.2f, paint);
                c.drawLine(12, 6, 12, 12, paint);
                c.drawLine(12, 12, 16, 12, paint);
                break;
            case TEMPERATURE:
                c.drawRoundRect(9, 3, 15, 16, 3, 3, paint);
                c.drawCircle(12, 17.5f, 4.3f, paint);
                c.drawLine(12, 9, 12, 17, paint);
                break;
            case MEMORY:
                c.drawRoundRect(5, 5, 19, 19, 2, 2, paint);
                c.drawRect(9, 9, 15, 15, paint);
                for (int i = 8; i <= 16; i += 4) {
                    c.drawLine(i, 2, i, 5, paint);
                    c.drawLine(i, 19, i, 22, paint);
                    c.drawLine(2, i, 5, i, paint);
                    c.drawLine(19, i, 22, i, paint);
                }
                break;
            case POWER:
                path(c, 13, 2, 5, 13, 11, 13, 10, 22, 19, 10, 13, 10, 13, 2);
                break;
            case EDIT:
                path(c, 4, 20, 9, 19, 20, 8, 16, 4, 5, 15, 4, 20);
                c.drawLine(13, 7, 17, 11, paint);
                c.drawLine(5, 15, 9, 19, paint);
                break;
            case ADD:
                c.drawLine(5, 12, 19, 12, paint);
                c.drawLine(12, 5, 12, 19, paint);
                break;
            case AUDIO_OUTPUT:
                path(c, 2, 9, 6, 9, 10, 5, 10, 19, 6, 15, 2, 15, 2, 9);
                c.drawLine(14, 12, 22, 12, paint);
                path(c, 18, 8, 22, 12, 18, 16);
                break;
            case MOUSE:
                c.drawRoundRect(6, 2, 18, 22, 6, 6, paint);
                c.drawLine(6, 9, 18, 9, paint);
                c.drawLine(12, 2, 12, 9, paint);
                break;
            case QUICK_SETTINGS:
                for (int y = 6; y <= 18; y += 6) c.drawLine(3, y, 21, y, paint);
                c.drawCircle(8, 6, 2, paint);
                c.drawCircle(16, 12, 2, paint);
                c.drawCircle(10, 18, 2, paint);
                break;
            case SETTINGS:
                c.drawCircle(12, 12, 7, paint);
                c.drawCircle(12, 12, 2.8f, paint);
                for (int angle = 0; angle < 360; angle += 45) {
                    double r = Math.toRadians(angle);
                    c.drawLine(12 + (float) Math.cos(r) * 7, 12 + (float) Math.sin(r) * 7,
                            12 + (float) Math.cos(r) * 10, 12 + (float) Math.sin(r) * 10, paint);
                }
                break;
            case WIFI:
                c.drawArc(1, 4, 23, 25, 220, 100, false, paint);
                c.drawArc(5, 8, 19, 25, 220, 100, false, paint);
                c.drawArc(9, 12, 15, 22, 220, 100, false, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 19, 1.2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case BLUETOOTH:
                path(c, 8, 7, 17, 17, 12, 22, 12, 2, 17, 7, 8, 17);
                break;
            case ROTATION:
                c.save();
                c.rotate(-27, 12, 12);
                c.drawRoundRect(7, 4, 17, 20, 2, 2, paint);
                c.restore();
                c.drawArc(1, 1, 23, 23, 200, 95, false, paint);
                path(c, 18, 1, 22, 4, 17, 5);
                c.drawArc(1, 1, 23, 23, 20, 95, false, paint);
                path(c, 6, 23, 2, 20, 7, 19);
                break;
            case DND:
                c.drawCircle(12, 12, 9, paint);
                c.drawLine(7, 12, 17, 12, paint);
                break;
            case KEYBOARD:
                c.drawRoundRect(2, 5, 22, 19, 2, 2, paint);
                for (int x = 6; x <= 18; x += 4) {
                    c.drawPoint(x, 9, paint);
                    c.drawPoint(x, 12.5f, paint);
                }
                c.drawLine(7, 16, 17, 16, paint);
                break;
            case SCREENSHOT:
                path(c, 8, 3, 3, 3, 3, 8);
                path(c, 16, 3, 21, 3, 21, 8);
                path(c, 21, 16, 21, 21, 16, 21);
                path(c, 8, 21, 3, 21, 3, 16);
                c.drawRoundRect(7, 8, 17, 17, 1.5f, 1.5f, paint);
                c.drawCircle(12, 12.5f, 2.2f, paint);
                break;
            case APPS:
                for (int x = 4; x <= 14; x += 10)
                    for (int y = 4; y <= 14; y += 10)
                        c.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, paint);
                break;
            case GAMEPAD:
                path(c, 6, 6, 18, 6, 21, 17, 18, 19, 14, 15, 10, 15, 6, 19, 3, 17, 6, 6);
                c.drawLine(6, 10, 10, 10, paint);
                c.drawLine(8, 8, 8, 12, paint);
                c.drawPoint(16, 10, paint);
                c.drawPoint(18, 12, paint);
                break;
            case VOLUME:
                path(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
                c.drawArc(8, 6, 20, 18, -55, 110, false, paint);
                c.drawArc(5, 2, 24, 22, -50, 100, false, paint);
                break;
            case MUTED_VOLUME:
                path(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
                c.drawLine(16, 9, 22, 15, paint);
                c.drawLine(22, 9, 16, 15, paint);
                break;
            case FAN:
                c.drawCircle(12, 12, 2, paint);
                for (int angle = 0; angle < 360; angle += 120) {
                    c.save();
                    c.rotate(angle, 12, 12);
                    path(c, 12, 10, 10, 5, 12, 2, 16, 3, 18, 7, 14, 11);
                    c.restore();
                }
                break;
            case PERFORMANCE:
                c.drawArc(3, 4, 21, 22, 180, 180, false, paint);
                c.drawLine(5, 15, 19, 15, paint);
                c.drawLine(12, 15, 17, 7, paint);
                c.drawCircle(12, 15, 1.2f, paint);
                break;
            case APP:
                c.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
                path(c, 8, 8, 16, 12, 8, 16, 8, 8);
                break;
            case PLUG:
                c.drawLine(8, 2, 8, 7, paint);
                c.drawLine(16, 2, 16, 7, paint);
                c.drawRoundRect(5, 7, 19, 13, 2, 2, paint);
                c.drawLine(12, 13, 12, 17, paint);
                path(c, 12, 17, 8, 17, 8, 22);
                path(c, 18, 16, 15, 20, 18, 20, 16, 23);
                break;
            case TRIGGERS:
                c.drawRoundRect(2, 4, 10, 10, 2, 2, paint);
                c.drawRoundRect(14, 4, 22, 10, 2, 2, paint);
                path(c, 4, 10, 4, 16, 9, 21);
                path(c, 20, 10, 20, 16, 15, 21);
                c.drawLine(8, 13, 8, 16, paint);
                c.drawLine(16, 13, 16, 16, paint);
                break;
            case BATTERY_LIMIT:
                c.drawRoundRect(2, 6, 20, 18, 2, 2, paint);
                c.drawLine(23, 10, 23, 14, paint);
                c.drawLine(14, 9, 14, 15, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawRoundRect(5, 9, 11, 15, 1, 1, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case LIGHT:
                c.drawCircle(12, 12, 4, paint);
                for (int angle = 0; angle < 360; angle += 45) {
                    double r = Math.toRadians(angle);
                    c.drawLine(12 + (float) Math.cos(r) * 7, 12 + (float) Math.sin(r) * 7,
                            12 + (float) Math.cos(r) * 10, 12 + (float) Math.sin(r) * 10, paint);
                }
                break;
            case EYE:
                Path eye = new Path();
                eye.moveTo(2, 12);
                eye.quadTo(12, 1, 22, 12);
                eye.quadTo(12, 23, 2, 12);
                c.drawPath(eye, paint);
                c.drawCircle(12, 12, 3, paint);
                break;
            case EYE_OFF:
                Path closed = new Path();
                closed.moveTo(2, 12);
                closed.quadTo(12, 1, 22, 12);
                closed.quadTo(12, 23, 2, 12);
                c.drawPath(closed, paint);
                c.drawCircle(12, 12, 3, paint);
                c.drawLine(3, 3, 21, 21, paint);
                break;
            case LANDSCAPE:
                c.drawRoundRect(2, 5, 22, 19, 2, 2, paint);
                c.drawLine(19, 9, 19, 15, paint);
                path(c, 5, 15, 8, 11, 11, 14, 13, 12, 16, 15);
                break;
            case ASSIST:
                path(c, 12, 2, 21, 6, 20, 14, 16, 20, 12, 23, 8, 20, 4, 14, 3, 6, 12, 2);
                c.drawLine(8, 11, 16, 11, paint);
                c.drawLine(12, 7, 12, 15, paint);
                break;
            case AIRPLANE:
                path(c, 12, 2, 14, 9, 22, 14, 22, 17, 14, 14, 14, 19, 17, 21, 17, 23, 12, 21, 7, 23,
                        7, 21, 10, 19, 10, 14, 2, 17, 2, 14, 10, 9, 12, 2);
                break;
            case BATTERY_SAVER:
                c.drawRoundRect(2, 6, 20, 18, 2, 2, paint);
                c.drawLine(23, 10, 23, 14, paint);
                c.drawLine(7, 12, 15, 12, paint);
                c.drawLine(11, 8, 11, 16, paint);
                break;
            case MOON:
                Path moon = new Path();
                moon.moveTo(18, 3);
                moon.cubicTo(7, -1, 0, 8, 4, 16);
                moon.cubicTo(8, 24, 20, 23, 22, 14);
                moon.cubicTo(14, 18, 9, 10, 18, 3);
                c.drawPath(moon, paint);
                break;
            case DARK:
                c.drawCircle(12, 12, 9, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawArc(3, 3, 21, 21, 90, 180, true, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case INVERSION:
                Path drop = new Path();
                drop.moveTo(12, 2);
                drop.cubicTo(8, 8, 4, 12, 4, 16);
                drop.cubicTo(4, 24, 20, 24, 20, 16);
                drop.cubicTo(20, 12, 16, 8, 12, 2);
                c.drawPath(drop, paint);
                c.save();
                c.clipRect(12, 0, 24, 24);
                paint.setStyle(Paint.Style.FILL);
                c.drawPath(drop, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.restore();
                break;
            case CAST:
                path(c, 3, 8, 3, 4, 22, 4, 22, 20, 16, 20);
                c.drawArc(-6, 11, 12, 29, 270, 90, false, paint);
                c.drawArc(-3, 14, 9, 26, 270, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(3, 20, 1.2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case HOTSPOT:
                c.drawCircle(12, 12, 2, paint);
                c.drawArc(6, 6, 18, 18, -55, 110, false, paint);
                c.drawArc(6, 6, 18, 18, 125, 110, false, paint);
                c.drawArc(2, 2, 22, 22, -55, 110, false, paint);
                c.drawArc(2, 2, 22, 22, 125, 110, false, paint);
                break;
            case LOCATION:
                Path pin = new Path();
                pin.moveTo(12, 22);
                pin.cubicTo(6, 15, 4, 12, 4, 9);
                pin.cubicTo(4, -1, 20, -1, 20, 9);
                pin.cubicTo(20, 12, 18, 15, 12, 22);
                c.drawPath(pin, paint);
                c.drawCircle(12, 9, 2.7f, paint);
                break;
            case SHORTCUTS:
                c.drawRoundRect(3, 4, 21, 20, 3, 3, paint);
                path(c, 7, 12, 17, 12, 13, 8);
                c.drawLine(17, 12, 13, 16, paint);
                break;
        }
        c.restore();
    }
    private void path(Canvas c, float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        c.drawPath(path, paint);
    }
    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }
    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
