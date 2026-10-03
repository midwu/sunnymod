package me.midwu.sunnyMod.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw, server-observed entries from the /skills menu. */
public final class SkillsMenuCatalog {
    private static final Map<String, SkillsMenuEntry> ENTRIES = new LinkedHashMap<>();
    private static long updatedAt;

    private SkillsMenuCatalog() {}

    public static synchronized void update(String name, List<String> tooltip) {
        if (name == null || name.isBlank()) return;
        ENTRIES.put(name, new SkillsMenuEntry(name, tooltip == null ? List.of() : tooltip));
        updatedAt = System.currentTimeMillis();
    }

    public static synchronized List<SkillsMenuEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
    }

    public static synchronized long updatedAt() { return updatedAt; }

    public static synchronized void clear() {
        ENTRIES.clear();
        updatedAt = 0L;
    }

    public record SkillsMenuEntry(String name, List<String> tooltip) {
        public SkillsMenuEntry {
            tooltip = Collections.unmodifiableList(new ArrayList<>(tooltip == null ? List.of() : tooltip));
        }
    }
}
