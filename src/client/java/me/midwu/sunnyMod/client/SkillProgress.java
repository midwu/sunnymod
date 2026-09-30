package me.midwu.sunnyMod.client;

/** Live progression state parsed from the server's Skills boss/progression bar. */
public record SkillProgress(
        String name,
        int level,
        double currentXp,
        double requiredXp,
        float percent,
        long updatedAt
) {
    public boolean isMaxed() {
        return requiredXp <= 0 || currentXp >= requiredXp;
    }

    public double remainingXp() {
        return Math.max(0.0, requiredXp - currentXp);
    }

    public double normalizedPercent() {
        if (requiredXp <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, currentXp / requiredXp));
    }
}
