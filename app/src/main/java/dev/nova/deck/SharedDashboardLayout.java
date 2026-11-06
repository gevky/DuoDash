package dev.nova.deck;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Shared dashboard arrangement, deliberately separate from per-output controls and canvas. */
public final class SharedDashboardLayout {
    public static final int VERSION = 1;
    public static final String PREFIX = "sharedDashboard";
    public static final String VERSION_KEY = PREFIX + ":version";
    public static final String SOURCE_KEY = PREFIX + ":sourceProfile";

    public static final String ORDER = "order";
    public static final String HIDDEN = "hidden";
    public static final String WIDE = "wide";
    /** Optional since beta30; absent legacy values mean normal size. */
    public static final String SMALL = "small";
    /** Optional compact per-control size map, independent of legacy small/wide metadata. */
    public static final String SIZES = "sizes";
    public static final String ACTION_COLUMNS = "actionColumns";
    public static final String HORIZONTAL = "horizontal";
    public static final String COMPACT = "compact";
    public static final String FPS_SCREEN = "fpsScreen";
    public static final String PINNED = "pinned";
    public static final String LAYOUT_VERSION = "layoutVersion";

    private static final String[] FIELDS = {
        ORDER, HIDDEN, WIDE, ACTION_COLUMNS, HORIZONTAL, COMPACT,
        FPS_SCREEN, PINNED, LAYOUT_VERSION
    };

    /**
     * Returns a one-time copy plan from an explicitly selected legacy profile.
     * The input and every other output profile remain untouched. An existing
     * current or newer shared version is never overwritten.
     */
    public static MigrationPlan planMigration(
        Map<String, String> current,
        String sourceProfile,
        Map<String, String> resolvedSourceLayout
    ) {
        if (current == null) throw new IllegalArgumentException("Current preferences are required");
        if (sourceProfile == null || !(sourceProfile.equals("handheld") || sourceProfile.startsWith("output:"))) {
            throw new IllegalArgumentException("Choose a saved output profile explicitly");
        }
        return plan(current, sourceProfile, resolvedSourceLayout);
    }

    /** Defaults are safe to seed only for a fresh installation with an empty shared store. */
    public static MigrationPlan planDefaults(
        Map<String, String> current,
        Map<String, String> defaults
    ) {
        if (current == null) throw new IllegalArgumentException("Current preferences are required");
        return plan(current, "portable-defaults", defaults);
    }

    /**
     * Selects a source only when layouts agree, there is a sole profile, or
     * one profile is the only more-customized outlier from a shared baseline.
     * Two conflicting profiles are deliberately ambiguous.
     */
    public static String selectUniqueCustomProfile(Map<String, Map<String, String>> profiles) {
        if (profiles == null || profiles.isEmpty()) return null;
        List<String> keys = new ArrayList<>(profiles.keySet());
        Collections.sort(keys);
        if (keys.size() == 1) return keys.get(0);

        Map<String, List<String>> bySignature = new HashMap<>();
        for (String key : keys) {
            Map<String, String> layout = profiles.get(key);
            if (layout == null) return null;
            String signature = signature(layout);
            bySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(key);
        }
        if (bySignature.size() == 1) return keys.get(0);
        if (bySignature.size() != 2) return null;

        List<String> singleton = null;
        List<String> baseline = null;
        for (List<String> group : bySignature.values()) {
            if (group.size() == 1) singleton = group;
            else baseline = group;
        }
        if (singleton == null || baseline == null || baseline.size() < 2) return null;
        Map<String, String> candidate = profiles.get(singleton.get(0));
        Map<String, String> common = profiles.get(baseline.get(0));
        return customizationScore(candidate) > customizationScore(common) ? singleton.get(0) : null;
    }

    private static String signature(Map<String, String> layout) {
        StringBuilder result = new StringBuilder();
        String[] comparable = { ORDER, HIDDEN, WIDE, ACTION_COLUMNS, HORIZONTAL, COMPACT, PINNED };
        for (String field : comparable) result.append(field).append('=').append(layout.get(field)).append('\n');
        result.append(SMALL).append('=').append(layout.getOrDefault(SMALL,""));
        result.append(SIZES).append('=').append(layout.getOrDefault(SIZES,""));
        return result.toString();
    }

    private static int customizationScore(Map<String, String> layout) {
        int score = 0;
        if (!ControlCatalog.defaultOrder().equals(layout.get(ORDER))) score++;
        if (!ControlCatalog.defaultHidden().equals(layout.get(HIDDEN))) score++;
        if (!"".equals(layout.get(WIDE))) score++;
        if (!"".equals(layout.getOrDefault(SMALL,""))) score++;
        if (!"".equals(layout.getOrDefault(SIZES,""))) score++;
        if (!"4".equals(layout.get(ACTION_COLUMNS))) score++;
        if (!"true".equals(layout.get(HORIZONTAL))) score++;
        if (!"true".equals(layout.get(COMPACT))) score++;
        if (!"[]".equals(layout.get(PINNED))) score++;
        return score;
    }

    private static MigrationPlan plan(
        Map<String, String> current,
        String sourceProfile,
        Map<String, String> resolvedSourceLayout
    ) {
        int version = parseInt(current.get(VERSION_KEY), 0);
        if (version >= VERSION) return MigrationPlan.alreadyInitialized(version);
        if (resolvedSourceLayout == null) throw new IllegalArgumentException("Source layout is required");

        Map<String, String> writes = new LinkedHashMap<>();
        for (String field : FIELDS) {
            String value = resolvedSourceLayout.get(field);
            if (value == null) throw new IllegalArgumentException("Missing source layout field: " + field);
            writes.put(sharedFieldKey(field), value);
        }
        if(resolvedSourceLayout.containsKey(SMALL))writes.put(sharedFieldKey(SMALL),resolvedSourceLayout.get(SMALL));
        if(resolvedSourceLayout.containsKey(SIZES))writes.put(sharedFieldKey(SIZES),resolvedSourceLayout.get(SIZES));
        writes.put(SOURCE_KEY, sourceProfile);
        writes.put(VERSION_KEY, Integer.toString(VERSION));
        return new MigrationPlan(sourceProfile, writes, false);
    }

    /** The persisted key used for a field after explicit migration. */
    public static String sharedFieldKey(String field) {
        if (SMALL.equals(field)||SIZES.equals(field)) return PREFIX + ":" + field;
        for (String candidate : FIELDS) if (candidate.equals(field)) return PREFIX + ":" + field;
        throw new IllegalArgumentException("Unknown dashboard layout field: " + field);
    }

    public static String storageProfile(String profile,boolean initialized) {
        if(profile==null||profile.isEmpty())throw new IllegalArgumentException("Output profile is required");
        return initialized?PREFIX:profile;
    }

    /** Resolve a field key without changing legacy fallback behavior before migration. */
    public static String storageKey(String profile, String field, boolean initialized) {
        sharedFieldKey(field);
        return storageProfile(profile,initialized) + ":" + field;
    }

    public static boolean initialized(Map<String, String> values) {
        return values != null && parseInt(values.get(VERSION_KEY), 0) >= VERSION;
    }

    /** Never seed a portable default over an un-migrated legacy layout. */
    public static boolean shouldSeedPortableDefaults(boolean sharedInitialized, boolean hasLegacyLayouts) {
        return !sharedInitialized && !hasLegacyLayouts;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static final class MigrationPlan {
        public final String sourceProfile;
        public final Map<String, String> writes;
        public final boolean alreadyInitialized;

        private MigrationPlan(String sourceProfile, Map<String, String> writes, boolean alreadyInitialized) {
            this.sourceProfile = sourceProfile;
            this.writes = Collections.unmodifiableMap(new LinkedHashMap<>(writes));
            this.alreadyInitialized = alreadyInitialized;
        }

        private static MigrationPlan alreadyInitialized(int version) {
            return new MigrationPlan(null, Collections.singletonMap(VERSION_KEY, Integer.toString(version)), true);
        }
    }

    private SharedDashboardLayout() {}
}
