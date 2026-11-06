package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

@SuppressLint("ViewConstructor")
final class ControlIconView extends View {
    private final ControlIconDrawable glyph;
    private final Drawable installedAppIcon;
    private final ControlIcon icon;
    private int size;
    private float screenshotHoldProgress;
    private String screenshotTarget = ScreenshotTargetPolicy.MAIN;
    ControlIconView(Context context, ControlIcon icon, int color, int size) {
        this(context, icon, color, size, null);
    }
    ControlIconView(
            Context context, ControlIcon icon, int color, int size, Drawable installedAppIcon) {
        super(context);
        this.icon = icon;
        this.installedAppIcon = installedAppIcon;
        this.size = dp(installedAppIcon == null ? size : 24);
        glyph = installedAppIcon == null ? new ControlIconDrawable(icon, color, this.size) : null;
    }
    void setIconSize(int next) {
        size = dp(next);
        invalidate();
    }
    void setScreenshotTarget(String target) {
        String next = ScreenshotTargetPolicy.normalize(target);
        if (!next.equals(screenshotTarget)) {
            screenshotTarget = next;
            invalidate();
        }
    }
    void setScreenshotHoldProgress(float progress) {
        float next = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(next - screenshotHoldProgress) > .001f) {
            screenshotHoldProgress = next;
            invalidate();
        }
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (installedAppIcon != null) {
            int intrinsicWidth = installedAppIcon.getIntrinsicWidth(),
                intrinsicHeight = installedAppIcon.getIntrinsicHeight();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                intrinsicWidth = size;
                intrinsicHeight = size;
            }
            float scale = Math.min(size / (float) Math.max(1, intrinsicWidth),
                    size / (float) Math.max(1, intrinsicHeight));
            int width = Math.round(Math.max(1, intrinsicWidth) * scale),
                height = Math.round(Math.max(1, intrinsicHeight) * scale);
            int left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
            installedAppIcon.setBounds(left, top, left + width, top + height);
            installedAppIcon.draw(canvas);
        } else if (icon != ControlIcon.SCREENSHOT) {
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            glyph.setBounds(left, top, left + size, top + size);
            glyph.draw(canvas);
        }
        if (icon == ControlIcon.SCREENSHOT) {
            Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
            float barWidth = dp(14), barHeight = dp(4), barLeft = (getWidth() - barWidth) / 2f,
                  barRight = barLeft + barWidth;
            float centerY = getHeight() / 2f, topY = centerY - dp(3.5f),
                  bottomY = centerY + dp(3.5f);
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setStrokeWidth(dp(1.4f));
            overlay.setColor(0xff39363f);
            canvas.drawCircle(getWidth() / 2f, centerY,
                    Math.min(getWidth(), getHeight()) / 2f - dp(1.5f), overlay);
            boolean external = screenshotTarget.equals(ScreenshotTargetPolicy.EXTERNAL)
                    || screenshotTarget.equals(ScreenshotTargetPolicy.BOTH);
            boolean main = screenshotTarget.equals(ScreenshotTargetPolicy.MAIN)
                    || screenshotTarget.equals(ScreenshotTargetPolicy.BOTH);
            overlay.setStrokeWidth(Math.max(1f, dp(.75f)));
            overlay.setStyle(external ? Paint.Style.FILL : Paint.Style.STROKE);
            overlay.setColor(external ? VIOLET : MUTED);
            canvas.drawRoundRect(barLeft, topY - barHeight / 2, barRight, topY + barHeight / 2,
                    dp(.8f), dp(.8f), overlay);
            overlay.setStyle(main ? Paint.Style.FILL : Paint.Style.STROKE);
            overlay.setColor(main ? AQUA : MUTED);
            canvas.drawRoundRect(barLeft, bottomY - barHeight / 2, barRight,
                    bottomY + barHeight / 2, dp(.8f), dp(.8f), overlay);
            if (screenshotHoldProgress > 0f) {
                float radius = getHeight() / 2f - dp(1.5f), indicatorCenter = getWidth() / 2f;
                if (radius > 0) {
                    RectF ring = new RectF(indicatorCenter - radius, getHeight() / 2f - radius,
                            indicatorCenter + radius, getHeight() / 2f + radius);
                    overlay.setStyle(Paint.Style.STROKE);
                    overlay.setStrokeWidth(dp(1.4f));
                    overlay.setColor(0x557f7886);
                    canvas.drawArc(ring, -90, 360, false, overlay);
                    overlay.setColor(BLUE);
                    canvas.drawArc(ring, -90, 360 * screenshotHoldProgress, false, overlay);
                }
            }
        }
    }
    private int dp(float value) {
        return ControlStyle.dp(getContext(), value);
    }
}
