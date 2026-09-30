package me.midwu.sunnyMod.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live Skills model. It intentionally only accepts the exact boss-bar format
 * we observed during the data-collection phase:
 *   Skill Name | Level 79 | 48,158.08/98,607.8 XP
 *
 * Job bars and ordinary Minecraft boss bars are rejected by the allow-list.
 */
public final class SkillsDataStore {
    private static final Pattern SKILL_BAR = Pattern.compile(
            "^\\s*(.+?)\\s*\\|\\s*Level\\s+(\\d+)\\s*\\|\\s*([0-9,.]+)\\s*/\\s*([0-9,.]+)\\s*XP\\s*$",
            Pattern.CASE_INSENSITIVE);

    // These are the skill names established by the Skills container data.
    // Slayer is included because it is part of the observed Skills system;
    // job bars are deliberately not included here.
    private static final List<String> ORDER = List.of(
            "Mining", "Woodcutting", "Fishing", "Digging", "Farming",
            "Taming", "Alchemy", "Enchanting", "Slayer"
    );

    private static final Map<String, SkillProgress> SKILLS = new LinkedHashMap<>();

    private SkillsDataStore() {}

    public static synchronized boolean updateFromBossBar(String rawName, float percent) {
        if (rawName == null) return false;

        Matcher matcher = SKILL_BAR.matcher(rawName.trim());
        if (!matcher.matches()) return false;

        String name = canonicalName(matcher.group(1));
        if (name == null) return false;

        try {
            int level = Integer.parseInt(matcher.group(2));
            double current = parseNumber(matcher.group(3));
            double required = parseNumber(matcher.group(4));

            SKILLS.put(name, new SkillProgress(
                    name, level, current, required, percent, System.currentTimeMillis()));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static synchronized List<SkillProgress> snapshot() {
        List<SkillProgress> result = new ArrayList<>();
        for (String name : ORDER) {
            SkillProgress progress = SKILLS.get(name);
            if (progress != null) result.add(progress);
        }
        // Unknown-but-valid skill names are retained at the end, so a server
        // addition does not silently disappear from the UI.
        SKILLS.values().stream()
                .filter(s -> !ORDER.contains(s.name()))
                .sorted(Comparator.comparing(SkillProgress::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(result::add);
        return result;
    }

    public static synchronized SkillProgress get(String name) {
        return SKILLS.get(canonicalName(name));
    }

    public static synchronized Collection<SkillProgress> values() {
        return List.copyOf(SKILLS.values());
    }

    private static String canonicalName(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().replaceAll("\\s+", " ");
        for (String known : ORDER) {
            if (known.equalsIgnoreCase(clean)) return known;
        }
        return null;
    }

    private static double parseNumber(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }

    public static String formatXp(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return String.format(Locale.US, "%,.0f", value);
        }
        return String.format(Locale.US, "%,.2f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }
}
