package me.midwu.sunnyMod.client;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable summary of a completed Skills analytics session. */
public record SkillSessionSummary(
        long startedAt,
        long endedAt,
        long durationMs,
        double totalSkillXp,
        int xpEvents,
        int levelUps,
        double peakXpPerSecond,
        double observedMoney,
        double observedJobXp,
        Map<String, Double> skillXp
) {
    public SkillSessionSummary {
        skillXp = new LinkedHashMap<>(skillXp == null ? Map.of() : skillXp);
    }

    public String duration() {
        return SkillsDataStore.formatDuration(durationMs);
    }
}
