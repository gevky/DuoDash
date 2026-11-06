package dev.nova.deck;

import java.util.List;
import java.util.Objects;

/** Narrow policy for output-scoped app routing and the Retroid panel-power lease. */
public final class TvProfilePolicy {
    private TvProfilePolicy() {}

    public static boolean shouldLaunchSecondaryHome(
        List<ControlLogic.Task> tasks, int displayId, String homePackage
    ) {
        if (displayId <= 0 || homePackage == null || homePackage.isEmpty()) return false;
        if (tasks == null) return false;
        for (ControlLogic.Task task : tasks) {
            if (task == null) return false;
            if (task.display != displayId) continue;
            if (!task.visible) continue;
            if (task.pkg == null) return false;
            if (homePackage.equals(task.pkg)) return false;
            if (task.standard && ControlLogic.externalUserTask(task)) return false;
        }
        return true;
    }

    public static String defaultHomePackage(String resolveOutput) {
        if (resolveOutput == null) return null;
        String found = null;
        for (String line : resolveOutput.split("\\R")) {
            String component = line.trim();
            int slash = component.indexOf('/');
            if (slash <= 0 || slash == component.length() - 1) continue;
            String pkg = component.substring(0, slash);
            String activity = component.substring(slash + 1);
            if (!validPackage(pkg) || !validActivity(activity)) continue;
            if (found != null) return null;
            found = pkg;
        }
        return found;
    }

    public static String secondaryHomeComponent(String homePackage, String queryOutput) {
        if (!validPackage(homePackage) || queryOutput == null) return null;
        String found = null;
        for (String line : queryOutput.split("\\R")) {
            String component = line.trim();
            int slash = component.indexOf('/');
            if (slash <= 0 || slash == component.length() - 1) continue;
            String pkg = component.substring(0, slash);
            String activity = component.substring(slash + 1);
            if (!homePackage.equals(pkg) || !validActivity(activity)) continue;
            if (found != null) return null;
            found = component;
        }
        return found;
    }

    private static boolean validPackage(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static boolean validActivity(String value) {
        return value != null && value.matches("\\.?[A-Za-z0-9_.$]+(?:\\.[A-Za-z0-9_.$]+)*");
    }

    public static boolean shouldTurnPanelOff(
        boolean requested, boolean externalConnected, boolean identityMatches,
        boolean supported, boolean controlsVisible, boolean temporaryWake
    ) {
        return requested && externalConnected && identityMatches && supported && !controlsVisible && !temporaryWake;
    }

    /** Keep a visible DuoDash panel above the saved external dim layer without changing its preference. */
    public static int effectiveUpperDimPercent(boolean panelOpen,int dashboardDisplayId,int savedPercent) {
        return panelOpen&&dashboardDisplayId>0?100:savedPercent;
    }

    public static int effectiveUpperDimPercent(boolean panelOpen,boolean physicalExternalTarget,int savedPercent) {
        return panelOpen&&physicalExternalTarget?100:savedPercent;
    }

    public static boolean stillOwnsSetting(String currentValue, String lastWrittenValue) {
        return Objects.equals(currentValue, lastWrittenValue);
    }

    public static boolean initialScreenOffChoice(boolean externalOutput,boolean hasSavedChoice,boolean savedChoice,boolean nativeBaselineOff){
        if(!externalOutput)return false;
        return hasSavedChoice?savedChoice:nativeBaselineOff;
    }

    /** Avoid restoring a value that could blank a connected panel again. */
    public static boolean mayRestoreBaseline(
        boolean externalConnected, String baselineValue, boolean panelConfirmedOn
    ) {
        if (baselineValue == null || "0".equals(baselineValue)) return true;
        return panelConfirmedOn;
    }
}
