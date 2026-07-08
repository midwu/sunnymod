package me.midwu.sunnyMod.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::buildConfigScreen;
    }

    private Screen buildConfigScreen(Screen parent) {
        Config cfg = Config.get();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Sunny Mod Config"))
                .setSavingRunnable(Config::save);

        ConfigEntryBuilder eb = builder.entryBuilder();

        // ── General ───────────────────────────────────────────────────────────
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));

        general.addEntry(eb.startBooleanToggle(
                        Text.literal("Feedback Messages (master switch)"),
                        cfg.feedbackMessages)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Master on/off for all in-game feedback messages"))
                .setSaveConsumer(v -> cfg.feedbackMessages = v)
                .build());

        // ── Feature Toggles ───────────────────────────────────────────────────
        ConfigCategory features = builder.getOrCreateCategory(Text.literal("Features"));

        features.addEntry(eb.startBooleanToggle(
                        Text.literal("Shop Logger"),
                        cfg.shopLoggerEnabled)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Enable/disable the shop logging system"))
                .setSaveConsumer(v -> cfg.shopLoggerEnabled = v)
                .build());

        features.addEntry(eb.startBooleanToggle(
                        Text.literal("Earnings Detector"),
                        cfg.earningsDetectorEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Enable/disable earnings tracking"))
                .setSaveConsumer(v -> cfg.earningsDetectorEnabled = v)
                .build());

        features.addEntry(eb.startBooleanToggle(
                        Text.literal("Fishing Logger"),
                        cfg.fishingLoggerEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal(
                        "Enable/disable fishing contest logging. The fishing timer always works."))
                .setSaveConsumer(v -> cfg.fishingLoggerEnabled = v)
                .build());

        // ── Feedback ──────────────────────────────────────────────────────────
        ConfigCategory feedback = builder.getOrCreateCategory(Text.literal("Feedback"));

        feedback.addEntry(eb.startTextDescription(
                Text.literal("§eShop Logger messages")).build());

        feedback.addEntry(eb.startBooleanToggle(Text.literal("Shop: Added"), cfg.feedbackShopAdded)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show message when a new shop is added to the CSV"))
                .setSaveConsumer(v -> cfg.feedbackShopAdded = v).build());

        feedback.addEntry(eb.startBooleanToggle(Text.literal("Shop: Updated"), cfg.feedbackShopUpdated)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show message when an existing shop entry is updated"))
                .setSaveConsumer(v -> cfg.feedbackShopUpdated = v).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Shop: Not Looking at Sign"), cfg.feedbackShopNotSign)
                .setDefaultValue(true)
                .setTooltip(Text.literal(
                        "Show warning when a shop message fires but you are not looking at an oak wall sign"))
                .setSaveConsumer(v -> cfg.feedbackShopNotSign = v).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Shop: Save Failed"), cfg.feedbackShopSaveFailed)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show error when shop data fails to save to CSV"))
                .setSaveConsumer(v -> cfg.feedbackShopSaveFailed = v).build());

        feedback.addEntry(eb.startBooleanToggle(Text.literal("Shop: Warp Set"), cfg.feedbackWarpSet)
                .setDefaultValue(true)
                .setTooltip(Text.literal(
                        "Show message when the current warp is updated via /sunnymod shoplogger setwarp"))
                .setSaveConsumer(v -> cfg.feedbackWarpSet = v).build());

        feedback.addEntry(eb.startTextDescription(
                Text.literal("§eEarnings Detector messages")).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Earnings: Per Sale"), cfg.feedbackEarningsPerSale)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show message on every sale (can be noisy)"))
                .setSaveConsumer(v -> cfg.feedbackEarningsPerSale = v).build());

        feedback.addEntry(eb.startTextDescription(
                Text.literal("§eFishing Logger messages")).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Fishing: Exported to CSV"), cfg.feedbackFishingExported)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show message when fishing contest data is saved to CSV"))
                .setSaveConsumer(v -> cfg.feedbackFishingExported = v).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Fishing: Export Failed"), cfg.feedbackFishingFailed)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show error when fishing contest data fails to save"))
                .setSaveConsumer(v -> cfg.feedbackFishingFailed = v).build());

        feedback.addEntry(eb.startTextDescription(
                Text.literal("§eCommand messages")).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Command: Earnings Reset"), cfg.feedbackEarningsReset)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show confirmation when /sunnymod earnings reset is run"))
                .setSaveConsumer(v -> cfg.feedbackEarningsReset = v).build());

        feedback.addEntry(eb.startBooleanToggle(
                        Text.literal("Command: Fishing Offset"), cfg.feedbackFishingOffset)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Show confirmation when /sunnymod fishing adjusttime is run"))
                .setSaveConsumer(v -> cfg.feedbackFishingOffset = v).build());

        // ── HUD ───────────────────────────────────────────────────────────────
        ConfigCategory hud = builder.getOrCreateCategory(Text.literal("HUD"));

        hud.addEntry(eb.startIntSlider(
                        Text.literal("Earnings Auto-Hide (minutes)"),
                        cfg.earningsHideDelayMinutes, 1, 30)
                .setDefaultValue(5)
                .setTooltip(Text.literal("Hide the earnings panel after this many minutes of no sales"))
                .setSaveConsumer(v -> cfg.earningsHideDelayMinutes = v).build());

        hud.addEntry(eb.startIntSlider(
                        Text.literal("Shop Auto-Hide (minutes)"),
                        cfg.shopHideDelayMinutes, 1, 30)
                .setDefaultValue(5)
                .setTooltip(Text.literal("Hide the shop panel after this many minutes of no warp activity"))
                .setSaveConsumer(v -> cfg.shopHideDelayMinutes = v).build());

        hud.addEntry(eb.startIntSlider(
                        Text.literal("Sign HUD Auto-Hide (minutes)"),
                        cfg.signHideDelayMinutes, 1, 30)
                .setDefaultValue(5)
                .setTooltip(Text.literal("Hide the sign HUD after this many minutes of no shop activity"))
                .setSaveConsumer(v -> cfg.signHideDelayMinutes = v).build());

        hud.addEntry(eb.startBooleanToggle(
                        Text.literal("Fishing Panel Visible"), cfg.fishingVisible)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.fishingVisible = v).build());

        hud.addEntry(eb.startBooleanToggle(
                        Text.literal("Earnings Panel Visible"), cfg.earningsVisible)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.earningsVisible = v).build());

        hud.addEntry(eb.startBooleanToggle(
                        Text.literal("Shop Panel Visible"), cfg.shopVisible)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.shopVisible = v).build());

        hud.addEntry(eb.startBooleanToggle(
                        Text.literal("Sign HUD Visible"), cfg.signVisible)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.signVisible = v).build());

        hud.addEntry(eb.startTextDescription(
                        Text.literal("§eUse the button below to reset all panel positions to defaults."))
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Text.literal("Reset HUD to Defaults (toggle to confirm)"), false)
                .setDefaultValue(false)
                .setTooltip(Text.literal(
                        "Toggle ON and save to reset all panel positions and visibility to defaults"))
                .setSaveConsumer(v -> { if (v) cfg.resetHudDefaults(); }).build());

        hud.addEntry(eb.startTextDescription(
                        Text.literal("§eTo reposition panels, press §6H §ein-game or use /sunnymod hud edit"))
                .build());

        // ── Fishing ───────────────────────────────────────────────────────────
        ConfigCategory fishing = builder.getOrCreateCategory(Text.literal("Fishing"));

        fishing.addEntry(eb.startIntSlider(
                        Text.literal("Fishing Timer Offset (hours)"),
                        cfg.timeOffset, 0, 2)
                .setDefaultValue(0)
                .setTooltip(Text.literal("Adjust the fishing reset timer offset (0-2 hours)"))
                .setSaveConsumer(v -> cfg.timeOffset = v).build());

//        fishing.addEntry(eb.startStrField(
//                        Text.literal("Fishing Method"), cfg.fishingMethod)
//                .setDefaultValue("")
//                .setTooltip(Text.literal("Your fishing method (e.g. AFK, manual). Saved to CSV."))
//                .setSaveConsumer(v -> cfg.fishingMethod = v).build());
//
//        fishing.addEntry(eb.startStrField(
//                        Text.literal("Fishing Rod"), cfg.fishingRod)
//                .setDefaultValue("")
//                .setTooltip(Text.literal("Your fishing rod name. Saved to CSV."))
//                .setSaveConsumer(v -> cfg.fishingRod = v).build());

        // ── Resets ────────────────────────────────────────────────────────────
        ConfigCategory resets = builder.getOrCreateCategory(Text.literal("Resets"));

        resets.addEntry(eb.startTextDescription(
                Text.literal("§cThese actions take effect immediately when you save.")).build());

        resets.addEntry(eb.startBooleanToggle(
                        Text.literal("Reset Earnings (toggle to confirm)"), false)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Toggle ON and save to reset all earnings data to $0.00"))
                .setSaveConsumer(v -> { if (v) EarningsDetector.reset(); }).build());

        resets.addEntry(eb.startBooleanToggle(
                        Text.literal("Reset HUD Positions (toggle to confirm)"), false)
                .setDefaultValue(false)
                .setTooltip(Text.literal(
                        "Toggle ON and save to reset all HUD panel positions to defaults"))
                .setSaveConsumer(v -> { if (v) cfg.resetHudDefaults(); }).build());

        return builder.build();
    }
}