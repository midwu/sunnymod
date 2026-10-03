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
 * Live Skills model plus the analytics layer used by the HUD/dashboard.
 * /skills is the authoritative snapshot; progression boss bars provide
 * high-frequency updates for the currently active skill.
 */
public final class SkillsDataStore {
    private static final long CURRENT_RATE_WINDOW_MS = 1_000L;
    private static final long RATE_HISTORY_WINDOW_MS = 60_000L;
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
    private static long sessionStartTime;
    private static int sessionXpEvents;
    private static int sessionLevelUps;
    private static double sessionPeakXpPerSecond;
    private static double observedMoney;
    private static double observedJobXp;

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
            update(name, level, current, required, percent);
            activeSkillName = name;
            activeSkillUpdatedAt = System.currentTimeMillis();
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static synchronized boolean updateFromMenu(
            String rawSkillName, int level, double currentXp, double requiredXp) {
        String name = canonicalName(rawSkillName);
        if (name == null) return false;
        float percent = requiredXp <= 0 ? 1.0f :
                (float) Math.max(0.0, Math.min(1.0, currentXp / requiredXp));
        update(name, level, currentXp, requiredXp, percent);
        return true;
    }

    private static void update(String name, int level, double current,
                               double required, float percent) {
        long now = System.currentTimeMillis();
        if (sessionStartTime == 0L) sessionStartTime = now;

        RateState state = RATES.computeIfAbsent(name, ignored -> new RateState(now));
        SkillProgress previous = SKILLS.get(name);
        if (previous != null) {
            if (level > previous.level()) {
                sessionLevelUps++;
                SkillsDebug.log("SKILLS_LEVEL_UP", name + " " + previous.level() + " -> " + level);
                SkillsDebug.feedback(name + " leveled up: " + previous.level() + " -> " + level);
            }
            updateRate(previous, level, current, now, state);
        } else {
            state.lastSampleTime = now;
            state.lastLevel = level;
            state.lastXp = current;
        }
        SKILLS.put(name, new SkillProgress(name, level, current, required, percent, now));
    }

    private static void updateRate(SkillProgress previous, int level, double current,
                                   long now, RateState state) {
        if (state.lastSampleTime == 0L) {
            state.lastSampleTime = now;
            state.lastLevel = previous.level();
            state.lastXp = previous.currentXp();
            return;
        }

        double delta;
        if (level == state.lastLevel) {
            delta = current - state.lastXp;
            if (delta < 0) delta = 0;
        } else if (level > state.lastLevel) {
            delta = Math.max(0.0, previous.requiredXp() - state.lastXp) + current;
        } else {
            delta = 0.0;
        }

        long elapsed = now - state.lastSampleTime;
        if (elapsed > 0 && delta > 0) {
            state.window.add(new RateSample(now, delta));
            state.sessionXp += delta;
            sessionXpEvents++;
            sessionPeakXpPerSecond = Math.max(sessionPeakXpPerSecond,
                    delta / (elapsed / 1000.0));
        }
        pruneWindow(state, now);
        state.lastSampleTime = now;
        state.lastLevel = level;
        state.lastXp = current;
    }

    private static void pruneWindow(RateState state, long now) {
        long cutoff = now - RATE_HISTORY_WINDOW_MS;
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

    public static boolean isHudFresh(SkillProgress skill) {
        return skill != null && System.currentTimeMillis() - skill.updatedAt() <= HUD_HIDE_DELAY_MS;
    }

    public static synchronized String activeSkillName() {
        if (activeSkillName == null) return null;
        if (System.currentTimeMillis() - activeSkillUpdatedAt > HUD_HIDE_DELAY_MS) return null;
        return activeSkillName;
    }

    public static synchronized long activeSkillAgeMs() {
        if (activeSkillName == null) return Long.MAX_VALUE;
        return Math.max(0L, System.currentTimeMillis() - activeSkillUpdatedAt);
    }

    public static synchronized long sessionStartTime() {
        return sessionStartTime;
    }

    public static synchronized long sessionDurationMs() {
        if (sessionStartTime == 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - sessionStartTime);
    }

    public static synchronized double totalSessionXp() {
        return RATES.values().stream().mapToDouble(state -> state.sessionXp).sum();
    }

    public static synchronized int sessionXpEvents() { return sessionXpEvents; }
    public static synchronized int sessionLevelUps() { return sessionLevelUps; }
    public static synchronized double sessionPeakXpPerSecond() { return sessionPeakXpPerSecond; }
    public static synchronized double observedMoney() { return observedMoney; }
    public static synchronized double observedJobXp() { return observedJobXp; }

    public static synchronized void recordObservedMoney(double amount) {
        if (sessionStartTime > 0L && amount > 0.0) observedMoney += amount;
    }

    public static synchronized void recordObservedJobXp(double amount) {
        if (sessionStartTime > 0L && amount > 0.0) observedJobXp += amount;
    }

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

    public static synchronized double averageXpPerHour(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null || state.sessionStartTime == 0L) return 0.0;
        long elapsed = System.currentTimeMillis() - state.sessionStartTime;
        if (elapsed < 10_000L || state.sessionXp <= 0.0) return 0.0;
        return state.sessionXp * 3_600_000.0 / elapsed;
    }

    public static synchronized double averageSessionXpPerHour() {
        if (sessionStartTime == 0L || totalSessionXp() <= 0.0) return 0.0;
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed < 1_000L) return 0.0;
        return totalSessionXp() * 3_600_000.0 / elapsed;
    }

    public static synchronized double currentSessionXpPerSecond() {
        if (sessionStartTime == 0L) return 0.0;
        long now = System.currentTimeMillis();
        double xp = 0.0;
        for (RateState state : RATES.values()) {
            xp += state.window.stream()
                    .filter(sample -> now - sample.time >= 0L
                            && now - sample.time < CURRENT_RATE_WINDOW_MS)
                    .mapToDouble(sample -> sample.xp)
                    .sum();
        }
        return xp / (CURRENT_RATE_WINDOW_MS / 1000.0);
    }

    public static synchronized double xpPerSecond(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null) return 0.0;
        long now = System.currentTimeMillis();
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

    public static synchronized long sessionDurationMs(String name) {
        RateState state = RATES.get(canonicalName(name));
        if (state == null || state.sessionStartTime == 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - state.sessionStartTime);
    }

    public static synchronized long etaToNextLevelSeconds(String name) {
        SkillProgress skill = get(name);
        if (skill == null || skill.isMaxed()) return -1L;
        double perSecond = averageXpPerSecond(name);
        if (perSecond <= 0.0) return -1L;
        return Math.max(0L, (long) Math.ceil(skill.remainingXp() / perSecond));
    }

    public static synchronized SkillSessionSummary buildSessionSummary(long endedAt) {
        Map<String, Double> bySkill = new LinkedHashMap<>();
        for (String name : ORDER) {
            double xp = sessionXp(name);
            if (xp > 0.0) bySkill.put(name, xp);
        }
        for (SkillProgress skill : snapshot()) {
            if (!bySkill.containsKey(skill.name())) {
                double xp = sessionXp(skill.name());
                if (xp > 0.0) bySkill.put(skill.name(), xp);
            }
        }
        return new SkillSessionSummary(
                sessionStartTime,
                endedAt,
                sessionDurationMs(),
                totalSessionXp(),
                sessionXpEvents,
                sessionLevelUps,
                sessionPeakXpPerSecond,
                observedMoney,
                observedJobXp,
                bySkill);
    }

    /** Saves the current session without clearing live data. */
    public static synchronized void saveCurrentSession() {
        if (sessionStartTime == 0L || totalSessionXp() <= 0.0) return;
        SkillsSessionHistory.add(buildSessionSummary(System.currentTimeMillis()));
    }

    /** Ends the current session, saving it when it contains XP. */
    public static synchronized void endSession() {
        saveCurrentSession();
        RATES.clear();
        sessionStartTime = 0L;
        sessionXpEvents = 0;
        sessionLevelUps = 0;
        sessionPeakXpPerSecond = 0.0;
        observedMoney = 0.0;
        observedJobXp = 0.0;
        activeSkillName = null;
        activeSkillUpdatedAt = 0L;
        SkillsResourceTracker.reset();
    }

    /** Saves the current session locally, then starts a clean analytics session. */
    public static synchronized void resetRates() {
        endSession();
    }

    public static String formatXp(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return String.format(Locale.US, "%,.0f", value);
        }
        return String.format(Locale.US, "%,.2f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static String formatRate(double xpPerHour) {
        if (xpPerHour <= 0.0) return "-- XP/hr";
        if (xpPerHour >= 1_000_000.0) return String.format(Locale.US, "%,.2fM XP/hr", xpPerHour / 1_000_000.0);
        if (xpPerHour >= 1000.0) return String.format(Locale.US, "%,.1fK XP/hr", xpPerHour / 1000.0);
        return String.format(Locale.US, "%,.0f XP/hr", xpPerHour);
    }

    public static String formatRatePerSecond(double xpPerSecond) {
        if (xpPerSecond <= 0.0) return "0 XP/s";
        if (xpPerSecond >= 1000.0) return String.format(Locale.US, "%,.1fK XP/s", xpPerSecond / 1000.0);
        if (xpPerSecond >= 10.0) return String.format(Locale.US, "%,.1f XP/s", xpPerSecond);
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

    private static String canonicalName(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().replaceAll("\\s+", " ");
        Matcher menu = MENU_SKILL.matcher(clean);
        if (menu.matches()) clean = menu.group(1).trim();
        for (String known : ORDER) if (known.equalsIgnoreCase(clean)) return known;
        return null;
    }

    private static double parseNumber(String raw) { return Double.parseDouble(raw.replace(",", "")); }

    private static final class RateState {
        final java.util.ArrayDeque<RateSample> window = new java.util.ArrayDeque<>();
        long sessionStartTime;
        long lastSampleTime;
        int lastLevel;
        double lastXp;
        double sessionXp;
        RateState(long now) { sessionStartTime = now; }
    }

    private record RateSample(long time, double xp) {}
}
