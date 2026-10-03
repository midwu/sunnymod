package me.midwu.sunnyMod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent local history for completed Skills sessions. */
public final class SkillsSessionHistory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("sunnyMod")
            .resolve("skills_sessions.json");
    private static final Type LIST_TYPE = new TypeToken<List<SkillSessionSummary>>() {}.getType();
    private static final int MAX_SESSIONS = 50;
    private static final List<SkillSessionSummary> HISTORY = new ArrayList<>();
    private static boolean loaded;

    private SkillsSessionHistory() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            try (Reader reader = Files.newBufferedReader(FILE)) {
                List<SkillSessionSummary> saved = GSON.fromJson(reader, LIST_TYPE);
                if (saved != null) {
                    HISTORY.clear();
                    HISTORY.addAll(saved);
                    trim();
                }
            }
        } catch (Exception e) {
            System.err.println("[SunnyMod Skills] Failed to load session history: " + e.getMessage());
        }
    }

    public static synchronized void add(SkillSessionSummary summary) {
        if (summary == null || summary.totalSkillXp() <= 0.0) return;
        load();
        HISTORY.add(0, summary);
        trim();
        save();
    }

    public static synchronized List<SkillSessionSummary> snapshot() {
        load();
        return Collections.unmodifiableList(new ArrayList<>(HISTORY));
    }

    public static synchronized void clear() {
        load();
        HISTORY.clear();
        save();
    }

    private static void trim() {
        while (HISTORY.size() > MAX_SESSIONS) HISTORY.remove(HISTORY.size() - 1);
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(HISTORY, LIST_TYPE, writer);
            }
        } catch (Exception e) {
            System.err.println("[SunnyMod Skills] Failed to save session history: " + e.getMessage());
        }
    }
}
