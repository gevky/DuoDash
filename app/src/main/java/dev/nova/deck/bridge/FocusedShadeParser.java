package dev.nova.deck.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only WindowManager's focused-window title for a requested display. */
public final class FocusedShadeParser {
    private static final Pattern DISPLAY = Pattern.compile("^\\s*Display:\\s*mDisplayId=(\\d+)\\b.*$");
    private static final Pattern FOCUS = Pattern.compile("^\\s*mCurrentFocus=Window\\{[^\\s}]+\\s+u\\d+\\s+([^}]+)\\}\\s*$");

    private FocusedShadeParser() { }

    public static boolean isNotificationShadeFocused(String dump, int expectedDisplay) {
        if (dump == null || dump.isEmpty() || expectedDisplay < 0) return false;
        int currentDisplay = -1;
        int matchingBlocks = 0;
        boolean focusedShade = false;
        boolean focusSeen = false;
        String[] lines = dump.split("\\r?\\n");
        for (String line : lines) {
            Matcher display = DISPLAY.matcher(line);
            if (display.matches()) {
                currentDisplay = parseDisplay(display.group(1));
                if (currentDisplay == expectedDisplay) {
                    matchingBlocks++;
                    focusSeen = false;
                    focusedShade = false;
                }
                continue;
            }
            if (currentDisplay != expectedDisplay) continue;
            Matcher focus = FOCUS.matcher(line);
            if (focus.matches()) {
                if (focusSeen) return false;
                focusSeen = true;
                focusedShade = "NotificationShade".equals(focus.group(1));
            }
        }
        return matchingBlocks == 1 && focusSeen && focusedShade;
    }

    private static int parseDisplay(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
