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
 * Live Skills model. The server's /skills menu is the authoritative snapshot
 * for all skills; the progression boss bar supplies high-frequency updates
 * for whichever skill is currently active.
 */
public final class SkillsDataStore {
    /**
     * The HUD "current XP/s" rate is intentionally very short-lived.
     * XP gained more than one second ago no longer contributes to this value.
     * This makes the HUD drop back to 0 XP/s shortly after the player stops
     * gaining skill XP, instead of behaving like a session/rolling average.
     */
    private static final long CURRENT_RATE_WINDOW_MS = 1_000L;
    public static final long HUD_HIDE_DELAY_MS = 5_000L;
    private static final Pattern SKILL_BAR = Pattern.compile(
            "^\\s*(.+?)\\s*\\|\\s*Level\\s+(\\d+)\\s*\\|\\s*([0-9,.]+)\\s*/\\s*([0-9,.]+)\\s*XP\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MENU_SKILL = Pattern.compile(
            "^\\s*(.+?)\\s+Skill\\s*$", Pattern.CASE_INSENSITIVE);

    private static final List<String> ORDER = List.of(
            "Mining", "Woodcutting", "Fishing", "Digging", "Farming",
            "Taming", "Alchemy", "Enchanting", "Slayer"
    );

    private static final Map<String, SkillProgress> SKILLS = new LinkedHashMap<>();
    private static final Map<String, RateState> RATES = new LinkedHashMap<>();
    private static String activeSkillName;
    private static long activeSkillUpdatedAt;

    private SkillsDataStore() {}

    /** Called by the live boss/progression bar. */
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
            update(name, level, current, required, percent);
            activeSkillName = name;
            activeSkillUpdatedAt = System.currentTimeMillis();
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** Called by the /skills container scanner. */
    public static synchronized boolean updateFromMenu(
            String rawSkillName, int level, double currentXp, double requiredXp) {
        String name = canonicalName(rawSkillName);
        if (name == null) return false;
        float percent = requiredXp <= 0 ? 1.0f :
                (float) Math.max(0.0, Math.min(1.0, currentXp / requiredXp));
        update(name, level, currentXp, requiredXp, percent);
        return true;
    }

    public static synchronized String activeSkillName() {
        if (activeSkillName == null) return null;
        if (System.currentTimeMillis() - activeSkillUpdatedAt > 5000L) return null;
        return activeSkillName;
    }

    public static synchronized long activeSkillAgeMs() {
        if (activeSkillName == null) return Long.MAX_VALUE;
        return Math.max(0L, System.currentTimeMillis() - activeSkillUpdatedAt);
    }

    private static void update(String name, int level, double current, double required, float percent) {
        long now = System.currentTimeMillis();
        RateState state = RATES.computeIfAbsent(name, ignored -> new RateState(now));
        SkillProgress previous = SKILLS.get(name);
        if (previous != null) {
            if (level > previous.level()) {
                SkillsDebug.log("SKILLS_LEVEL_UP",
                        name + " " + previous.level() + " -> " + level);
                SkillsDebug.feedback(name + " leveled up: " + previous.level() + " -> " + level);
            }
            updateRate(name, previous, level, current, required, now, state);
        } else {
            state.lastSampleTime = now;
            state.lastLevel = level;
            state.lastXp = current;
        }
        SKILLS.put(name, new SkillProgress(name, level, current, required, percent, now));
    }

    private static void updateRate(String name, SkillProgress previous, int level,
                                   double current, double required, long now, RateState state) {
        if (state.lastSampleTime == 0L) {
            state.lastSampleTime = now;
            state.lastLevel = previous.level();
            state.lastXp = previous.currentXp();
            return;
        }

        double delta;
        if (level == state.lastLevel) {
            delta = current - state.lastXp;
            if (delta < 0) {
                // A decrease without a level change is normally a UI reset or
                // stale packet. Do not turn it into negative XP/hour.
                delta = 0;
            }
        } else if (level > state.lastLevel) {
            // We may not receive the exact final pre-level-up bar value. Count
            // the visible remainder plus the new level's progress. This keeps
            // the HUD responsive; /skills snapshots can re-anchor the session.
            delta = Math.max(0.0, previous.requiredXp() - state.lastXp) + current;
        } else {
            delta = 0.0;
        }

        long elapsed = now - state.lastSampleTime;
        if (elapsed > 0 && delta > 0) {
            state.window.add(new RateSample(now, delta));
            state.sessionXp += delta;
        }
        pruneWindow(state, now);
        state.lastSampleTime = now;
        state.lastLevel = level;
        state.lastXp = current;
    }

    private static void pruneWindow(RateState state, long now) {
        long cutoff = now - 60_000L;
        while (!state.window.isEmpty() && state.window.peekFirst().time < cutoff) {
            state.window.removeFirst();
        }
    }

    public static synchronized List<SkillProgress> snapshot() {
        List<SkillProgress> result = new ArrayList<>();
        for (String name : ORDER) {
            SkillProgress progress = SKILLS.get(name);
            if (progress != null) result.add(progress);
        }
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

    /** Returns true while this skill has received a server/menu update recently enough for the HUD. */
    public static boolean isHudFresh(SkillProgress skill) {
        return skill != null && System.currentTimeMillis() - skill.updatedAt() <= HUD_HIDE_DELAY_MS;
    }

    /** Session duration since the first observed skill update. */
    public static synchronized long sessionDurationMs() {
        long earliest = Long.MAX_VALUE;
        for (RateState state : RATES.values()) {
            if (state.sessionStartTime > 0L) earliest = Math.min(earliest, state.sessionStartTime);
        }
        if (earliest == Long.MAX_VALUE) return 0L;
        return Math.max(0L, System.currentTimeMillis() - earliest);
    }

    /** Current rolling XP/hour, based on the last 60 seconds of observed XP. */
    public static synchronized double xpPerHour(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null) return 0.0;
        long now = System.currentTimeMillis();
        pruneWindow(state, now);
        if (state.window.isEmpty()) return 0.0;
        double xp = state.window.stream().mapToDouble(s -> s.xp).sum();
        long span = Math.max(1L, now - state.window.peekFirst().time);
        return xp * 3_600_000.0 / span;
    }

    public static synchronized double sessionXp(String name) {
        RateState state = RATES.get(canonicalName(name));
        return state == null ? 0.0 : state.sessionXp;
    }

    /** Average XP/hour over the whole observed session for this skill. */
    public static synchronized double averageXpPerHour(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null || state.sessionStartTime == 0L) return 0.0;
        long elapsed = System.currentTimeMillis() - state.sessionStartTime;
        if (elapsed < 10_000L || state.sessionXp <= 0.0) return 0.0;
        return state.sessionXp * 3_600_000.0 / elapsed;
    }

    public static synchronized long sessionDurationMs(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null || state.lastSampleTime == 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - state.sessionStartTime);
    }

    /**
     * Current XP/sec for the HUD.
     *
     * This is NOT the session average and NOT the 60-second XP/hour rate.
     * It is simply the amount of XP observed during the last second.
     * Once no XP has been observed for one second, this returns 0.
     */
    public static synchronized double xpPerSecond(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null) return 0.0;

        long now = System.currentTimeMillis();
        if (state.window.isEmpty()) return 0.0;

        // Do not mutate the 60-second history here. The same history is also
        // used by xpPerHour() and by session statistics. Just look at the
        // samples that fall inside the short current-rate window.
        double xp = state.window.stream()
                .filter(sample -> now - sample.time >= 0L
                        && now - sample.time < CURRENT_RATE_WINDOW_MS)
                .mapToDouble(sample -> sample.xp)
                .sum();

        return xp / (CURRENT_RATE_WINDOW_MS / 1000.0);
    }

    public static synchronized double averageXpPerSecond(String name) {
        return averageXpPerHour(name) / 3600.0;
    }

    /** Seconds until the next skill level at the current average XP/sec. */
    public static synchronized long etaToNextLevelSeconds(String name) {
        SkillProgress skill = get(name);
        if (skill == null || skill.isMaxed()) return -1L;
        double perSecond = averageXpPerSecond(name);
        if (perSecond <= 0.0) return -1L;
        return Math.max(0L, (long) Math.ceil(skill.remainingXp() / perSecond));
    }

    public static String formatRatePerSecond(double xpPerSecond) {
        if (xpPerSecond <= 0.0) return "0 XP/s";
        if (xpPerSecond >= 1000.0) {
            return String.format(Locale.US, "%,.1fK XP/s", xpPerSecond / 1000.0);
        }
        if (xpPerSecond >= 10.0) {
            return String.format(Locale.US, "%,.1f XP/s", xpPerSecond);
        }
        return String.format(Locale.US, "%.2f XP/s", xpPerSecond);
    }

    public static String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static String formatEta(long seconds) {
        if (seconds < 0) return "--";
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0) return String.format(Locale.US, "%dh %02dm", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%dm %02ds", minutes, secs);
        return String.format(Locale.US, "%ds", secs);
    }

    public static synchronized void resetRates() {
        RATES.clear();
        activeSkillName = null;
        activeSkillUpdatedAt = 0L;
    }

    private static String canonicalName(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().replaceAll("\\s+", " ");
        Matcher menu = MENU_SKILL.matcher(clean);
        if (menu.matches()) clean = menu.group(1).trim();
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

    public static String formatRate(double xpPerHour) {
        if (xpPerHour <= 0.0) return "-- XP/hr";
        if (xpPerHour >= 1000.0) {
            return String.format(Locale.US, "%,.1fK XP/hr", xpPerHour / 1000.0);
        }
        return String.format(Locale.US, "%,.0f XP/hr", xpPerHour);
    }

    private static final class RateState {
        final java.util.ArrayDeque<RateSample> window = new java.util.ArrayDeque<>();
        long sessionStartTime;
        long lastSampleTime;
        int lastLevel;
        double lastXp;
        double sessionXp;

        RateState(long now) {
            sessionStartTime = now;
        }
    }

    private record RateSample(long time, double xp) {}
}
