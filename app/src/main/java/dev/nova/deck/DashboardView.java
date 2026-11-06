package dev.nova.deck;

import static dev.nova.deck.ControlStyle.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;

/** Native dashboard; controls are selected from the same catalog as its editor. */
public final class DashboardView extends FrameLayout {
    private static final String[] BRIGHTNESS = {"both", "upper", "main"},
                                  AUDIO = {"audio_upper", "audio_main", "volume"};
    private final DashboardActions actions;
    private final ScrollView scroll;
    private final LinearLayout content, banks, brightnessBank, audioBank, metricBand;
    private final ActionGrid actionGrid;
    private final TextView addControlButton;
    private final TextView profileName, refreshRate, batteryTime, clockTime, emptyState,
            headerHeading, editHint, undoEditingButton;
    private final LinearLayout header, headerTitle, statusCluster;
    private final FrameLayout headerActionSlot;
    private final ControlIconView brandButton;
    private final LinearLayout brandTray;
    private final TextView doneEditingButton;
    private final BatteryGauge batteryGauge;
    private final LinearLayout batteryGroup;
    private final WifiIndicator wifiIndicator;
    private final Map<String, ControlSlider> faders = new LinkedHashMap<>();
    private final Map<String, BlackButton> blackButtons = new LinkedHashMap<>();
    private final Map<String, AudioMuteButton> audioMuteButtons = new LinkedHashMap<>();
    private final Map<String, ActionTile> tiles = new LinkedHashMap<>();
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final DashboardEditHistory editHistory = new DashboardEditHistory();
    private UiState state = new UiState();
    private String layoutSignature = "";
    private boolean fullScreen, horizontal = true, compact = true, modeInitialized,
                                renderedExternalConnected;
    private boolean editing, brandTrayOpen, brandTrayDismissGesture;
    private boolean controllerNavigation;
    private int fullscreenScrollViewportHeight;
    private View lastControllerFocus;
    private final Set<Integer> controllerActivationKeys = new HashSet<>(),
                               controllerDismissKeys = new HashSet<>(),
                               controllerTrayDismissKeys = new HashSet<>();
    private final IdentityHashMap<View, Boolean> priorFocusableInTouchMode =
            new IdentityHashMap<>();
    private int actionColumns = 4;
    private boolean pendingRebuild, dragInProgress;
    private String editingSection = "", editingKey = "";
    private String renderedLayoutKey = "";

    private final ControlSlider.Interaction sliderInteraction = new ControlSlider.Interaction() {
        public boolean isEditing() {
            return editing;
        }
        public boolean isControllerNavigationEnabled() {
            return controllerNavigation;
        }
        public void startMove(ControlSlider slider) {
            startMoveDrag(slider, idOf(slider));
        }
        public void remove(ControlSlider slider) {
            actions.removeControl(editingKey, idOf(slider));
            hideRemoved(slider);
            refreshEditingLayout(state);
        }
        public void finishEditing() {
            finishMove();
        }
        public void unavailable(ControlSlider slider, String reason) {
            if (!state.bridgeReady && requiresBridge(idOf(slider)))
                actions.connection();
            else
                explain(reason);
        }
        public void valueCommitted(ControlSlider slider) {
            if ("volume".equals(idOf(slider)))
                slider.setValue(state.volume);
        }
    };

    public DashboardView(Context context, DashboardActions actions) {
        super(context);
        this.actions = actions;
        setClickable(true);
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(scroll,
                new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        content = column();
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        scroll.addView(content,
                new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        header = row();
        content.addView(header, lp(-1, 44));
        scroll.setOnScrollChangeListener(
                (View view, int scrollX, int scrollY, int oldScrollX, int oldScrollY) -> {
                    cancelScreenshotHold();
                    if (editing)
                        updateEditHeaderPosition(scrollY);
                });
        brandButton = new ControlIconView(context, ControlIcon.DUAL, AQUA, 25);
        brandButton.setContentDescription("Dashboard menu");
        brandButton.setTooltipText("Dashboard menu");
        brandButton.setFocusable(true);
        brandButton.setClickable(true);
        brandButton.setBackground(
                new InsetDrawable(ripple(round(SURFACE, 10, 0), AQUA), dp(4), dp(4), dp(4), dp(4)));
        brandButton.setOnClickListener(v -> {
            if (!editing)
                setBrandTrayOpen(!brandTrayOpen);
        });
        header.addView(brandButton, lp(40, 44));
        headerTitle = row();
        headerTitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1);
        titleParams.leftMargin = dp(4);
        header.addView(headerTitle, titleParams);
        headerHeading = text("DuoDash", 18, TEXT, true);
        headerHeading.setLetterSpacing(-.025f);
        headerTitle.addView(headerHeading, lp(-2, 44));
        profileName = text("New output", 8.4f, MUTED, false);
        profileName.setPadding(dp(6), 0, 0, 0);
        profileName.setFocusable(false);
        profileName.setClickable(false);
        headerTitle.addView(profileName, new LinearLayout.LayoutParams(0, dp(44), 1));
        editHint = text("Tap left side to shrink, right side to grow\nHold and drag to move", 11,
                MUTED, true);
        editHint.setSingleLine(false);
        editHint.setMaxLines(2);
        editHint.setVisibility(GONE);
        headerTitle.addView(editHint, new LinearLayout.LayoutParams(-1, dp(44)));
        statusCluster = row();
        statusCluster.setGravity(Gravity.CENTER_VERTICAL);
        statusCluster.setPadding(dp(4), 0, dp(4), 0);
        statusCluster.setBackground(round(SURFACE, 8, 0));
        header.addView(statusCluster, lp(-2, 44));
        refreshRate = text("— Hz", 12, MUTED, true);
        refreshRate.setGravity(Gravity.CENTER);
        refreshRate.setPadding(dp(8), 0, dp(8), 0);
        refreshRate.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff),
                new ColorDrawable(Color.TRANSPARENT), round(Color.WHITE, 18, 0)));
        refreshRate.setOnClickListener(v -> {
            if (state.bridgeReady)
                actions.control("refresh_toggle");
            else
                actions.connection();
        });
        refreshRate.setFocusable(true);
        LinearLayout.LayoutParams refreshParams = lp(68, 44);
        refreshParams.rightMargin = dp(4);
        statusCluster.addView(refreshRate, refreshParams);
        wifiIndicator = new WifiIndicator(context);
        statusCluster.addView(wifiIndicator, lp(32, 36));
        batteryGroup = row();
        batteryGroup.setGravity(Gravity.CENTER_VERTICAL);
        batteryGroup.setPadding(dp(2), 0, dp(2), 0);
        statusCluster.addView(batteryGroup, lp(-2, 36));
        batteryGauge = new BatteryGauge(context);
        batteryGroup.addView(batteryGauge, lp(40, 32));
        batteryTime = text("—", 10.5f, MUTED, true);
        batteryTime.setGravity(Gravity.CENTER);
        batteryTime.setSingleLine(true);
        batteryTime.setEllipsize(TextUtils.TruncateAt.END);
        batteryGroup.addView(batteryTime, lp(50, 32));
        clockTime = text("", 11, MUTED, true);
        clockTime.setGravity(Gravity.CENTER);
        clockTime.setSingleLine(true);
        LinearLayout.LayoutParams clockParams = lp(64, 44);
        clockParams.leftMargin = dp(4);
        statusCluster.addView(clockTime, clockParams);
        headerActionSlot = new FrameLayout(context);
        undoEditingButton = text("Undo", 11, TEXT, true);
        undoEditingButton.setGravity(Gravity.CENTER);
        undoEditingButton.setBackground(ripple(round(SURFACE, 12, 0), AQUA));
        undoEditingButton.setContentDescription("Undo last edit");
        undoEditingButton.setFocusable(true);
        undoEditingButton.setClickable(true);
        undoEditingButton.setVisibility(GONE);
        undoEditingButton.setOnClickListener(v -> undoEditing());
        headerActionSlot.addView(
                undoEditingButton, new FrameLayout.LayoutParams(dp(58), -1, Gravity.LEFT));
        doneEditingButton = text("Done", 12, AQUA, true);
        doneEditingButton.setGravity(Gravity.CENTER);
        doneEditingButton.setBackground(ripple(round(SURFACE, 12, 0), AQUA));
        doneEditingButton.setContentDescription("Done editing");
        doneEditingButton.setFocusable(true);
        doneEditingButton.setClickable(true);
        doneEditingButton.setVisibility(GONE);
        doneEditingButton.setOnClickListener(v -> finishMove());
        headerActionSlot.addView(
                doneEditingButton, new FrameLayout.LayoutParams(dp(92), -1, Gravity.RIGHT));
        header.addView(headerActionSlot, lp(0, 48));

        banks = row();
        LinearLayout.LayoutParams bankParams = lp(-1, -2);
        bankParams.topMargin = dp(4);
        content.addView(banks, bankParams);
        brightnessBank = column();
        brightnessBank.setBackground(round(SURFACE, 17, 0));
        audioBank = column();
        audioBank.setBackground(round(SURFACE, 17, 0));
        banks.addView(brightnessBank, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams audioParams = new LinearLayout.LayoutParams(0, -2, 1);
        audioParams.leftMargin = dp(8);
        banks.addView(audioBank, audioParams);
        faders.put("both",
                new ControlSlider(context, sliderInteraction, "Both", "screens", BOTH_FILL, 5,
                        actions::setBoth, actions::previewBoth));
        faders.put("upper",
                new ControlSlider(context, sliderInteraction, "External", "screen", SLIDER_FILL, 0,
                        actions::setUpper));
        faders.put("main",
                new ControlSlider(context, sliderInteraction, "Main", "screen", SLIDER_FILL, 5,
                        actions::setMain, actions::previewMain));
        faders.put("audio_upper",
                new ControlSlider(context, sliderInteraction, "External", "audio", SLIDER_FILL, 0,
                        actions::setUpperAudio));
        faders.put("audio_main",
                new ControlSlider(context, sliderInteraction, "Main", "audio", SLIDER_FILL, 0,
                        actions::setMainAudio));
        faders.put("volume",
                new ControlSlider(context, sliderInteraction, "Master", "volume", MASTER_FILL, 0,
                        actions::setVolume));
        for (String id : BRIGHTNESS) faders.get(id).setSideButton(true);
        for (String id : AUDIO) {
            faders.get(id).setSideButton(true);
            audioMuteButtons.put(id, new AudioMuteButton(context, id));
        }
        blackButtons.put("main", new BlackButton(context, "black_main", true));
        blackButtons.put("upper", new BlackButton(context, "black_upper", false));

        actionGrid = new ActionGrid(context);
        LinearLayout.LayoutParams gridParams = lp(-1, -2);
        gridParams.topMargin = dp(6);
        content.addView(actionGrid, gridParams);
        addControlButton = text("＋  Add control", 13, AQUA, true);
        addControlButton.setGravity(Gravity.CENTER);
        addControlButton.setBackground(ripple(round(ELEVATED, 10, 0), AQUA));
        addControlButton.setFocusable(true);
        addControlButton.setClickable(true);
        addControlButton.setVisibility(GONE);
        addControlButton.setOnClickListener(v -> actions.editControls());
        actionGrid.addView(addControlButton, new TileParams(1));
        metricBand = row();
        metricBand.setBackground(round(0xff1b1b1e, 14, 0));
        metricBand.setPadding(dp(4), 0, dp(4), 0);
        FrameLayout.LayoutParams metricsParams =
                new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM);
        metricsParams.leftMargin = dp(14);
        metricsParams.rightMargin = dp(14);
        metricsParams.bottomMargin = dp(12);
        addView(metricBand, metricsParams);
        for (ControlCatalog.Entry entry : ControlCatalog.entries())
            if (entry.kind.equals("metric"))
                metrics.put(entry.id, new Metric(context, entry.id));
        emptyState = text("＋  Add control", 15, AQUA, true);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setBackground(ripple(round(ELEVATED, 12, 0), AQUA));
        emptyState.setFocusable(true);
        emptyState.setClickable(true);
        emptyState.setOnClickListener(v -> actions.editControls());
        content.addView(emptyState, lp(-1, 72));

        brandTray = new LinearLayout(context);
        brandTray.setOrientation(LinearLayout.HORIZONTAL);
        brandTray.setVisibility(GONE);
        FrameLayout.LayoutParams trayParams =
                new FrameLayout.LayoutParams(dp(132), dp(44), Gravity.TOP | Gravity.LEFT);
        trayParams.leftMargin = dp(56);
        trayParams.topMargin = dp(12);
        addView(brandTray, trayParams);
        addBrandTrayAction(ControlIcon.CLOSE, "Close", actions::close);
        addBrandTrayAction(ControlIcon.EDIT, "Edit", this::beginEditing);
        addBrandTrayAction(ControlIcon.SETTINGS, "Settings", actions::setup);

        render(state);
    }

    private void addBrandTrayAction(ControlIcon icon, String title, Runnable action) {
        ControlIconView item = new ControlIconView(getContext(), icon, AQUA, 18);
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(ripple(round(Color.TRANSPARENT, 8, 0), AQUA));
        item.setContentDescription(title);
        item.setTooltipText(title);
        item.setOnClickListener(v -> {
            setBrandTrayOpen(false);
            action.run();
        });
        brandTray.addView(item, new LinearLayout.LayoutParams(0, dp(44), 1));
    }
    private void setBrandTrayOpen(boolean open) {
        boolean next = open && !editing;
        if (next == brandTrayOpen)
            return;
        brandTray.animate().cancel();
        headerTitle.animate().cancel();
        brandTrayOpen = next;
        boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
        if (brandTrayOpen) {
            brandTray.setAlpha(animate ? 0f : 1f);
            brandTray.setTranslationX(animate ? -dp(8) : 0);
            brandTray.setVisibility(VISIBLE);
            brandTray.bringToFront();
            headerTitle.setVisibility(VISIBLE);
            headerTitle.setAlpha(animate ? 1f : 0f);
            if (animate) {
                brandTray.animate().alpha(1f).translationX(0).setDuration(160).start();
                headerTitle.animate()
                        .alpha(0f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            if (brandTrayOpen)
                                headerTitle.setVisibility(INVISIBLE);
                        })
                        .start();
            } else
                headerTitle.setVisibility(INVISIBLE);
        } else {
            brandTray.setVisibility(GONE);
            brandTray.setAlpha(1f);
            brandTray.setTranslationX(0);
            headerTitle.setVisibility(VISIBLE);
            if (animate) {
                headerTitle.setAlpha(0f);
                headerTitle.animate().alpha(1f).setDuration(120).start();
            } else
                headerTitle.setAlpha(1f);
        }
        debugInteraction("tray", brandTrayOpen ? "open" : "closed");
        if (brandTrayOpen && controllerNavigation && brandTray.getChildCount() > 0)
            brandTray.getChildAt(0).requestFocus();
    }
    private void debugInteraction(String event, String value) {
        if ((getContext().getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
                != 0)
            android.util.Log.i("NovaDeckUI",
                    "event=" + event + (value == null || value.isEmpty() ? "" : " value=" + value));
    }
    private boolean containsScreenPoint(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != VISIBLE || !view.isShown())
            return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth() && rawY >= location[1]
                && rawY < location[1] + view.getHeight();
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (brandTrayDismissGesture) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                brandTrayDismissGesture = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && brandTrayOpen
                && !containsScreenPoint(brandTray, event.getRawX(), event.getRawY())
                && !containsScreenPoint(brandButton, event.getRawX(), event.getRawY())) {
            setBrandTrayOpen(false);
            brandTrayDismissGesture = true;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    public void render(UiState next) {
        boolean outputChanged =
                !renderedLayoutKey.isEmpty() && !renderedLayoutKey.equals(next.layoutKey);
        if ((outputChanged || renderedExternalConnected != next.externalConnected) && editing)
            finishMove();
        renderedExternalConnected = next.externalConnected;
        if (outputChanged)
            editHistory.clear();
        state = next;
        updateAddControlVisibility();
        if (editing && editingKey.equals(next.layoutKey)) {
            editHistory.observe(DashboardEditSnapshot.capture(next));
            updateUndoButton();
        } else if (!editing)
            editHistory.clear();
        renderedLayoutKey = next.layoutKey;
        actionColumns = ControlLogic.actionColumns(next.actionColumns);
        applyMode(next.fullScreen, next.horizontalSliders, next.compactControls);
        profileName.setText(nonempty(next.profile, "New output"));
        float actualHz = next.refreshHz;
        UiState.ControlStatus refreshMode = next.controls.get("refresh_mode");
        float selectedHz = refreshMode != null && refreshMode.available && refreshMode.value > 0
                ? refreshMode.value
                : actualHz;
        String hz = finite(selectedHz) && selectedHz > 0
                ? String.format(Locale.US, "%.0f Hz", selectedHz)
                : "— Hz";
        refreshRate.setText(hz);
        refreshRate.setContentDescription("Change display refresh rate. " + hz
                + " selected. Panel currently " + Math.round(actualHz) + " Hz.");
        refreshRate.setTooltipText(
                "Main screen: " + hz + " selected. Apps can request a lower rate.");
        batteryGauge.update(next.battery, next.batteryCharging || next.batteryFull);
        String batteryText, batteryDescription;
        boolean estimating = next.showBatteryTime && !next.batteryCharging && !next.batteryFull
                && !next.batteryPowered && next.batteryRuntimeMinutes > 0;
        if (estimating) {
            batteryText = formatRuntime(next.batteryRuntimeMinutes);
            batteryDescription = "Estimated about " + describeRuntime(next.batteryRuntimeMinutes)
                    + " remaining at current drain";
        } else {
            batteryText = "—";
            batteryDescription = next.batteryCharging || next.batteryFull || next.batteryPowered
                    ? "Runtime estimate is shown while discharging"
                    : "Battery runtime estimate unavailable";
        }
        batteryTime.setText(batteryText);
        batteryTime.setContentDescription(batteryDescription);
        batteryTime.setTooltipText(batteryDescription);
        batteryTime.setVisibility(next.showBatteryTime ? VISIBLE : GONE);
        refreshRate.setVisibility(next.showHeaderHz ? VISIBLE : GONE);
        wifiIndicator.update(next.wifiConnected);
        wifiIndicator.setVisibility(next.showHeaderWifi ? VISIBLE : GONE);
        updateClock(next.showHeaderClock);
        clockTime.setVisibility(next.showHeaderClock ? VISIBLE : GONE);
        statusCluster.setVisibility(editing ? GONE : VISIBLE);
        batteryGauge.setContentDescription(next.battery < 0 ? "Battery level unavailable"
                                                            : "Battery " + next.battery + " percent"
                                + (next.batteryCharging            ? ". Charging"
                                                : next.batteryFull ? ". Full"
                                                                   : ""));
        faders.get("main").setValue(next.main);
        faders.get("upper").setValue(next.upper);
        faders.get("both").setValue(Math.round((next.main + next.upper) / 2f));
        faders.get("both").setMixedValues(next.main, next.upper);
        faders.get("volume").setValue(next.volume);
        faders.get("audio_main").setValue(next.mainAudio);
        faders.get("audio_upper").setValue(next.upperAudio);
        faders.get("audio_main").setContextLabel(nonempty(next.mainApp, "Main app"));
        faders.get("audio_upper").setContextLabel(nonempty(next.upperApp, "External app"));
        boolean external = next.bridgeReady && next.externalConnected;
        faders.get("main").setAvailable(next.bridgeReady,
                next.bridgeReady ? "" : "Reconnect system controls to adjust the main screen");
        faders.get("volume").setAvailable(true, "");
        String displayReason = next.externalConnected
                ? "Reconnect system controls to dim the external screen"
                : "Connect an external display first";
        faders.get("upper").setAvailable(external, displayReason);
        faders.get("both").setAvailable(external, displayReason);
        UiState.ControlStatus mainBlack = next.controls.get("black_main"),
                              upperBlack = next.controls.get("black_upper");
        blackButtons.get("main").update(next.mainBlack || next.screenOffRequested,
                mainBlack == null || mainBlack.available,
                next.screenOffRequested     ? "Screen off while the external display is connected"
                        : mainBlack == null ? ""
                                            : mainBlack.detail);
        blackButtons.get("upper").update(next.upperBlack,
                external && (upperBlack == null || upperBlack.available),
                !next.externalConnected      ? "Connect an external display first"
                        : !next.bridgeReady  ? "Reconnect system controls"
                        : upperBlack == null ? ""
                                             : upperBlack.detail);
        faders.get("audio_main")
                .setAvailable(next.bridgeReady && next.mainAudioAvailable,
                        next.bridgeReady ? nonempty(next.mainAudioReason,
                                                   "No active audio on the main screen")
                                         : "Reconnect system controls to adjust app audio");
        faders.get("audio_upper")
                .setAvailable(next.bridgeReady && next.upperAudioAvailable,
                        next.bridgeReady ? nonempty(next.upperAudioReason,
                                                   "No active audio on the external screen")
                                         : "Reconnect system controls to adjust app audio");
        audioMuteButtons.get("volume").update(next.volumeMuted || next.volume == 0, true, "");
        audioMuteButtons.get("audio_main")
                .update(next.mainAudioMuted || next.mainAudio == 0,
                        next.bridgeReady && next.mainAudioAvailable,
                        nonempty(next.mainAudioReason, "Main app audio is unavailable"));
        audioMuteButtons.get("audio_upper")
                .update(next.upperAudioMuted || next.upperAudio == 0,
                        next.bridgeReady && next.upperAudioAvailable,
                        nonempty(next.upperAudioReason, "External app audio is unavailable"));
        updateMetricValues(next, actualHz);
        String signature = signature(next);
        if (!signature.equals(layoutSignature)) {
            if (editing)
                pendingRebuild = true;
            else {
                rebuild(next);
                layoutSignature = signature;
            }
        }
        for (Map.Entry<String, ActionTile> tile : tiles.entrySet()) tile.getValue().update(next);
        if (controllerNavigation)
            applyControllerFocusBehavior(this);
    }

    /** Enables keyboard focus and controller navigation while this panel owns input focus. */
    public void enableControllerNavigation(boolean enabled) {
        if (controllerNavigation == enabled) {
            if (enabled && !hasControllerFocus())
                focusControllerTarget();
            return;
        }
        controllerNavigation = enabled;
        setDescendantFocusability(enabled ? FOCUS_AFTER_DESCENDANTS : FOCUS_BEFORE_DESCENDANTS);
        if (enabled) {
            applyControllerFocusBehavior(this);
            focusControllerTarget();
            post(() -> {
                if (controllerNavigation && !hasControllerFocus())
                    focusControllerTarget();
            });
        } else {
            cancelScreenshotHold();
            controllerActivationKeys.clear();
            controllerDismissKeys.clear();
            controllerTrayDismissKeys.clear();
            View focused = findFocus();
            if (isControllerTarget(focused))
                lastControllerFocus = focused;
            clearControllerFocusBehavior(this);
            clearFocus();
        }
    }

    private void focusControllerTarget() {
        if (!controllerNavigation)
            return;
        if (isControllerTarget(lastControllerFocus) && lastControllerFocus.requestFocus())
            return;
        List<View> targets = getFocusables(FOCUS_FORWARD);
        for (View target : targets)
            if (isControllerTarget(target) && target.requestFocus()) {
                lastControllerFocus = target;
                return;
            }
    }

    private boolean hasControllerFocus() {
        return isControllerTarget(findFocus());
    }
    private boolean isControllerTarget(View view) {
        if (view == null || view == this || !view.isFocusable() || !view.isEnabled()
                || view.getVisibility() != VISIBLE || !view.isShown())
            return false;
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent())
            if (parent == this)
                return true;
        return false;
    }

    private void applyControllerFocusBehavior(View view) {
        if (view != this && view.isFocusable()) {
            if (!priorFocusableInTouchMode.containsKey(view))
                priorFocusableInTouchMode.put(view, view.isFocusableInTouchMode());
            view.setFocusableInTouchMode(true);
            view.setOnFocusChangeListener((target, focused) -> {
                if (focused && controllerNavigation)
                    lastControllerFocus = target;
                if (!focused && target instanceof ActionTile
                        && "screenshot".equals(((ActionTile) target).id)) {
                    controllerActivationKeys.clear();
                    ((ActionTile) target).cancelScreenshotGesture();
                }
                target.setForeground(
                        focused && controllerNavigation ? controllerFocusOutline() : null);
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                applyControllerFocusBehavior(group.getChildAt(i));
        }
    }

    private void clearControllerFocusBehavior(View view) {
        if (view != this && view.isFocusable()) {
            view.setOnFocusChangeListener(null);
            view.setForeground(null);
            Boolean wasFocusableInTouchMode = priorFocusableInTouchMode.remove(view);
            if (wasFocusableInTouchMode != null)
                view.setFocusableInTouchMode(wasFocusableInTouchMode);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                clearControllerFocusBehavior(group.getChildAt(i));
        }
    }

    private Drawable controllerFocusOutline() {
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(8));
        outline.setStroke(dp(2), AQUA);
        return outline;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (controllerNavigation) {
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? FOCUS_UP
                    : keyCode == KeyEvent.KEYCODE_DPAD_DOWN     ? FOCUS_DOWN
                    : keyCode == KeyEvent.KEYCODE_DPAD_LEFT     ? FOCUS_LEFT
                    : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT    ? FOCUS_RIGHT
                                                                : 0;
            if (direction != 0) {
                View focused = findFocus();
                if (focused instanceof ControlSlider && focused.dispatchKeyEvent(event))
                    return true;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (!hasControllerFocus())
                        focusControllerTarget();
                    focused = findFocus();
                    View next = focused == null ? null : switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_UP -> focused.focusSearch(FOCUS_UP);
                        case KeyEvent.KEYCODE_DPAD_DOWN -> focused.focusSearch(FOCUS_DOWN);
                        case KeyEvent.KEYCODE_DPAD_LEFT -> focused.focusSearch(FOCUS_LEFT);
                        default -> focused.focusSearch(FOCUS_RIGHT);
                    };
                    if (isControllerTarget(next))
                        next.requestFocus();
                }
                return true;
            }
        }
        if (controllerNavigation
                && (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (brandTrayOpen)
                        controllerTrayDismissKeys.add(keyCode);
                    else
                        controllerDismissKeys.add(keyCode);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean trayOwned = controllerTrayDismissKeys.remove(keyCode);
                boolean owned = controllerDismissKeys.remove(keyCode);
                if (trayOwned && !event.isCanceled())
                    setBrandTrayOpen(false);
                else if (owned && !event.isCanceled()) {
                    if (editing)
                        finishMove();
                    else
                        actions.close();
                }
                return true;
            }
            return true;
        }
        if (controllerNavigation
                && (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    controllerActivationKeys.add(keyCode);
                    View focused = findFocus();
                    if (!editing && focused instanceof ActionTile
                            && "screenshot".equals(((ActionTile) focused).id))
                        ((ActionTile) focused).beginControllerScreenshotGesture();
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean owned = controllerActivationKeys.remove(keyCode);
                View focused = findFocus();
                if (owned && !editing && focused instanceof ActionTile
                        && "screenshot".equals(((ActionTile) focused).id)) {
                    ActionTile tile = (ActionTile) focused;
                    if (event.isCanceled() || !isControllerTarget(focused))
                        tile.cancelScreenshotGesture();
                    else
                        tile.releaseControllerScreenshotGesture();
                } else if (owned && !event.isCanceled() && isControllerTarget(focused)
                        && focused.isClickable() && focused.isEnabled())
                    focused.performClick();
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applyMode(boolean full, boolean horizontalSliders, boolean compactControls) {
        if (modeInitialized && fullScreen == full && horizontal == horizontalSliders
                && compact == compactControls)
            return;
        modeInitialized = true;
        fullScreen = full;
        horizontal = horizontalSliders;
        compact = compactControls;
        setBackground(round(BACKGROUND, full ? 0 : 26, 0));
        setClipToOutline(!full);
        setElevation(full ? 0 : dp(12));
        content.setPadding(dp(14), dp(12), dp(14), dp(full ? 0 : 12));
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, full ? -1 : -2);
        scroll.setLayoutParams(scrollParams);
        updateScrollForMetricBand();
        LinearLayout.LayoutParams bankParams = (LinearLayout.LayoutParams) banks.getLayoutParams();
        bankParams.topMargin = dp(full ? 10 : 4);
        banks.setLayoutParams(bankParams);
        LinearLayout.LayoutParams gridParams =
                (LinearLayout.LayoutParams) actionGrid.getLayoutParams();
        gridParams.topMargin = dp(full ? 8 : 6);
        actionGrid.setLayoutParams(gridParams);
        for (ControlSlider fader : faders.values()) fader.setHorizontal(horizontal);
        brightnessBank.setOrientation(horizontal ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        audioBank.setOrientation(horizontal ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        brightnessBank.setPadding(0, horizontal ? dp(4) : 0, 0, horizontal ? dp(4) : 0);
        audioBank.setPadding(0, horizontal ? dp(4) : 0, 0, horizontal ? dp(4) : 0);
        layoutSignature = "";
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        fullscreenScrollViewportHeight = 0;
        if (fullScreen) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) scroll.getLayoutParams();
            int available = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop()
                    - getPaddingBottom() - params.topMargin - params.bottomMargin;
            fullscreenScrollViewportHeight = Math.max(0, available);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int availableActionGridHeight() {
        if (!fullScreen)
            return 0;
        int trailing = 0;
        boolean afterGrid = false;
        for (int index = 0; index < content.getChildCount(); index++) {
            View child = content.getChildAt(index);
            if (child == actionGrid) {
                afterGrid = true;
                continue;
            }
            if (!afterGrid || child.getVisibility() == GONE)
                continue;
            trailing += child.getMeasuredHeight() + verticalMargins(child);
        }
        int headerHeight = header.getVisibility() == GONE ? 0 : header.getMeasuredHeight();
        int banksHeight = banks.getVisibility() == GONE ? 0 : banks.getMeasuredHeight();
        return DashboardGridSizingPolicy.availableGridHeight(fullscreenScrollViewportHeight,
                content.getPaddingTop(), content.getPaddingBottom(), headerHeight,
                verticalMarginsTop(header), verticalMarginsBottom(header), banksHeight,
                banks.getVisibility() == GONE ? 0 : verticalMarginsTop(banks),
                banks.getVisibility() == GONE ? 0 : verticalMarginsBottom(banks),
                verticalMarginsTop(actionGrid), verticalMarginsBottom(actionGrid), trailing);
    }
    private int verticalMargins(View view) {
        return verticalMarginsTop(view) + verticalMarginsBottom(view);
    }
    private int verticalMarginsTop(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).topMargin
                : 0;
    }
    private int verticalMarginsBottom(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) params).bottomMargin
                : 0;
    }

    private String signature(UiState value) {
        StringBuilder signature = new StringBuilder()
                                          .append(value.externalConnected)
                                          .append('|')
                                          .append(value.controlOrder)
                                          .append('|')
                                          .append(value.hiddenControls)
                                          .append('|')
                                          .append(value.wideControls)
                                          .append('|')
                                          .append(value.smallControls)
                                          .append('|')
                                          .append(value.controlSizes)
                                          .append('|')
                                          .append(horizontal)
                                          .append('|')
                                          .append(compact)
                                          .append('|')
                                          .append(ControlLogic.actionColumns(value.actionColumns));
        for (UiState.AppShortcut app : value.pinnedApps)
            signature.append('|').append(app.id).append(':').append(app.label).append(':').append(
                    app.upper);
        for (String id : new String[] {"fan", "performance"}) {
            UiState.ControlStatus status = value.controls.get(id);
            signature.append('|').append(status == null ? "unknown"
                            : status.available          ? "available"
                                                        : "unavailable");
        }
        signature.append('|').append(finite(value.fanPercent));
        return signature.toString();
    }

    private void rebuild(UiState value) {
        Set<String> hidden = ids(value.hiddenControls), wide = ids(value.wideControls);
        List<String> order = ordered(value);
        int brightnessCount = buildBank(brightnessBank, filter(order, BRIGHTNESS), hidden);
        int audioCount = buildBank(audioBank, filter(order, AUDIO), hidden);
        banks.setVisibility(brightnessCount + audioCount > 0 ? VISIBLE : GONE);
        LinearLayout.LayoutParams audioParams =
                (LinearLayout.LayoutParams) audioBank.getLayoutParams();
        audioParams.leftMargin = brightnessCount > 0 && audioCount > 0 ? dp(8) : 0;
        audioBank.setLayoutParams(audioParams);
        actionGrid.removeAllViews();
        tiles.clear();
        for (String id : order) {
            ControlCatalog.Entry entry = ControlCatalog.find(id);
            UiState.AppShortcut app = app(value, id);
            if (hidden.contains(id)
                    || !(entry != null && entry.kind.equals("action") || app != null))
                continue;
            if ((id.equals("fan") || id.equals("performance")) && explicitlyUnavailable(value, id))
                continue;
            ActionTile tile = new ActionTile(getContext(), id,
                    app != null ? app.label : entry.label, iconFor(id), tintFor(id), appIcon(app));
            tiles.put(id, tile);
            actionGrid.addView(tile, new TileParams(1));
            tile.update(value);
        }
        actionGrid.addView(addControlButton, new TileParams(1));
        updateAddControlVisibility();
        actionGrid.setVisibility(tiles.isEmpty() && !editing ? GONE : VISIBLE);
        actionGrid.requestLayout();
        metricBand.removeAllViews();
        int metricCount = 0;
        for (String id : order) {
            ControlCatalog.Entry entry = ControlCatalog.find(id);
            if (hidden.contains(id) || entry == null || !entry.kind.equals("metric"))
                continue;
            if (id.equals("fan_speed") && !finite(value.fanPercent))
                continue;
            Metric metric = metrics.get(id);
            if (metric == null)
                continue;
            metric.setVisibility(VISIBLE);
            if (metricCount > 0) {
                View line = new View(getContext());
                line.setBackgroundColor(0xff303034);
                metricBand.addView(line, lp(1, 27));
            }
            metricBand.addView(metric, new LinearLayout.LayoutParams(0, -1, 1));
            metricCount++;
        }
        metricBand.setVisibility(metricCount > 0 ? VISIBLE : GONE);
        updateScrollForMetricBand();
        boolean empty = brightnessCount + audioCount + tiles.size() + metricCount == 0;
        emptyState.setText(empty ? "＋  Add control" : "Choose dashboard controls in Settings");
        emptyState.setTextColor(empty ? AQUA : MUTED);
        emptyState.setBackground(empty ? ripple(round(ELEVATED, 12, 0), AQUA) : null);
        emptyState.setClickable(empty);
        emptyState.setFocusable(empty);
        emptyState.setContentDescription(
                empty ? "Add control" : "Choose dashboard controls in Settings");
        emptyState.setVisibility(empty ? VISIBLE : GONE);
    }

    private int buildBank(LinearLayout bank, List<String> order, Set<String> hidden) {
        bank.removeAllViews();
        int count = 0;
        for (String id : order) {
            if (hidden.contains(id)
                    || !ControlLogic.visibleOnDisplay(id, state.externalConnected)) {
                faders.get(id).setVisibility(GONE);
                continue;
            }
            if (count > 0) {
                View line = new View(getContext());
                line.setBackgroundColor(0xff25252a);
                bank.addView(line, horizontal ? lp(-1, 1) : lp(1, 140));
            }
            ControlSlider fader = faders.get(id);
            fader.setVisibility(VISIBLE);
            if (fader.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) fader.getParent()).removeView(fader);
            View control = fader;
            BlackButton blackButton = blackButtons.get(id);
            AudioMuteButton muteButton = audioMuteButtons.get(id);
            if (blackButton != null || muteButton != null) {
                FrameLayout wrapper = new FrameLayout(getContext());
                wrapper.addView(fader, new FrameLayout.LayoutParams(-1, -1));
                if (blackButton != null) {
                    if (blackButton.getParent() instanceof android.view.ViewGroup)
                        ((android.view.ViewGroup) blackButton.getParent()).removeView(blackButton);
                    blackButton.setVisibility(editing ? GONE : VISIBLE);
                    wrapper.addView(blackButton,
                            new FrameLayout.LayoutParams(dp(40), dp(44),
                                    Gravity.RIGHT
                                            | (horizontal ? Gravity.CENTER_VERTICAL
                                                          : Gravity.TOP)));
                } else if (muteButton != null) {
                    if (muteButton.getParent() instanceof android.view.ViewGroup)
                        ((android.view.ViewGroup) muteButton.getParent()).removeView(muteButton);
                    muteButton.setVisibility(editing ? GONE : VISIBLE);
                    wrapper.addView(muteButton,
                            new FrameLayout.LayoutParams(dp(40), dp(44),
                                    Gravity.RIGHT
                                            | (horizontal ? Gravity.CENTER_VERTICAL
                                                          : Gravity.TOP)));
                }
                control = wrapper;
            }
            bank.addView(control,
                    horizontal ? lp(-1, 54)
                               : new LinearLayout.LayoutParams(0, dp(compact ? 142 : 184), 1));
            count++;
        }
        bank.setVisibility(count > 0 ? VISIBLE : GONE);
        return count;
    }

    private void updateMetricValues(UiState value, float actualHz) {
        metric("temp", nonempty(value.temperatureLabel, "Temperature"),
                finite(value.temperatureC) ? String.format(Locale.US, "%.1f°", value.temperatureC)
                                           : "—",
                VIOLET);
        metric("fan_speed", "Fan %",
                finite(value.fanPercent) ? String.format(Locale.US, "%.0f", value.fanPercent) : "—",
                AQUA);
        boolean cpuUsage =
                MetricModePolicy.USAGE.equals(MetricModePolicy.normalize(value.cpuMetricMode));
        boolean gpuUsage =
                MetricModePolicy.USAGE.equals(MetricModePolicy.normalize(value.gpuMetricMode));
        float cpuLoad = value.bridgeReady && MetricModePolicy.isValidUsage(value.cpuLoadPercent)
                ? value.cpuLoadPercent
                : Float.NaN;
        float gpuLoad = value.bridgeReady && MetricModePolicy.isValidUsage(value.gpuLoadPercent)
                ? value.gpuLoadPercent
                : Float.NaN;
        String disconnected =
                value.bridgeReady ? "" : "Reconnect system controls to view telemetry";
        metric("cpu", cpuUsage ? "CPU · Usage" : "CPU · Clock",
                cpuUsage ? (finite(cpuLoad) ? String.format(Locale.US, "%.0f%%", cpuLoad) : "—")
                         : (value.bridgeReady && finite(value.cpuGhz)
                                           ? String.format(Locale.US, "%.2f GHz", value.cpuGhz)
                                           : "—"),
                0xffd89bdf,
                cpuUsage ? (value.bridgeReady ? value.cpuLoadReason : disconnected) : disconnected);
        metric("gpu", gpuUsage ? "GPU · Usage" : "GPU · Clock",
                gpuUsage ? (finite(gpuLoad) ? String.format(Locale.US, "%.0f%%", gpuLoad) : "—")
                         : (value.bridgeReady && finite(value.gpuMhz)
                                           ? String.format(Locale.US, "%.0f MHz", value.gpuMhz)
                                           : "—"),
                AQUA,
                gpuUsage ? (value.bridgeReady ? value.gpuLoadReason : disconnected) : disconnected);
        metric("memory", "Memory GB",
                finite(value.usedMemoryGb)
                        ? String.format(Locale.US,
                                  finite(value.totalMemoryGb) ? "%.1f / %.0f" : "%.1f",
                                  value.usedMemoryGb, value.totalMemoryGb)
                        : "—",
                BLUE);
        metric("power", "Battery W",
                finite(value.powerW) ? String.format(Locale.US, "%.1f", Math.abs(value.powerW))
                                     : "—",
                0xffeacb8a);
        metric("refresh", "Refresh Hz",
                finite(actualHz) ? String.format(Locale.US, "%.0f", actualHz) : "—", AQUA);
        String fpsScreen = value.fpsScreen == null ? "main" : value.fpsScreen;
        boolean external = "upper".equals(fpsScreen);
        float fps = external ? value.upperFps : value.mainFps;
        String fpsReason = external ? value.upperFpsReason : value.mainFpsReason;
        boolean fpsAvailable = finite(fps) && (!external || value.externalConnected);
        metric("fps", "FPS, " + (external ? "External" : "Main"),
                fpsAvailable ? String.format(Locale.US, "%.0f", fps) : "—",
                external ? VIOLET : AQUA,
                external && !value.externalConnected ? "Connect an external display" : fpsReason);
    }
    private void metric(String id, String label, String value, int tint) {
        Metric metric = metrics.get(id);
        if (metric != null)
            metric.update(label, value, tint);
    }
    private void metric(String id, String label, String value, int tint, String reason) {
        Metric metric = metrics.get(id);
        if (metric != null)
            metric.update(label, value, tint, reason);
    }
    private void updateClock(boolean visible) {
        if (!visible)
            return;
        String formatted =
                android.text.format.DateFormat.getTimeFormat(getContext()).format(new Date());
        if (!formatted.contentEquals(clockTime.getText()))
            clockTime.setText(formatted);
        String description = "Current time, " + formatted;
        clockTime.setContentDescription(description);
        clockTime.setTooltipText(description);
    }
    private void updateScrollForMetricBand() {
        ViewGroup.LayoutParams raw = scroll.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams))
            return;
        boolean visible = metricBand.getVisibility() == VISIBLE;
        FrameLayout.LayoutParams footerParams =
                (FrameLayout.LayoutParams) metricBand.getLayoutParams();
        int footerMargin = dp(12);
        if (footerParams.bottomMargin != footerMargin) {
            footerParams.bottomMargin = footerMargin;
            metricBand.setLayoutParams(footerParams);
        }
        int reserve = MetricFooterPolicy.scrollReserve(
                dp(48), dp(fullScreen ? 10 : 6), footerMargin, visible);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
        if (params.bottomMargin != reserve) {
            params.bottomMargin = reserve;
            scroll.setLayoutParams(params);
        }
    }

    private boolean available(UiState value, String id) {
        UiState.ControlStatus status = value.controls.get(id);
        return status != null && status.available;
    }
    private boolean explicitlyUnavailable(UiState value, String id) {
        UiState.ControlStatus status = value.controls.get(id);
        return status != null && !status.available;
    }
    private UiState.AppShortcut app(UiState value, String id) {
        for (UiState.AppShortcut app : value.pinnedApps)
            if (id.equals(app.id))
                return app;
        return null;
    }
    private List<String> ordered(UiState value) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String id : ids(value.controlOrder))
            if (ControlCatalog.known(id))
                order.add(id);
        for (ControlCatalog.Entry entry : ControlCatalog.entries()) order.add(entry.id);
        for (UiState.AppShortcut app : value.pinnedApps) order.add(app.id);
        return new ArrayList<>(order);
    }
    private List<String> filter(List<String> order, String[] group) {
        Set<String> allowed = new HashSet<>(Arrays.asList(group));
        List<String> result = new ArrayList<>();
        for (String id : order)
            if (allowed.contains(id))
                result.add(id);
        return result;
    }
    private Set<String> ids(String source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (source != null)
            for (String id : source.split(","))
                if (!id.trim().isEmpty())
                    result.add(id.trim());
        return result;
    }
    void startMove(String id, String stableKey) {
        String section = section(id);
        if (section.isEmpty())
            return;
        enterEditMode(section, stableKey);
    }
    public void beginEditing() {
        enterEditMode("actions", state.layoutKey);
    }
    private void enterEditMode(String section, String stableKey) {
        if (editing)
            return;
        cancelScreenshotHold();
        editing = true;
        editHistory.begin(DashboardEditSnapshot.capture(state));
        headerHeading.setVisibility(GONE);
        profileName.setVisibility(GONE);
        editHint.setVisibility(VISIBLE);
        undoEditingButton.setVisibility(VISIBLE);
        updateUndoButton();
        updateEditHeaderPosition(scroll.getScrollY());
        setBrandTrayOpen(false);
        editingSection = section;
        editingKey = stableKey;
        updateAddControlVisibility();
        actionGrid.setVisibility(VISIBLE);
        actionGrid.requestLayout();
        debugInteraction("edit", "entered");
        doneEditingButton.setVisibility(VISIBLE);
        statusCluster.setVisibility(GONE);
        LinearLayout.LayoutParams actionParams =
                (LinearLayout.LayoutParams) headerActionSlot.getLayoutParams();
        actionParams.width = dp(158);
        headerActionSlot.setLayoutParams(actionParams);
        updateMetricValues(state, state.refreshHz);
        applyEditingDecorations();
    }
    private void updateUndoButton() {
        boolean canUndo = editing && editHistory.canUndo();
        undoEditingButton.setEnabled(canUndo);
        undoEditingButton.setAlpha(canUndo ? 1f : .48f);
        undoEditingButton.setTextColor(canUndo ? AQUA : MUTED);
    }
    private void updateEditHeaderPosition(int scrollY) {
        header.setTranslationY(editing ? scrollY : 0);
        header.setElevation(editing ? dp(5) : 0);
        header.setBackground(editing ? round(BACKGROUND, 8, 0) : null);
    }
    private void undoEditing() {
        if (!editing || !editingKey.equals(state.layoutKey))
            return;
        DashboardEditSnapshot previous = editHistory.undo();
        if (previous == null) {
            updateUndoButton();
            return;
        }
        if (!editingKey.equals(previous.profileKey)
                || !actions.restoreEditSnapshot(editingKey, previous)) {
            editHistory.clear();
            updateUndoButton();
            return;
        }
        refreshEditingLayout(state);
        updateUndoButton();
    }
    void refreshEditingLayout(UiState next) {
        render(next);
        if (!editing || dragInProgress)
            return;
        rebuild(next);
        layoutSignature = signature(next);
        pendingRebuild = false;
        applyEditingDecorations();
    }
    private void applyEditingDecorations() {
        List<View> all = new ArrayList<>();
        all.addAll(tiles.values());
        all.addAll(faders.values());
        all.addAll(metrics.values());
        for (View view : all) {
            String item = idOf(view);
            if (item.isEmpty() || view.getParent() == null || view.getVisibility() == GONE)
                continue;
            view.setForeground(new ControlEditDecoration(getContext()));
            view.setContentDescription("Edit mode. " + labelFor(item) + ". "
                    + (view instanceof ActionTile
                                    ? "Tap left side to shrink or right side to grow. "
                                    : "")
                    + "Hold to move. Remove with the top-right badge. Done to finish.");
            if (view instanceof ControlSlider)
                ((ControlSlider) view).invalidate();
            else {
                boolean[] captured = {false};
                if (view instanceof ActionTile)
                    view.setOnTouchListener((target, event)
                                                    -> actionTileEditTouch((ActionTile) target,
                                                            item, event, captured));
                else
                    view.setOnTouchListener(
                            (target, event) -> badgeTouch(target, item, event, captured));
            }
        }
        List<View> peers = new ArrayList<>();
        peers.addAll(tiles.values());
        peers.addAll(faders.values());
        peers.addAll(metrics.values());
        for (View view : peers) {
            if (view.getVisibility() == GONE)
                continue;
            String item = idOf(view);
            view.setTag(item);
            view.setOnDragListener((target, event) -> {
                if (event.getAction() == DragEvent.ACTION_DROP) {
                    Object value = event.getLocalState();
                    String targetId = String.valueOf(target.getTag());
                    if (value instanceof String
                            && ControlMovePolicy.canDrop((String) value, targetId))
                        actions.moveControl(editingKey, (String) value, targetId);
                    return true;
                }
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED && dragInProgress) {
                    post(() -> {
                        dragInProgress = false;
                        if (pendingRebuild)
                            refreshEditingLayout(state);
                    });
                }
                return event.getAction() == DragEvent.ACTION_DRAG_STARTED
                        || event.getAction() == DragEvent.ACTION_DRAG_ENTERED
                        || event.getAction() == DragEvent.ACTION_DRAG_LOCATION
                        || event.getAction() == DragEvent.ACTION_DRAG_EXITED
                        || event.getAction() == DragEvent.ACTION_DRAG_ENDED;
            });
        }
        for (BlackButton button : blackButtons.values()) button.setVisibility(GONE);
        for (AudioMuteButton button : audioMuteButtons.values()) button.setVisibility(GONE);
    }
    private void hideRemoved(View view) {
        View target = view;
        ViewParent parent = view.getParent();
        if (view instanceof ControlSlider && parent instanceof View && parent != brightnessBank
                && parent != audioBank)
            target = (View) parent;
        target.setVisibility(GONE);
        target.requestLayout();
        boolean hasBrightness = false, hasAudio = false;
        for (String id : BRIGHTNESS) {
            ControlSlider fader = faders.get(id);
            View wrapper = fader == null
                    ? null
                    : (fader.getParent() instanceof View ? (View) fader.getParent() : null);
            if (fader != null && fader.getVisibility() != GONE && wrapper != null
                    && wrapper.getVisibility() != GONE
                    && (wrapper == brightnessBank || wrapper.getParent() == brightnessBank))
                hasBrightness = true;
        }
        for (String id : AUDIO) {
            ControlSlider fader = faders.get(id);
            if (fader != null && fader.getVisibility() != GONE
                    && fader.getParent() instanceof View) {
                View wrapper = (View) fader.getParent();
                if (wrapper.getVisibility() != GONE
                        && (wrapper == audioBank || wrapper.getParent() == audioBank))
                    hasAudio = true;
            }
        }
        brightnessBank.setVisibility(hasBrightness ? VISIBLE : GONE);
        audioBank.setVisibility(hasAudio ? VISIBLE : GONE);
        banks.setVisibility(hasBrightness || hasAudio ? VISIBLE : GONE);
        boolean hasMetric = false;
        for (Metric metric : metrics.values())
            if (metric.getParent() == metricBand && metric.getVisibility() != GONE)
                hasMetric = true;
        metricBand.setVisibility(hasMetric ? VISIBLE : GONE);
        updateScrollForMetricBand();
    }
    private void updateAddControlVisibility() {
        Set<String> hidden = ids(state.hiddenControls);
        boolean visibleNonAction = false;
        for (ControlCatalog.Entry entry : ControlCatalog.entries())
            if (!entry.kind.equals("action") && !hidden.contains(entry.id)
                    && ControlLogic.visibleOnDisplay(entry.id, state.externalConnected))
                visibleNonAction = true;
        boolean empty = ControlLogic.actionCount(state.controlOrder, state.hiddenControls) == 0
                && !visibleNonAction;
        addControlButton.setVisibility(
                ControlLogic.showAddControl(editing, empty) ? VISIBLE : GONE);
    }
    private String labelFor(String id) {
        ControlCatalog.Entry entry = ControlCatalog.find(id);
        if (entry != null)
            return entry.label;
        UiState.AppShortcut app = app(state, id);
        return app == null ? id : app.label;
    }
    private boolean badgeTouch(View view, String id, MotionEvent event, boolean[] captured) {
        boolean hit = ControlLogic.inRemoveBadge(event.getX(), event.getY(), view.getWidth(),
                view.getHeight(), dp(REMOVE_BADGE_TARGET_DP));
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            captured[0] = editing && hit;
            return captured[0];
        }
        if (!captured[0])
            return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            boolean remove = ControlLogic.shouldRemoveBadge(captured[0], hit);
            captured[0] = false;
            if (remove) {
                actions.removeControl(editingKey, id);
                hideRemoved(view);
                refreshEditingLayout(state);
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            captured[0] = false;
            return true;
        }
        return true;
    }
    private boolean actionTileEditTouch(
            ActionTile tile, String id, MotionEvent event, boolean[] captured) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tile.resizeDirectionPending = false;
            tile.touchDownX = event.getX();
            tile.touchDownY = event.getY();
            tile.touchMoved = false;
            tile.longPressTriggered = false;
            return badgeTouch(tile, id, event, captured);
        }
        if (captured[0])
            return badgeTouch(tile, id, event, captured);
        if (action == MotionEvent.ACTION_MOVE) {
            float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(event.getX() - tile.touchDownX) > slop
                    || Math.abs(event.getY() - tile.touchDownY) > slop) {
                tile.touchMoved = true;
                tile.resizeDirectionPending = false;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean tap = editing && !tile.touchMoved && !tile.longPressTriggered
                    && event.getX() >= 0 && event.getX() < tile.getWidth() && event.getY() >= 0
                    && event.getY() < tile.getHeight();
            if (tap) {
                tile.resizeGrow = ControlSizePolicy.tapGrows(id, event.getX(), tile.getWidth(),
                        state.smallControls, state.wideControls, state.controlSizes,
                        state.actionColumns);
                tile.resizeDirectionPending = true;
                tile.cancelLongPress();
                tile.setPressed(false);
                tile.performClick();
                tile.resizeDirectionPending = false;
                tile.longPressTriggered = false;
                return true;
            }
            tile.resizeDirectionPending = false;
            tile.longPressTriggered = false;
            tile.cancelLongPress();
            tile.setPressed(false);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            tile.resizeDirectionPending = false;
            tile.touchMoved = true;
            tile.longPressTriggered = false;
            return false;
        }
        return false;
    }
    private String section(String id) {
        return ControlMovePolicy.section(id);
    }
    private String idOf(View view) {
        for (Map.Entry<String, ActionTile> e : tiles.entrySet())
            if (e.getValue() == view)
                return e.getKey();
        for (Map.Entry<String, ControlSlider> e : faders.entrySet())
            if (e.getValue() == view)
                return e.getKey();
        for (Map.Entry<String, Metric> e : metrics.entrySet())
            if (e.getValue() == view)
                return e.getKey();
        return "";
    }
    private void finishMove() {
        if (!editing)
            return;
        editing = false;
        editHistory.clear();
        debugInteraction("edit", "exited");
        dragInProgress = false;
        cancelScreenshotHold();
        doneEditingButton.setVisibility(GONE);
        undoEditingButton.setVisibility(GONE);
        updateUndoButton();
        editHint.setVisibility(GONE);
        headerHeading.setVisibility(VISIBLE);
        profileName.setVisibility(VISIBLE);
        statusCluster.setVisibility(VISIBLE);
        updateEditHeaderPosition(0);
        LinearLayout.LayoutParams actionParams =
                (LinearLayout.LayoutParams) headerActionSlot.getLayoutParams();
        actionParams.width = 0;
        headerActionSlot.setLayoutParams(actionParams);
        addControlButton.setVisibility(GONE);
        List<View> views = new ArrayList<>();
        views.addAll(tiles.values());
        views.addAll(faders.values());
        views.addAll(metrics.values());
        for (View view : views) {
            view.setForeground(null);
            view.setOnDragListener(null);
            view.setOnTouchListener(null);
            view.setTag(null);
        }
        for (BlackButton button : blackButtons.values()) button.setVisibility(VISIBLE);
        for (AudioMuteButton button : audioMuteButtons.values()) button.setVisibility(VISIBLE);
        for (ActionTile tile : tiles.values()) {
            tile.resizeDirectionPending = false;
            tile.resizeGrow = true;
            tile.touchMoved = false;
            tile.longPressTriggered = false;
            tile.lastDescription = "";
            tile.update(state);
        }
        updateMetricValues(state, state.refreshHz);
        for (ControlSlider fader : faders.values()) fader.restoreDescription();
        editingSection = "";
        editingKey = "";
        if (pendingRebuild || !signature(state).equals(layoutSignature)) {
            pendingRebuild = false;
            rebuild(state);
            layoutSignature = signature(state);
        }
    }
    private void startMoveDrag(View view, String id) {
        if (!editing || id == null || id.isEmpty())
            return;
        android.content.ClipData clip =
                android.content.ClipData.newPlainText("duodash-control", id);
        dragInProgress = view.startDragAndDrop(clip, new View.DragShadowBuilder(view), id,
                View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_OPAQUE);
    }
    private int tintFor(String id) {
        if (id.equals("down") || id.equals("audio_upper"))
            return VIOLET;
        if (id.equals("screenshot") || id.equals("apps"))
            return BLUE;
        return AQUA;
    }
    private ControlIcon iconFor(String id) {
        switch (id) {
            case "swap":
                return ControlIcon.SWAP;
            case "up":
                return ControlIcon.UP;
            case "down":
                return ControlIcon.DOWN;
            case "wifi":
                return ControlIcon.WIFI;
            case "bluetooth":
                return ControlIcon.BLUETOOTH;
            case "rotation":
                return ControlIcon.ROTATION;
            case "dnd":
                return ControlIcon.DND;
            case "keyboard":
                return ControlIcon.KEYBOARD;
            case "screenshot":
                return ControlIcon.SCREENSHOT;
            case "apps":
                return ControlIcon.APPS;
            case "clear_recents":
                return ControlIcon.CLOSE;
            case "audio_output":
                return ControlIcon.AUDIO_OUTPUT;
            case "controller_mouse":
                return ControlIcon.MOUSE;
            case "quick_settings":
                return ControlIcon.QUICK_SETTINGS;
            case "shortcuts":
                return ControlIcon.SHORTCUTS;
            case "display_settings":
                return ControlIcon.SCREEN;
            case "audio_settings":
                return ControlIcon.VOLUME;
            case "fan":
                return ControlIcon.FAN;
            case "performance":
                return ControlIcon.PERFORMANCE;
            case "charging":
                return ControlIcon.PLUG;
            case "controller_style":
                return ControlIcon.GAMEPAD;
            case "trigger_mode":
                return ControlIcon.TRIGGERS;
            case "charge_limit":
                return ControlIcon.BATTERY_LIMIT;
            case "led":
                return ControlIcon.LIGHT;
            case "keep_awake":
                return ControlIcon.EYE;
            case "force_landscape":
                return ControlIcon.LANDSCAPE;
            case "game_assistant":
                return ControlIcon.ASSIST;
            case "airplane":
                return ControlIcon.AIRPLANE;
            case "battery_saver":
                return ControlIcon.BATTERY_SAVER;
            case "night":
                return ControlIcon.MOON;
            case "dark":
                return ControlIcon.DARK;
            case "inversion":
                return ControlIcon.INVERSION;
            case "cast":
                return ControlIcon.CAST;
            case "hotspot":
                return ControlIcon.HOTSPOT;
            case "location":
                return ControlIcon.LOCATION;
            case "refresh_mode":
                return ControlIcon.REFRESH;
            default:
                return ControlIcon.APP;
        }
    }
    private Drawable appIcon(UiState.AppShortcut shortcut) {
        if (shortcut == null || shortcut.component == null || shortcut.component.isEmpty())
            return null;
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        android.content.ComponentName component =
                android.content.ComponentName.unflattenFromString(shortcut.component);
        if (component != null)
            try {
                return pm.getActivityIcon(component).mutate();
            } catch (android.content.pm.PackageManager.NameNotFoundException
                    | RuntimeException ignored) {
            }
        if (component != null)
            try {
                return pm.getApplicationIcon(component.getPackageName()).mutate();
            } catch (android.content.pm.PackageManager.NameNotFoundException
                    | RuntimeException ignored) {
            }
        return null;
    }
    private boolean privileged(String id) {
        return Arrays
                .asList("wifi", "bluetooth", "rotation", "dnd", "fan", "performance",
                        "refresh_mode", "charging", "controller_style", "trigger_mode",
                        "charge_limit", "led", "keep_awake", "force_landscape", "game_assistant",
                        "airplane", "battery_saver", "night", "dark", "inversion", "location",
                        "clear_recents", "controller_mouse")
                .contains(id);
    }
    private boolean requiresBridge(String id) {
        return Arrays.asList("main", "upper", "both", "audio_main", "audio_upper").contains(id);
    }
    private String nonempty(String value, String fallback) {
        return ControlStyle.label(value, fallback);
    }
    private void cancelScreenshotHold() {
        ActionTile screenshot = tiles.get("screenshot");
        if (screenshot != null)
            screenshot.cancelScreenshotGesture();
    }
    private boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
    private void explain(String reason) {
        Toast.makeText(getContext(), nonempty(reason, "This control is not available yet"),
                     Toast.LENGTH_SHORT)
                .show();
    }

    private final class ActionTile extends LinearLayout {
        final String id;
        final int tint;
        final TextView label, detail;
        final ControlIconView mark;
        final LinearLayout copy;
        boolean canUse;
        String reason = "";
        boolean resizeDirectionPending, resizeGrow = true, touchMoved, longPressTriggered;
        float touchDownX, touchDownY;
        boolean appearanceInitialized, lastActive, lastAvailable;
        String lastDetail = "", lastDescription = "";
        private final ScreenshotHoldPolicy screenshotHold = new ScreenshotHoldPolicy();
        private boolean screenshotPointerGesture, screenshotControllerGesture,
                screenshotCaptureQueued;
        private float screenshotDownX, screenshotDownY;
        private Runnable screenshotHoldFrame, screenshotCaptureFirstFrame,
                screenshotCaptureSecondFrame;
        private String screenshotTarget = ScreenshotTargetPolicy.MAIN;
        ActionTile(Context context, String id, String name, ControlIcon icon, int tint,
                Drawable appIcon) {
            super(context);
            this.id = id;
            this.tint = tint;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(10), 0, dp(8), 0);
            setFocusable(true);
            setClickable(true);
            mark = new ControlIconView(context, icon, tint, 21, appIcon);
            addView(mark, lp(id.equals("screenshot") ? 32 : 25, 32));
            copy = column();
            copy.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -1, 1);
            copyParams.leftMargin = dp(7);
            addView(copy, copyParams);
            label = text(name, 12, TEXT, true);
            detail = text("", 10.5f, MUTED, false);
            copy.addView(label, lp(-1, 17));
            copy.addView(detail, lp(-1, 14));
            setOnClickListener(v -> {
                if (editing) {
                    boolean grow = resizeDirectionPending ? resizeGrow : true;
                    resizeDirectionPending = false;
                    resizeControl(grow);
                    if (controllerNavigation && tiles.containsKey(id))
                        tiles.get(id).requestFocus();
                    return;
                }
                resizeDirectionPending = false;
                if (id.equals("screenshot")) {
                    cycleScreenshotTarget();
                    return;
                }
                if (canUse) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    actions.control(id);
                } else if (privileged(id) && !state.bridgeReady)
                    actions.connection();
                else
                    explain(reason);
            });
            setOnLongClickListener(v -> {
                if (editing) {
                    resizeDirectionPending = false;
                    longPressTriggered = true;
                    startMoveDrag(this, id);
                    return true;
                }
                if (id.equals("screenshot")) {
                    requestScreenshotCaptureAfterClear();
                    return true;
                }
                if (id.equals("wifi") || id.equals("bluetooth")) {
                    debugInteraction("native-option", id);
                    actions.openNativeSettings(id);
                    return true;
                }
                return false;
            });
        }
        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (!id.equals("screenshot") || editing)
                return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    screenshotDownX = event.getX();
                    screenshotDownY = event.getY();
                    screenshotPointerGesture = true;
                    setPressed(true);
                    beginScreenshotGesture();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (screenshotPointerGesture && !touchMoved) {
                        float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        if (Math.hypot(
                                    event.getX() - screenshotDownX, event.getY() - screenshotDownY)
                                > slop) {
                            touchMoved = true;
                            getParent().requestDisallowInterceptTouchEvent(false);
                            cancelScreenshotHoldOnly();
                            setPressed(false);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    setPressed(false);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (!screenshotPointerGesture)
                        return true;
                    screenshotPointerGesture = false;
                    if (touchMoved) {
                        touchMoved = false;
                        return true;
                    }
                    releaseScreenshotGesture();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    screenshotPointerGesture = false;
                    touchMoved = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    cancelScreenshotGesture();
                    setPressed(false);
                    return true;
                default:
                    return true;
            }
        }
        private void beginControllerScreenshotGesture() {
            if (!controllerNavigation || !isControllerTarget(this)
                    || !ScreenshotHoldPolicy.canBegin(id.equals("screenshot"), editing))
                return;
            screenshotControllerGesture = true;
            beginScreenshotGesture();
        }
        private void releaseControllerScreenshotGesture() {
            if (!screenshotControllerGesture)
                return;
            screenshotControllerGesture = false;
            releaseScreenshotGesture();
        }
        private void beginScreenshotGesture() {
            cancelScreenshotHoldOnly();
            screenshotHold.begin(SystemClock.uptimeMillis());
            mark.setScreenshotHoldProgress(0f);
            scheduleScreenshotHoldFrame();
        }
        private void scheduleScreenshotHoldFrame() {
            if (screenshotHoldFrame == null)
                screenshotHoldFrame = () -> {
                    if (!screenshotHold.isActive() || editing || !isAttachedToWindow()) {
                        cancelScreenshotGesture();
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    ScreenshotHoldPolicy.Outcome outcome = screenshotHold.update(now);
                    if (outcome == ScreenshotHoldPolicy.Outcome.CAPTURE) {
                        mark.setScreenshotHoldProgress(0f);
                        invalidate();
                        queueScreenshotCaptureAfterClear();
                        return;
                    }
                    mark.setScreenshotHoldProgress(screenshotHold.progress(now));
                    postOnAnimation(screenshotHoldFrame);
                };
            postOnAnimation(screenshotHoldFrame);
        }
        private void releaseScreenshotGesture() {
            ScreenshotHoldPolicy.Outcome outcome =
                    screenshotHold.release(SystemClock.uptimeMillis());
            stopScreenshotHoldFrame();
            mark.setScreenshotHoldProgress(0f);
            invalidate();
            if (outcome == ScreenshotHoldPolicy.Outcome.CYCLE)
                performClick();
            else if (outcome == ScreenshotHoldPolicy.Outcome.CAPTURE)
                queueScreenshotCaptureAfterClear();
        }
        private void cycleScreenshotTarget() {
            if (screenshotCaptureQueued)
                return;
            screenshotTarget = ScreenshotOptions.cycleTarget(getContext());
            mark.setScreenshotTarget(screenshotTarget);
            lastDetail = "";
            update(state);
        }
        private void queueScreenshotCaptureAfterClear() {
            if (screenshotCaptureQueued || editing || !isAttachedToWindow())
                return;
            screenshotCaptureQueued = true;
            mark.setScreenshotHoldProgress(0f);
            invalidate();
            if (screenshotCaptureFirstFrame == null)
                screenshotCaptureFirstFrame = () -> {
                    if (!screenshotCaptureQueued || editing || !isAttachedToWindow()) {
                        screenshotCaptureQueued = false;
                        return;
                    }
                    if (screenshotCaptureSecondFrame == null)
                        screenshotCaptureSecondFrame = () -> {
                            if (!screenshotCaptureQueued)
                                return;
                            screenshotCaptureQueued = false;
                            if (!editing && isAttachedToWindow() && canUse)
                                actions.control("screenshot");
                        };
                    postOnAnimation(screenshotCaptureSecondFrame);
                };
            postOnAnimation(screenshotCaptureFirstFrame);
        }
        private void stopScreenshotHoldFrame() {
            if (screenshotHoldFrame != null)
                removeCallbacks(screenshotHoldFrame);
        }
        private void cancelScreenshotHoldOnly() {
            stopScreenshotHoldFrame();
            screenshotHold.cancel();
            mark.setScreenshotHoldProgress(0f);
            invalidate();
        }
        private void cancelScreenshotGesture() {
            cancelScreenshotHoldOnly();
            screenshotPointerGesture = false;
            screenshotControllerGesture = false;
            if (!editing) {
                touchMoved = false;
                setPressed(false);
            }
            if (screenshotCaptureFirstFrame != null)
                removeCallbacks(screenshotCaptureFirstFrame);
            if (screenshotCaptureSecondFrame != null)
                removeCallbacks(screenshotCaptureSecondFrame);
            screenshotCaptureQueued = false;
        }
        private void requestScreenshotCaptureAfterClear() {
            if (!editing)
                queueScreenshotCaptureAfterClear();
        }
        @Override
        protected void onDetachedFromWindow() {
            cancelScreenshotGesture();
            super.onDetachedFromWindow();
        }
        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (editing) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            } else if (id.equals("screenshot")) {
                info.setLongClickable(true);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, "Take screenshot"));
            }
        }
        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (editing && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                resizeControl(false);
                return true;
            }
            if (editing && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                resizeControl(true);
                return true;
            }
            if (!editing && id.equals("screenshot")
                    && action == AccessibilityNodeInfo.ACTION_LONG_CLICK)
                return performLongClick();
            return super.performAccessibilityAction(action, arguments);
        }
        private void resizeControl(boolean grow) {
            if (!editing || !actions.resizeControlSize(editingKey, id, grow))
                return;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            debugInteraction("size",
                    id + "="
                            + ControlSizePolicy.sizeLabel(id, state.smallControls,
                                    state.wideControls, state.controlSizes));
            refreshEditingLayout(state);
        }
        void applySize(boolean small) {
            copy.setVisibility(small ? GONE : VISIBLE);
            setGravity(small ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
            setPadding(dp(small ? 4 : 10), 0, dp(small ? 4 : 8), 0);
            mark.setScaleX(small ? .9f : 1f);
            mark.setScaleY(small ? .9f : 1f);
        }
        void update(UiState value) {
            applySize(ControlSizePolicy.iconOnly(id, value.smallControls, value.controlSizes));
            if (id.equals("screenshot")) {
                screenshotTarget = ScreenshotOptions.target(getContext());
                mark.setScreenshotTarget(screenshotTarget);
            }
            UiState.ControlStatus status = value.controls.get(id);
            UiState.AppShortcut app = app(value, id);
            boolean active = status != null && status.active;
            boolean route = id.equals("swap") || id.equals("up") || id.equals("down");
            if (route) {
                canUse = value.bridgeReady && value.externalConnected && !value.busy
                        && (status == null || status.available);
                reason = value.busy                           ? "Wait for the current screen move"
                        : !value.externalConnected            ? "Connect an external display first"
                        : !value.bridgeReady                  ? "Reconnect system controls"
                        : status != null && !status.available ? status.detail
                                                              : "";
            } else if (id.equals("clear_recents")) {
                canUse = value.bridgeReady && !value.busy;
                reason = !value.bridgeReady ? "Reconnect system controls"
                        : value.busy        ? "Wait for the current action"
                                            : "";
            } else if (status != null) {
                boolean needsBridge = privileged(id);
                canUse = status.available && (!needsBridge || value.bridgeReady);
                reason = needsBridge && !value.bridgeReady ? "Reconnect system controls"
                                                           : status.detail;
            } else if (app != null) {
                canUse = !app.upper || value.externalConnected;
                reason = app.upper ? "Connect the external display first" : "";
            } else {
                canUse = Arrays.asList("apps", "shortcuts", "keyboard", "screenshot",
                                       "display_settings", "audio_settings", "cast", "hotspot",
                                       "audio_output", "quick_settings")
                                 .contains(id);
                reason = value.bridgeReady ? "Waiting for device status"
                                           : "Reconnect system controls";
            }
            String secondary = status != null ? nonempty(status.detail, "") : "";
            if (route)
                secondary = value.busy      ? "Moving…"
                        : !canUse           ? reason
                        : id.equals("up")   ? nonempty(value.mainApp, "Main app")
                        : id.equals("down") ? nonempty(value.upperApp, "External app")
                                            : "Main ↔ External";
            else if (app != null)
                secondary = app.upper ? "External screen" : "Main screen";
            else if (id.equals("screenshot") && !editing)
                secondary = screenshotTargetLabel();
            else if (secondary.isEmpty()) {
                switch (id) {
                    case "clear_recents":
                        secondary = canUse ? "Close recent apps" : reason;
                        break;
                    case "audio_output":
                        secondary = "Choose a device";
                        break;
                    case "quick_settings":
                        secondary = "Android controls";
                        break;
                    case "apps":
                        secondary = "Choose a screen";
                        break;
                    case "shortcuts":
                        secondary = "Button setup";
                        break;
                    case "keyboard":
                        secondary = "Input method";
                        break;
                    case "screenshot":
                        secondary = screenshotTargetLabel();
                        break;
                    case "display_settings":
                        secondary = "Android display";
                        break;
                    case "audio_settings":
                        secondary = "Android sound";
                        break;
                    case "cast":
                        secondary = "Cast settings";
                        break;
                    case "hotspot":
                        secondary = "Hotspot settings";
                        break;
                    default:
                        secondary = canUse ? (active ? "On" : "Off") : reason;
                }
            }
            if (!secondary.equals(lastDetail)) {
                detail.setText(secondary);
                lastDetail = secondary;
            }
            if (!appearanceInitialized || lastAvailable != canUse) {
                label.setTextColor(canUse ? TEXT : MUTED);
                mark.setAlpha(canUse ? 1f : .5f);
                lastAvailable = canUse;
            }
            if (!appearanceInitialized || lastActive != active) {
                setBackground(ripple(round(active ? 0xff39313f : ELEVATED, 12, 0), tint));
                lastActive = active;
            }
            String description = id.equals("screenshot") && !editing
                    ? "Screenshot capture. Target " + screenshotTargetLabel()
                            + ". Tap to cycle target. Hold to capture."
                    : label.getText() + ". " + secondary
                            + (editing ? ". Edit mode. Size "
                                                    + ControlSizePolicy.sizeLabel(id,
                                                            value.smallControls, value.wideControls,
                                                            value.controlSizes)
                                                    + (". Tap left side to shrink or right side to "
                                                       + "grow. Hold to move. Done to finish.")
                                            : canUse ? ""
                                                     : ". Unavailable: " + reason);
            setTooltipText(label.getText());
            if (!description.equals(lastDescription)) {
                setContentDescription(description);
                lastDescription = description;
            }
            appearanceInitialized = true;
        }
        private String screenshotTargetLabel() {
            switch (ScreenshotTargetPolicy.normalize(screenshotTarget)) {
                case ScreenshotTargetPolicy.EXTERNAL:
                    return "External";
                case ScreenshotTargetPolicy.BOTH:
                    return "Both";
                default:
                    return "Main";
            }
        }
    }

    private final class ActionGrid extends android.view.ViewGroup {
        private int columns = ControlSizePolicy.ROW_UNITS, rowHeight, gridWidth;
        ActionGrid(Context context) {
            super(context);
            setClipToPadding(false);
        }
        private int gutterPx() {
            return dp(fullScreen ? 7f : 4.4f);
        }
        private int minimumTouchableUnits(int width, int gap) {
            for (int units = 1; units <= ControlSizePolicy.ROW_UNITS; units++)
                if (ControlSizePolicy.spanWidth(width, gap, 0, units) >= dp(44))
                    return units;
            return ControlSizePolicy.ROW_UNITS;
        }
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int configuredColumns = ControlLogic.actionColumns(actionColumns);
            columns = ControlSizePolicy.ROW_UNITS;
            int gap = gutterPx();
            gridWidth = width;
            List<String> ids = new ArrayList<>();
            Map<String, View> byId = new HashMap<>();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE)
                    continue;
                String id = child == addControlButton ? "__add_control__" : idOf(child);
                ids.add(id);
                byId.put(id, child);
            }
            List<ControlLogic.GridPlacement> placements = ControlSizePolicy.pack(ids,
                    state.smallControls, state.wideControls, state.controlSizes, configuredColumns,
                    minimumTouchableUnits(width, gap));
            int rows = 0;
            for (ControlLogic.GridPlacement placement : placements)
                rows = Math.max(rows, placement.row + 1);
            int naturalRowHeight = dp(compact ? 48 : 60);
            rowHeight = DashboardGridSizingPolicy.rowHeight(availableActionGridHeight(), rows, gap,
                    dp(compact ? 48 : 60), naturalRowHeight, fullScreen);
            for (ControlLogic.GridPlacement placement : placements) {
                View child = byId.get(placement.id);
                if (child == null)
                    continue;
                TileParams params = (TileParams) child.getLayoutParams();
                params.row = placement.row;
                params.column = placement.column;
                params.resolvedSpan = placement.span;
                child.measure(MeasureSpec.makeMeasureSpec(ControlSizePolicy.spanWidth(width, gap,
                                                                  placement.column, placement.span),
                                      MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, DashboardGridSizingPolicy.gridHeight(rows, rowHeight, gap));
        }
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int gap = gutterPx();
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE)
                    continue;
                TileParams params = (TileParams) child.getLayoutParams();
                int x = ControlSizePolicy.unitEdge(gridWidth, gap, params.column),
                    y = params.row * (rowHeight + gap);
                child.layout(x, y, x + child.getMeasuredWidth(), y + rowHeight);
            }
        }
        @Override
        protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams params) {
            return params instanceof TileParams;
        }
        @Override
        protected android.view.ViewGroup.LayoutParams generateDefaultLayoutParams() {
            return new TileParams(1);
        }
    }
    private static final class TileParams extends android.view.ViewGroup.LayoutParams {
        final int span;
        int row, column, resolvedSpan;
        TileParams(int span) {
            super(-1, -2);
            this.span = span;
        }
    }
    private final class Metric extends LinearLayout {
        final TextView label, value;
        final String id;
        final boolean changesRefresh;
        Metric(Context context, String id) {
            super(context);
            this.id = id;
            changesRefresh = id.equals("refresh");
            setOrientation(VERTICAL);
            if (id.equals("fps"))
                setBackground(new FpsGlyph(context));
            setGravity(Gravity.CENTER);
            setFocusable(true);
            setClickable(true);
            label = text("", 10.5f, MUTED, false);
            label.setGravity(Gravity.CENTER);
            label.setAutoSizeTextTypeUniformWithConfiguration(
                    9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            value = text("—", 17, TEXT, true);
            value.setGravity(Gravity.CENTER);
            value.setFontFeatureSettings("tnum");
            value.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            label.setClickable(false);
            label.setFocusable(false);
            label.setLongClickable(false);
            value.setClickable(false);
            value.setFocusable(false);
            value.setLongClickable(false);
            addView(label, lp(-1, 15));
            addView(value, lp(-1, 25));
            setOnClickListener(v -> {
                if (editing) {
                    if (id.equals("cpu") || id.equals("gpu")) {
                        String mode = id.equals("cpu") ? state.cpuMetricMode : state.gpuMetricMode;
                        String next = MetricModePolicy.toggle(mode);
                        debugInteraction("metric-mode", id + "=" + next);
                        actions.setMetricMode(id, next);
                    } else
                        finishMove();
                    return;
                }
                if ((changesRefresh || id.equals("fan_speed") || id.equals("cpu")
                            || id.equals("gpu"))
                        && !state.bridgeReady) {
                    actions.connection();
                    return;
                }
                if (changesRefresh)
                    actions.control("refresh_toggle");
                else if (id.equals("fps"))
                    actions.control("fps_cycle");
                else if (id.equals("fan_speed")) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    actions.control("fan");
                } else if (id.equals("cpu") || id.equals("gpu")) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    actions.control("performance");
                }
            });
            setOnLongClickListener(v -> {
                if (editing) {
                    startMoveDrag(this, id);
                    return true;
                }
                return false;
            });
        }
        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            return true;
        }
        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width != oldWidth || height != oldHeight)
                value.post(this::fitValueTextSize);
        }
        void update(String name, String reading, int tint) {
            update(name, reading, tint, "");
        }
        void update(String name, String reading, int tint, String reason) {
            scheduleValueTextSize();
            if (id.equals("fps")) {
                label.setText("FPS");
                value.setText(reading);
                label.setTextColor(MUTED);
                value.setTextColor(tint);
                setContentDescription(name + " screen: "
                        + (reading.equals("—")
                                        ? "unavailable. " + nonempty(reason, "No reading available")
                                        : reading + " frames per second")
                        + ". Tap to switch screen.");
                setTooltipText(getContentDescription().toString());
                if (getBackground() != null)
                    getBackground().invalidateSelf();
                return;
            }
            if (!name.contentEquals(label.getText()))
                label.setText(name);
            if (!reading.contentEquals(value.getText()))
                value.setText(reading);
            if (value.getCurrentTextColor() != tint)
                value.setTextColor(tint);
            String action = id.equals("fan_speed") ? ". Tap for fan mode."
                    : (id.equals("cpu") || id.equals("gpu"))
                    ? (editing ? ". Tap to switch between Clock and Usage."
                               : ". Tap for performance mode.")
                    : changesRefresh ? ". Tap to change refresh rate."
                                     : "";
            String description = name + ": "
                    + (reading.equals("—")
                                    ? "unavailable. " + nonempty(reason, "No reading available")
                                    : reading)
                    + action;
            setContentDescription(description);
            setTooltipText(description);
        }
        private void scheduleValueTextSize() {
            value.post(this::fitValueTextSize);
        }
        private void fitValueTextSize() {
            int width = value.getWidth() - value.getCompoundPaddingLeft()
                    - value.getCompoundPaddingRight();
            int height = value.getHeight() - value.getCompoundPaddingTop()
                    - value.getCompoundPaddingBottom();
            if (width <= 0 || height <= 0)
                return;
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            android.text.TextPaint paint = new android.text.TextPaint(value.getPaint());
            String text = value.getText().toString();
            float fittedSp = 12f;
            for (int candidateSp = 18; candidateSp >= 12; candidateSp--) {
                paint.setTextSize(candidateSp * scaledDensity);
                android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
                if (paint.measureText(text) <= width
                        && (metrics.descent - metrics.ascent) <= height) {
                    fittedSp = candidateSp;
                    break;
                }
            }
            boolean usage = state != null
                    && ((id.equals("cpu") && MetricModePolicy.USAGE.equals(state.cpuMetricMode))
                            || (id.equals("gpu")
                                    && MetricModePolicy.USAGE.equals(state.gpuMetricMode)));
            float finalSp = Math.max(1f, fittedSp - (usage ? 2f : 1f));
            float targetPx = finalSp * scaledDensity;
            if (Math.abs(value.getTextSize() - targetPx) > .5f)
                value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, finalSp);
        }
        void restoreDescription() {
            String name = label.getText().toString(), reading = value.getText().toString();
            String action = id.equals("fan_speed")         ? ". Tap for fan mode."
                    : id.equals("cpu") || id.equals("gpu") ? ". Tap for performance mode."
                    : changesRefresh                       ? ". Tap to change refresh rate."
                                                           : "";
            setContentDescription(name + ": " + reading + action);
            setTooltipText(getContentDescription().toString());
        }
    }
    private final class FpsGlyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        FpsGlyph(Context context) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
        }
        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(),
                  radius = Math.min(dp(22), Math.min(b.width(), b.height()) / 2f - dp(1));
            paint.setColor(0xff39363f);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(cx, cy, radius, paint);
            boolean external = "upper".equals(state.fpsScreen);
            int externalColor = external ? VIOLET : MUTED, mainColor = external ? MUTED : AQUA;
            float barW = dp(10), barH = dp(2.5f), x = cx + dp(13), y = cy + dp(9);
            paint.setStrokeWidth(dp(1));
            paint.setColor(externalColor);
            paint.setStyle(ControlLogic.fpsMarkerFilled(external, true) ? Paint.Style.FILL
                                                                        : Paint.Style.STROKE);
            canvas.drawRoundRect(x, y - barH / 2, x + barW, y + barH / 2, dp(1), dp(1), paint);
            paint.setColor(mainColor);
            paint.setStyle(ControlLogic.fpsMarkerFilled(external, false) ? Paint.Style.FILL
                                                                         : Paint.Style.STROKE);
            canvas.drawRoundRect(
                    x, y + dp(4) - barH / 2, x + barW, y + dp(4) + barH / 2, dp(1), dp(1), paint);
            paint.setStyle(Paint.Style.FILL);
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
    }
    /** Independent44dp button, kept outside the slider's touch target. */
    private final class BlackButton extends View {
        private final String controlId;
        private final boolean mainScreen;
        private final ControlIconDrawable eye = icon(ControlIcon.EYE, MUTED, 16),
                                          closedEye = icon(ControlIcon.EYE_OFF, AQUA, 16);
        private boolean black, canUse = true;
        private String reason = "";
        BlackButton(Context context, String controlId, boolean mainScreen) {
            super(context);
            this.controlId = controlId;
            this.mainScreen = mainScreen;
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> {
                if (editing) {
                    finishMove();
                    return;
                }
                if (canUse) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    actions.control(controlId);
                } else if (!state.bridgeReady && !mainScreen && state.externalConnected)
                    actions.connection();
                else
                    explain(reason);
            });
            setOnLongClickListener(v -> {
                if (editing) {
                    String id = mainScreen ? "main" : "upper";
                    startMoveDrag(faders.get(id), id);
                    return true;
                }
                return false;
            });
        }
        void update(boolean black, boolean available, String reason) {
            this.black = black;
            this.canUse = available;
            this.reason = reason;
            String target = mainScreen ? "main" : "external";
            String label = (black ? "Turn on " : "Turn off ") + target + " screen";
            if (mainScreen && !black)
                label += ". Use the button shortcut to turn it on";
            setContentDescription(label
                    + (available ? ""
                                 : ". Unavailable: " + nonempty(reason, "Control unavailable")));
            setStateDescription(black ? "Screen on" : "Screen off");
            setTooltipText(label);
            setAlpha(available ? 1f : .45f);
            invalidate();
        }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            ControlIconDrawable glyph = black ? closedEye : eye;
            int size = dp(16), left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            glyph.setBounds(left, top, left + size, top + size);
            glyph.draw(canvas);
        }
        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.ToggleButton");
            info.setCheckable(true);
            info.setChecked(black);
            info.setEnabled(canUse);
        }
    }
    private final class AudioMuteButton extends View {
        private final String id;
        private final ControlIconDrawable sound = icon(ControlIcon.VOLUME, MUTED, 16),
                                          mutedGlyph = icon(ControlIcon.MUTED_VOLUME, AQUA, 16);
        private boolean muted, canUse = true;
        private String reason = "";
        AudioMuteButton(Context context, String id) {
            super(context);
            this.id = id;
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> {
                if (editing) {
                    finishMove();
                    return;
                }
                if (canUse) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    actions.toggleAudioMute(this.id);
                } else if (!state.bridgeReady && !"volume".equals(this.id))
                    actions.connection();
                else
                    explain(reason);
            });
            setOnLongClickListener(v -> {
                ControlSlider fader = faders.get(this.id);
                if (fader == null)
                    return false;
                if (editing) {
                    startMoveDrag(fader, this.id);
                    return true;
                }
                return false;
            });
        }
        void update(boolean muted, boolean available, String reason) {
            this.muted = muted;
            this.canUse = available;
            this.reason = reason == null ? "" : reason;
            String target = id.equals("volume") ? "master volume"
                    : id.equals("audio_main")   ? "main app audio"
                                                : "external app audio";
            String label = (muted ? "Unmute " : "Mute ") + target;
            setContentDescription(label
                    + (available ? ""
                                 : ". Unavailable: "
                                            + nonempty(this.reason, "Audio control unavailable")));
            setTooltipText(label);
            setAlpha(available ? 1f : .42f);
            invalidate();
        }
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            ControlIconDrawable glyph = muted ? mutedGlyph : sound;
            int size = dp(16), left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            glyph.setBounds(left, top, left + size, top + size);
            glyph.draw(canvas);
        }
        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.ToggleButton");
            info.setCheckable(true);
            info.setChecked(muted);
            info.setEnabled(canUse);
        }
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(medium ? MEDIUM : REGULAR);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }
    private GradientDrawable round(int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius * .78f));
        if (stroke != 0)
            drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private Drawable ripple(Drawable background, int tint) {
        return new RippleDrawable(ColorStateList.valueOf((tint & 0x00ffffff) | 0x28000000),
                background, round(Color.WHITE, 16, 0));
    }
    private ControlIconDrawable icon(ControlIcon glyph, int color, int size) {
        return new ControlIconDrawable(glyph, color, dp(size));
    }
    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(
                width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private String formatRuntime(int minutes) {
        if (minutes < 5)
            return "~<5m";
        if (minutes < 60)
            return "~" + minutes + "m";
        int hours = minutes / 60, remaining = minutes % 60;
        if (hours >= 24)
            return "~" + (hours / 24) + "d" + (hours % 24 == 0 ? "" : hours % 24 + "h");
        return "~" + hours + "h" + (remaining == 0 ? "" : remaining + "m");
    }
    private String describeRuntime(int minutes) {
        if (minutes < 5)
            return "less than 5 minutes";
        if (minutes < 60)
            return minutes + " minutes";
        int hours = minutes / 60, remaining = minutes % 60;
        return hours + " hour" + (hours == 1 ? "" : "s")
                + (remaining == 0 ? "" : " " + remaining + " minutes");
    }
}
