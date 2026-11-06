package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashSet;
import java.util.Set;

@SuppressLint("ViewConstructor")
final class ControlSlider extends View {
    interface ValueChanged {
        void changed(int value);
    }
    interface Interaction {
        boolean isEditing();
        boolean isControllerNavigationEnabled();
        void startMove(ControlSlider slider);
        void remove(ControlSlider slider);
        void finishEditing();
        void unavailable(ControlSlider slider, String reason);
        void valueCommitted(ControlSlider slider);
    }
    void setSideButton(boolean present) {
        hasSideButton = present;
    }
    private int dp(float value) {
        return ControlStyle.dp(getContext(), value);
    }

    private final String title;
    private final String subtitle;
    private final int fillColor;
    private final Interaction interaction;
    private final int minimum;
    private final ValueChanged listener;
    private final ValueChanged previewListener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final Path clip = new Path();
    private int value = 100;
    private boolean dragging;
    private boolean available = true;
    private boolean horizontal;
    private boolean mixed;
    private int mixedMain;
    private int mixedUpper;
    private String unavailableDescription = "";
    private String contextLabel = "";
    private boolean hasSideButton;
    private final Set<Integer> controllerAdjustmentKeys = new HashSet<>();
    private float touchStartX, touchStartY;
    private int touchStartValue;
    private boolean touchMoved, longPressTriggered, badgeCaptured, previewActive, trackGesture,
            parentScrollInterceptDisallowed;
    private Runnable longPressAction;

    ControlSlider(Context context, Interaction interaction, String title, String subtitle,
            int fillColor, int minimum, ValueChanged listener) {
        this(context, interaction, title, subtitle, fillColor, minimum, listener, null);
    }

    ControlSlider(Context context, Interaction interaction, String title, String subtitle,
            int fillColor, int minimum, ValueChanged listener, ValueChanged previewListener) {
        super(context);
        this.title = title;
        this.subtitle = subtitle;
        this.fillColor = fillColor;
        this.interaction = interaction;
        this.minimum = minimum;
        this.listener = listener;
        this.previewListener = previewListener;
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(title + (subtitle.isEmpty() ? "" : " " + subtitle));
    }

    void setValue(int next) {
        if (dragging)
            return;
        int bounded = Math.max(minimum, Math.min(100, next));
        if (value != bounded) {
            value = bounded;
            invalidate();
        }
    }

    void setHorizontal(boolean next) {
        if (horizontal != next) {
            horizontal = next;
            invalidate();
        }
    }
    void setContextLabel(String next) {
        String label = next == null ? "" : next;
        if (!label.equals(contextLabel)) {
            contextLabel = label;
            invalidate();
        }
    }
    void restoreDescription() {
        setContentDescription(title + (subtitle.isEmpty() ? "" : " " + subtitle));
    }

    void setMixedValues(int mainValue, int upperValue) {
        if (dragging)
            return;
        int boundedMain = Math.max(0, Math.min(100, mainValue));
        int boundedUpper = Math.max(0, Math.min(100, upperValue));
        boolean nextMixed = boundedMain != boundedUpper;
        if (mixed != nextMixed || mixedMain != boundedMain || mixedUpper != boundedUpper) {
            mixed = nextMixed;
            mixedMain = boundedMain;
            mixedUpper = boundedUpper;
            invalidate();
        }
    }

    void setAvailable(boolean next, String explanation) {
        available = next;
        unavailableDescription = ControlStyle.label(explanation, "Control unavailable");
        // Keep a tap target so an unavailable control explains what is missing.
        setEnabled(true);
        if (!next && (dragging || parentScrollInterceptDisallowed)) {
            dragging = false;
            releaseParentScrollInterception();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float fontScale = Math.max(
                .85f, Math.min(1.15f, getResources().getDisplayMetrics().scaledDensity / density));
        float width = getWidth() / density, height = getHeight() / density;
        canvas.save();
        canvas.scale(density, density);
        paint.setStyle(Paint.Style.FILL);
        if (horizontal)
            drawHorizontal(canvas, width, height, fontScale);
        else
            drawVertical(canvas, width, height, fontScale);
        canvas.restore();
    }

    private int fillColor() {
        return fillColor;
    }

    private void drawVertical(Canvas canvas, float width, float height, float fontScale) {
        paint.setTypeface(MEDIUM);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(11 * fontScale);
        paint.setColor(TEXT);
        float labelCenter = hasSideButton ? Math.max(15, (width - 40) / 2) : width / 2;
        canvas.drawText(title, labelCenter, 19, paint);
        if (!subtitle.isEmpty()) {
            paint.setTypeface(REGULAR);
            paint.setTextSize(10 * fontScale);
            paint.setColor(MUTED);
            canvas.drawText(subtitle, labelCenter, 32, paint);
        }
        float trackWidth = Math.min(42, width - 20);
        track.set((width - trackWidth) / 2, hasSideButton ? 46 : 43, (width + trackWidth) / 2,
                height - 35);
        paint.setColor(0xff38363d);
        canvas.drawRoundRect(track, 12, 12, paint);
        clip.reset();
        clip.addRoundRect(track, 12, 12, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (available && value > 0) {
            paint.setColor(fillColor());
            canvas.drawRect(track.left, track.bottom - track.height() * value / 100f, track.right,
                    track.bottom, paint);
        }
        canvas.restore();
        paint.setTypeface(MEDIUM);
        paint.setColor(available ? TEXT : MUTED);
        if (available) {
            paint.setTextSize((mixed ? 11.5f : 15.5f) * fontScale);
            canvas.drawText(mixed ? mixedMain + "/" + mixedUpper + "%" : value + "%", width / 2,
                    height - 11, paint);
        } else {
            paint.setTypeface(REGULAR);
            paint.setTextSize(9 * fontScale);
            canvas.drawText(fit(unavailableDescription, width - 8), width / 2, height - 11, paint);
        }
    }

    private void drawHorizontal(Canvas canvas, float width, float height, float fontScale) {
        float middle = height / 2;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(MEDIUM);
        paint.setTextSize(10.5f * fontScale);
        paint.setColor(TEXT);
        canvas.drawText(title, 9, middle + (subtitle.isEmpty() ? 3 : -3), paint);
        if (!subtitle.isEmpty()) {
            paint.setTypeface(REGULAR);
            paint.setTextSize(9.5f * fontScale);
            paint.setColor(MUTED);
            canvas.drawText(subtitle, 9, middle + 9, paint);
        }
        track.set(62, middle - 12.5f, Math.max(63, width - 10 - (hasSideButton ? 40 : 0)),
                middle + 12.5f);
        paint.setColor(0xff38363d);
        canvas.drawRoundRect(track, 12.5f, 12.5f, paint);
        float fillRight = track.left + track.width() * value / 100f;
        clip.reset();
        clip.addRoundRect(track, 12.5f, 12.5f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (available && value > 0) {
            paint.setColor(fillColor());
            canvas.drawRect(track.left, track.top, fillRight, track.bottom, paint);
        }
        canvas.restore();
        paint.setTypeface(REGULAR);
        paint.setTextSize(9.5f * fontScale);
        if (!available) {
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(MUTED);
            canvas.drawText(fit(ControlStyle.label(unavailableDescription, "No active audio"),
                                    track.width() - 16),
                    track.left + 8, middle + 3.5f, paint);
            return;
        }
        String reading = mixed ? mixedMain + "/" + mixedUpper + "%" : value + "%";
        paint.setTypeface(MEDIUM);
        paint.setTextAlign(Paint.Align.RIGHT);
        drawFlatBarText(canvas, reading, track.right - 8, middle + 3.5f, fillRight);
        if (!contextLabel.isEmpty()) {
            float readingWidth = paint.measureText(reading);
            paint.setTypeface(REGULAR);
            paint.setTextSize(9 * fontScale);
            paint.setTextAlign(Paint.Align.LEFT);
            drawFlatBarText(canvas, fit(contextLabel, track.width() - readingWidth - 28),
                    track.left + 8, middle + 3.5f, fillRight);
        }
    }

    private void drawFlatBarText(
            Canvas canvas, String text, float x, float baseline, float fillRight) {
        paint.setColor(TEXT);
        canvas.drawText(text, x, baseline, paint);
        if (value > 0) {
            canvas.save();
            canvas.clipRect(track.left, track.top, fillRight, track.bottom);
            paint.setColor(0xff403947);
            canvas.drawText(text, x, baseline, paint);
            canvas.restore();
        }
    }

    private String fit(String text, float width) {
        if (text == null || width <= 0)
            return "";
        if (paint.measureText(text) <= width)
            return text;
        int count = paint.breakText(text, true, Math.max(0, width - paint.measureText("…")), null);
        if (count > 0 && Character.isHighSurrogate(text.charAt(count - 1)))
            count--;
        return count == 0 ? "" : text.substring(0, count) + "…";
    }

    private boolean sliderTouchZone(float x, float y) {
        float density = getResources().getDisplayMetrics().density, px = x / density,
              py = y / density;
        if (horizontal) {
            float inset = Math.min(10, track.left);
            return px >= track.left - inset && px <= track.right + 10;
        }
        return px >= track.left - 8 && px <= track.right + 8 && py >= track.top - 8
                && py <= track.bottom + 8;
    }

    private void setParentScrollInterception(boolean disallow) {
        if (parentScrollInterceptDisallowed == disallow)
            return;
        ViewParent parent = getParent();
        if (parent == null) {
            parentScrollInterceptDisallowed = false;
            return;
        }
        parent.requestDisallowInterceptTouchEvent(disallow);
        parentScrollInterceptDisallowed = disallow;
    }

    private void releaseParentScrollInterception() {
        setParentScrollInterception(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseParentScrollInterception();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                releaseParentScrollInterception();
                if (interaction.isEditing()
                        && ControlLogic.inRemoveBadge(event.getX(), event.getY(), getWidth(),
                                getHeight(), dp(REMOVE_BADGE_TARGET_DP))) {
                    badgeCaptured = true;
                    return true;
                }
                badgeCaptured = false;
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartValue = value;
                touchMoved = false;
                longPressTriggered = false;
                previewActive = false;
                trackGesture = sliderTouchZone(event.getX(), event.getY());
                setParentScrollInterception(!interaction.isEditing() && available && trackGesture);
                longPressAction = null;
                if (interaction.isEditing()) {
                    longPressAction = () -> {
                        if (!touchMoved) {
                            longPressTriggered = true;
                            dragging = false;
                            interaction.startMove(this);
                        }
                    };
                    postDelayed(longPressAction, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (badgeCaptured)
                    return true;
                if (!touchMoved
                        && Math.hypot(event.getX() - touchStartX, event.getY() - touchStartY)
                                > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    touchMoved = true;
                    if (longPressAction != null)
                        removeCallbacks(longPressAction);
                }
                if (interaction.isEditing()) {
                    releaseParentScrollInterception();
                    return true;
                }
                if (available && trackGesture && touchMoved) {
                    dragging = true;
                    setParentScrollInterception(true);
                    updateFromTouch(event.getX(), event.getY(), true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                releaseParentScrollInterception();
                if (badgeCaptured) {
                    boolean remove = ControlLogic.shouldRemoveBadge(true,
                            ControlLogic.inRemoveBadge(event.getX(), event.getY(), getWidth(),
                                    getHeight(), dp(REMOVE_BADGE_TARGET_DP)));
                    badgeCaptured = false;
                    if (remove) {
                        interaction.remove(this);
                    }
                    return true;
                }
                if (longPressAction != null)
                    removeCallbacks(longPressAction);
                if (interaction.isEditing()) {
                    if (!longPressTriggered)
                        interaction.finishEditing();
                    return true;
                }
                if (longPressTriggered)
                    return true;
                if (!trackGesture)
                    return true;
                if (!available) {
                    interaction.unavailable(this, unavailableDescription);
                    return true;
                }
                dragging = false;
                updateFromTouch(event.getX(), event.getY(), false);
                interaction.valueCommitted(this);
                performClick();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseParentScrollInterception();
                badgeCaptured = false;
                if (longPressAction != null)
                    removeCallbacks(longPressAction);
                if (previewActive) {
                    value = touchStartValue;
                    mixed = false;
                    listener.changed(value);
                    previewActive = false;
                    invalidate();
                }
                dragging = false;
                trackGesture = false;
                touchMoved = false;
                longPressTriggered = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(float x, float y) {
        updateFromTouch(x, y, false);
    }

    private void updateFromTouch(float x, float y, boolean preview) {
        if (track.height() <= 0 || track.width() <= 0)
            return;
        float density = getResources().getDisplayMetrics().density;
        float fraction = horizontal ? (x / density - track.left) / track.width()
                                    : 1 - (y / density - track.top) / track.height();
        changeValue(Math.round(100 * fraction), preview);
    }

    private void changeValue(int next) {
        changeValue(next, false);
    }

    private void changeValue(int next, boolean preview) {
        int bounded = Math.max(minimum, Math.min(100, next));
        if (bounded == value && !mixed) {
            if (!preview && previewListener != null && previewActive) {
                listener.changed(value);
                previewActive = false;
            }
            return;
        }
        if (bounded / 10 != value / 10)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        value = bounded;
        mixed = false;
        if (preview && previewListener != null) {
            previewActive = true;
            previewListener.changed(value);
        } else {
            listener.changed(value);
            previewActive = false;
        }
        invalidate();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (interaction.isControllerNavigationEnabled()) {
            int delta = 0;
            if (horizontal) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                    delta = -5;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    delta = 5;
            } else {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                    delta = -5;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_UP)
                    delta = 5;
            }
            if (delta != 0) {
                controllerAdjustmentKeys.add(keyCode);
                if (available)
                    changeValue(value + delta);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MOVE_HOME || keyCode == KeyEvent.KEYCODE_MOVE_END) {
                controllerAdjustmentKeys.add(keyCode);
                if (available)
                    changeValue(keyCode == KeyEvent.KEYCODE_MOVE_HOME ? minimum : 100);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (available) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                changeValue(value + 5);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                changeValue(value - 5);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
                changeValue(minimum);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MOVE_END) {
                changeValue(100);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (interaction.isControllerNavigationEnabled() && controllerAdjustmentKeys.remove(keyCode))
            return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setEnabled(available);
        String reading = mixed
                ? "mixed, main " + mixedMain + " percent, external " + mixedUpper + " percent"
                : value + " percent";
        info.setContentDescription(available
                        ? title + (subtitle.isEmpty() ? "" : " " + subtitle) + ", " + reading
                        : unavailableDescription);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minimum, 100, value));
        if (available) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (available) {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                    && arguments != null) {
                float progress = arguments.getFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
                if (Float.isNaN(progress) || Float.isInfinite(progress))
                    return false;
                changeValue(Math.round(progress));
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                changeValue(value + 5);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                changeValue(value - 5);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
