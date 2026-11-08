package dev.nova.deck;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pure model for independently persisted dashboard and control key bindings. */
public final class ShortcutBindings {
    public static final String TOGGLE = "dashboard";
    public static final String PREF = "controlShortcuts";

    private final String toggleShortcuts;
    private final LinkedHashMap<String,String> byAction;

    public ShortcutBindings(String toggleString,String controlString) {
        toggleShortcuts = ShortcutMatcher.normalize(toggleString);
        byAction = parse(controlString);
    }

    /** Returns the stable action-to-chord map. Chords use comma-separated key codes. */
    public Map<String,String> controls() { return Collections.unmodifiableMap(new LinkedHashMap<>(byAction)); }

    public String shortcuts(String action) {
        if (TOGGLE.equals(action)) return toggleShortcuts;
        String chord = byAction.get(action);
        return chord == null ? "" : chord;
    }

    /** Returns the first conflicting action, preferring dashboard, or an empty string. */
    public String conflict(String action,int[] keys,String replacingChord) {
        String candidate = normalizeInputChord(keys);
        boolean dashboard = TOGGLE.equals(action);
        if (candidate.isEmpty() || (!dashboard && !isControlAction(action))) return "";
        Set<Integer> proposed = keySet(candidate);
        String replacing = normalizeSingleChord(replacingChord);
        boolean replaced = false;
        for (String chord : toggleShortcuts.split(";")) {
            if (dashboard && !replaced && chord.equals(replacing)) { replaced = true; continue; }
            if (!chord.isEmpty() && proposed.equals(keySet(chord))) return TOGGLE;
        }
        for (Map.Entry<String,String> entry : byAction.entrySet()) {
            if (!dashboard && entry.getKey().equals(action)) continue;
            if (proposed.equals(keySet(entry.getValue()))) return entry.getKey();
        }
        return "";
    }

    /** True when this complete chord is a strict prefix of a longer configured chord. */
    public boolean waitsForRelease(String chord) {
        String normalized=normalizeSingleChord(chord);
        if(normalized.isEmpty())return false;
        Set<Integer> candidate=keySet(normalized);
        for(String other:toggleShortcuts.split(";"))if(isProperSubset(candidate,keySet(other)))return true;
        for(String other:byAction.values())if(isProperSubset(candidate,keySet(other)))return true;
        return false;
    }

    /** Replaces this control's binding and returns canonical controls serialization. */
    public String put(String action,int[] keys,String replacingChord) {
        String candidate = normalizeInputChord(keys);
        if (!isControlAction(action) || candidate.isEmpty())
            throw new IllegalArgumentException("Choose a valid control and one to three supported buttons.");
        String conflict = conflict(action,keys,replacingChord);
        if (!conflict.isEmpty()) throw new IllegalArgumentException("This binding conflicts with " + conflict + ".");
        LinkedHashMap<String,String> next = new LinkedHashMap<>(byAction);
        next.put(action,candidate);
        return serialize(next);
    }

    /** Removes a control binding only when its currently saved chord matches exactly. */
    public String remove(String action,String chord) {
        String normalized = normalizeSingleChord(chord);
        if (normalized.isEmpty() || !normalized.equals(byAction.get(action))) return serialize(byAction);
        LinkedHashMap<String,String> next = new LinkedHashMap<>(byAction);
        next.remove(action);
        return serialize(next);
    }

    /** Canonical format: action=chord;action=chord. Legacy extra chords are ignored. */
    public static String normalizeControls(String serialized) { return serialize(parse(serialized)); }

    private static LinkedHashMap<String,String> parse(String serialized) {
        LinkedHashMap<String,String> result = new LinkedHashMap<>();
        if (serialized == null) return result;
        for (String item : serialized.split(";")) {
            int equals = item.indexOf('=');
            if (equals <= 0 || equals == item.length()-1) continue;
            String action = item.substring(0,equals).trim();
            if (!isControlAction(action) || result.containsKey(action)) continue;
            String chord = normalizeSingleChord(item.substring(equals+1));
            if (!chord.isEmpty()) result.put(action,chord);
        }
        return result;
    }

    private static boolean isControlAction(String action) {
        if (action == null || action.isEmpty() || TOGGLE.equals(action)) return false;
        return ShortcutActions.valid(action,null) || ControlCatalog.isApp(action);
    }

    private static String normalizeInputChord(int[] values) {
        if (values == null || values.length < 1 || values.length > 3) return "";
        Set<Integer> unique = new TreeSet<>();
        for (int key : values) if (key <= 0 || !unique.add(key)) return "";
        StringBuilder encoded = new StringBuilder();
        for (int key : unique) { if (encoded.length()>0) encoded.append(','); encoded.append(key); }
        return normalizeSingleChord(encoded.toString());
    }

    private static String normalizeSingleChord(String chord) {
        if (chord == null) return "";
        String normalized = ShortcutMatcher.normalize(chord);
        return normalized.indexOf(';') < 0 ? normalized : "";
    }

    private static Set<Integer> keySet(String chord) {
        Set<Integer> result = new TreeSet<>();
        if (chord != null) for (String key : chord.split(",")) try { result.add(Integer.parseInt(key)); }
        catch (NumberFormatException ignored) { return Collections.emptySet(); }
        return result;
    }

    private static boolean isProperSubset(Set<Integer> first,Set<Integer> second) {
        return !first.isEmpty() && first.size()<second.size() && second.containsAll(first);
    }

    private static String serialize(Map<String,String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String,String> entry : values.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            if (result.length()>0) result.append(';');
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }
}
