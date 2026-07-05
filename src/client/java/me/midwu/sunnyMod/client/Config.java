package me.midwu.sunnyMod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("sunnymod-config.json");

    // ── Feature toggles ───────────────────────────────────────────────────────
    public boolean shopLoggerEnabled       = true;
    public boolean earningsDetectorEnabled = true;
    public boolean fishingLoggerEnabled    = true;

    // ── Feedback master switch ────────────────────────────────────────────────
    public boolean feedbackMessages = true;

    // ── Individual feedback toggles ───────────────────────────────────────────

    // ShopLogger
    public boolean feedbackShopAdded       = true;
    public boolean feedbackShopUpdated     = true;
    public boolean feedbackShopNotSign     = true;
    public boolean feedbackShopSaveFailed  = true;
    public boolean feedbackWarpSet         = true;

    // EarningsDetector
    public boolean feedbackEarningsPerSale = true;

    // FishingLogger
    public boolean feedbackFishingExported = true;
    public boolean feedbackFishingFailed   = true;

    // Commands
    public boolean feedbackEarningsReset   = true;
    public boolean feedbackFishingOffset   = true;

    // ── HUD panel positions ───────────────────────────────────────────────────
    public int fishingX  = 6;
    public int fishingY  = 6;
    public int earningsX = 6;
    public int earningsY = 36;
    public int shopX     = 6;
    public int shopY     = 66;

    // ── HUD panel visibility ──────────────────────────────────────────────────
    public boolean fishingVisible  = true;
    public boolean earningsVisible = true;
    public boolean shopVisible     = true;

    // ── HUD panel order ───────────────────────────────────────────────────────
    public List<String> panelOrder = Arrays.asList("fishing", "earnings", "shop");

    // ── Fishing timer ─────────────────────────────────────────────────────────
    public int timeOffset = 0;

    // ── Auto-hide delays (minutes) ────────────────────────────────────────────
    public int earningsHideDelayMinutes = 5;
    public int shopHideDelayMinutes     = 5;

    // ── Fishing session (persisted) ───────────────────────────────────────────
    public String fishingMethod = "";
    public String fishingRod    = "";

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static Config instance;

    public static Config get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        File file = CONFIG_PATH.toFile();
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                instance = GSON.fromJson(reader, Config.class);
                if (instance == null)
                    instance = new Config();
                if (instance.panelOrder == null)
                    instance.panelOrder = Arrays.asList("fishing", "earnings", "shop");
            } catch (IOException e) {
                System.err.println("[SunnyMod] Failed to load config: " + e.getMessage());
                instance = new Config();
            }
        } else {
            instance = new Config();
            save();
        }
    }

    public static void save() {
        try (Writer writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            System.err.println("[SunnyMod] Failed to save config: " + e.getMessage());
        }
    }

    public void resetHudDefaults() {
        fishingX  = 6;  fishingY  = 6;
        earningsX = 6;  earningsY = 36;
        shopX     = 6;  shopY     = 66;
        fishingVisible  = true;
        earningsVisible = true;
        shopVisible     = true;
        panelOrder = Arrays.asList("fishing", "earnings", "shop");
    }

    public long earningsHideDelayMs() {
        return (long) earningsHideDelayMinutes * 60 * 1000L;
    }

    public long shopHideDelayMs() {
        return (long) shopHideDelayMinutes * 60 * 1000L;
    }

    // ── Feedback helpers ──────────────────────────────────────────────────────
    // Each returns true only if both the master switch AND the individual toggle are on

    public boolean showShopAdded()       { return feedbackMessages && feedbackShopAdded; }
    public boolean showShopUpdated()     { return feedbackMessages && feedbackShopUpdated; }
    public boolean showShopNotSign()     { return feedbackMessages && feedbackShopNotSign; }
    public boolean showShopSaveFailed()  { return feedbackMessages && feedbackShopSaveFailed; }
    public boolean showWarpSet()         { return feedbackMessages && feedbackWarpSet; }
    public boolean showEarningsPerSale() { return feedbackMessages && feedbackEarningsPerSale; }
    public boolean showFishingExported() { return feedbackMessages && feedbackFishingExported; }
    public boolean showFishingFailed()   { return feedbackMessages && feedbackFishingFailed; }
    public boolean showEarningsReset()   { return feedbackMessages && feedbackEarningsReset; }
    public boolean showFishingOffset()   { return feedbackMessages && feedbackFishingOffset; }
}