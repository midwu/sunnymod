package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FishingLogger implements ClientModInitializer {
    private static final boolean DEBUG = false;

    // State
    private static volatile boolean contestActive = false;
    private static volatile boolean trackingSales = false;
    private static volatile boolean exportedAlready = false;
    private static final Map<String, Integer> fishCounts = new ConcurrentHashMap<>();
    private static volatile Fish biggestFish = null;
    private static volatile int playerRank = -1;
    private static volatile int baitsCaught = 0;
    private static volatile int prizeClaimblocks = 0;
    private static volatile double prizeCash = 0;
    private static volatile String prizeKey = "N/A";
    private static volatile int fishSoldCount = 0;
    private static volatile double fishSoldMoney = 0.0;
    private static volatile long rainTicksDuring = 0L;
    private static volatile long totalTicksDuring = 0L;
    private static volatile long lastSaleTime = 0L;
    private static final long POST_SALE_TIMEOUT_MS = 2 * 60 * 1000L;

    // CSV
    private static final Path CSV_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("sunnyMod/fishing_data.csv");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Bait normalization
    private static final Map<String, String> BAIT_NORMALIZE = new HashMap<>();
    static {
        BAIT_NORMALIZE.put("epicelixir", "Epic Elixir");
        BAIT_NORMALIZE.put("epic elixor", "Epic Elixir");
        BAIT_NORMALIZE.put("epic elixir", "Epic Elixir");
        BAIT_NORMALIZE.put("shrimp", "Shrimp");
        BAIT_NORMALIZE.put("stringyworms", "Stringy Worms");
        BAIT_NORMALIZE.put("stringy worms", "Stringy Worms");
        BAIT_NORMALIZE.put("infinitebait", "Infinite Bait");
        BAIT_NORMALIZE.put("infinite bait", "Infinite Bait");
        BAIT_NORMALIZE.put("legendarylure", "Legendary Lure");
        BAIT_NORMALIZE.put("legendary lure", "Legendary Lure");
    }

    // Patterns
    private static final Pattern CONTEST_START = Pattern.compile("\\[EvenMoreFish\\] A fishing contest for the largest fish has started\\.");
    private static final Pattern CONTEST_END = Pattern.compile("\\[EvenMoreFish\\] The fishing contest has ended\\.");
    private static final Pattern ANY_FISH = Pattern.compile("^(\\w+) has fished a (\\d+\\.\\d+)cm (.+)!$");
    private static final Pattern BAIT_CAUGHT = Pattern.compile("^(\\w+) has caught a (.+?) bait!$");
    private static final Pattern LEADERBOARD_ENTRY = Pattern.compile("\\[EvenMoreFish\\] #(\\d+) \\| (\\w+) \\((.+), (\\d+\\.\\d+)cm\\)");
    private static final Pattern TOTAL_PLAYERS = Pattern.compile("\\[EvenMoreFish\\] There are a total of (\\d+) player\\(s\\) in the leaderboard\\.");
    private static final Pattern FISH_SOLD = Pattern.compile("\\[EvenMoreFish\\] You've sold (\\d+) fish for \\$([\\d,]+(?:\\.\\d+)?)\\.");
    private static final Pattern PRIZE_1ST = Pattern.compile("You were given \\$30K, 2K ClaimBlocks & (.+) for 1st place!");
    private static final Pattern PRIZE_2ND = Pattern.compile("You were given \\$25K & 1500 ClaimBlocks for 2nd place!");
    private static final Pattern PRIZE_3RD = Pattern.compile("You were given \\$15K & 1000 ClaimBlocks for 3rd place!");
    private static final Pattern PRIZE_PARTICIPATION = Pattern.compile("You received 500 claimblocks for participating!");

    private record Fish(String name, double size, String rarity) {}

    private String getPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getSession() != null) return client.getSession().getUsername();
        return "unknown";
    }

    private String normalizeBait(String raw) {
        String key = raw.trim().toLowerCase();
        return BAIT_NORMALIZE.getOrDefault(key, capitalize(raw.trim()));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase())
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }

    private String getRarity(String fishName) {
        String lower = fishName.toLowerCase();
        if (lower.startsWith("legendary")) return "Legendary";
        if (lower.startsWith("epic")) return "Epic";
        if (lower.startsWith("rare")) return "Rare";
        if (lower.startsWith("common")) return "Common";
        if (lower.startsWith("junk")) return "Junk";
        return "Unknown";
    }

    private String stripRarity(String fishName) {
        for (String prefix : List.of("Legendary ", "Epic ", "Rare ", "Common ", "Junk ")) {
            if (fishName.startsWith(prefix)) return fishName.substring(prefix.length());
        }
        return fishName;
    }

    private void applyPrize(int rank, String keyString) {
        if (playerRank <= 0) playerRank = rank;
        switch (rank) {
            case 1 -> { prizeCash = 30000; prizeClaimblocks = 2000; prizeKey = keyString; }
            case 2 -> { prizeCash = 25000; prizeClaimblocks = 1500; }
            case 3 -> { prizeCash = 15000; prizeClaimblocks = 1000; }
            case 4 -> { prizeCash = 0; prizeClaimblocks = 500; }
        }
        debugLog("Prize applied: Rank " + rank + " | Cash=" + prizeCash);
    }

    private void sendExportedFeedback(String message) {
        if (!Config.get().showFishingExported()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null)
            client.player.sendMessage(Text.literal(message), false);
    }

    private void sendFailedFeedback(String message) {
        if (!Config.get().showFishingFailed()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null)
            client.player.sendMessage(Text.literal(message), false);
    }

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CSV_FILE.getParent());
        } catch (IOException e) {
            System.err.println("[FishingLogger] Failed to create config directory: " + e.getMessage());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) return;
            if (contestActive) {
                totalTicksDuring++;
                if (client.world.isRaining()) rainTicksDuring++;
            }
            if (trackingSales && !exportedAlready && lastSaleTime > 0) {
                if ((System.currentTimeMillis() - lastSaleTime) > POST_SALE_TIMEOUT_MS) {
                    exportToCSV();
                }
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            if (CONTEST_START.matcher(text).matches()) {
                resetContestData();
                contestActive = true;
                trackingSales = Config.get().fishingLoggerEnabled;
                exportedAlready = false;
                return;
            }
            if (CONTEST_END.matcher(text).matches()) {
                contestActive = false;
                if (Config.get().fishingLoggerEnabled) {
                    lastSaleTime = System.currentTimeMillis();
                } else {
                    trackingSales = false;
                }
                return;
            }
            if (!Config.get().fishingLoggerEnabled) return;

            String playerName = getPlayerName();
            Matcher m;

            if ((m = LEADERBOARD_ENTRY.matcher(text)).find()) {
                int pos = Integer.parseInt(m.group(1));
                String who = m.group(2);
                if (who.equalsIgnoreCase(playerName)) playerRank = pos;
                return;
            }

            // Prize detection (FIXED)
            Matcher prizeMatcher;
            if ((prizeMatcher = PRIZE_1ST.matcher(text)).find()) {
                applyPrize(1, prizeMatcher.group(1));
                lastSaleTime = System.currentTimeMillis();
                return;
            }
            if ((prizeMatcher = PRIZE_2ND.matcher(text)).find()) {
                applyPrize(2, "");
                lastSaleTime = System.currentTimeMillis();
                return;
            }
            if ((prizeMatcher = PRIZE_3RD.matcher(text)).find()) {
                applyPrize(3, "");
                lastSaleTime = System.currentTimeMillis();
                return;
            }
            if (PRIZE_PARTICIPATION.matcher(text).find()) {
                applyPrize(4, "");
                lastSaleTime = System.currentTimeMillis();
                return;
            }

            if (TOTAL_PLAYERS.matcher(text).find()) {
                lastSaleTime = System.currentTimeMillis();
            }

            if (trackingSales && (m = FISH_SOLD.matcher(text)).find()) {
                int count = Integer.parseInt(m.group(1));
                double amount = Double.parseDouble(m.group(2).replace(",", ""));
                fishSoldCount += count;
                fishSoldMoney += amount;
                lastSaleTime = System.currentTimeMillis();
                if (!contestActive && !exportedAlready) exportToCSV();
                return;
            }

            if (!contestActive) return;

            if ((m = ANY_FISH.matcher(text)).find()) {
                String catcher = m.group(1);
                double size = Double.parseDouble(m.group(2));
                String fishName = m.group(3);
                String rarity = getRarity(fishName);
                if (catcher.equalsIgnoreCase(playerName)) {
                    fishCounts.merge(rarity, 1, Integer::sum);
                    if (biggestFish == null || size > biggestFish.size()) {
                        biggestFish = new Fish(fishName, size, rarity);
                    }
                }
                return;
            }

            if ((m = BAIT_CAUGHT.matcher(text)).find()) {
                String catcher = m.group(1);
                if (catcher.equalsIgnoreCase(playerName)) {
                    baitsCaught++;
                }
            }
        });
    }

    private static List<String> getStaticHeaders() {
        return List.of(
                "Date & Time", "Fisher", "Baits Caught", "Junk Fish", "Common Fish", "Rare Fish",
                "Epic Fish", "Legendary Fish", "Total Fish", "Grand Total (Fish + Baits)",
                "Biggest Fish Rarity", "Biggest Fish Name", "Biggest Fish Size (cm)",
                "Leaderboard Position", "Prize Cash ($)", "Prize Claimblocks", "Prize Key",
                "Fish Sold (count)", "Fish Sold ($)", "Grand Total Cash ($)", "Rain %"
        );
    }

    private void exportToCSV() {
        if (exportedAlready) return;
        exportedAlready = true;
        trackingSales = false;

        try {
            String timestamp = LocalDateTime.now().format(DATE_TIME_FORMAT);
            String playerName = getPlayerName();

            int totalFish = fishCounts.values().stream().mapToInt(Integer::intValue).sum();
            int grandTotal = totalFish + baitsCaught;
            double grandTotalCash = prizeCash + fishSoldMoney;
            double rainPercent = totalTicksDuring > 0 ? (rainTicksDuring / (double) totalTicksDuring) * 100.0 : 0.0;

            String biggestRarity = biggestFish != null ? biggestFish.rarity() : "N/A";
            String biggestName = biggestFish != null ? stripRarity(biggestFish.name()) : "N/A";
            String biggestSize = biggestFish != null ? String.valueOf(biggestFish.size()) : "N/A";
            String rankStr = playerRank > 0 ? String.valueOf(playerRank) : "N/A";
            String prizeCashStr = prizeCash > 0 ? String.format("%.0f", prizeCash) : "N/A";
            String prizeCbStr = prizeClaimblocks > 0 ? String.valueOf(prizeClaimblocks) : "N/A";

            StringBuilder row = new StringBuilder();
            row.append(escapeCsv(timestamp)).append(",");
            row.append(escapeCsv(playerName)).append(",");
            row.append(baitsCaught).append(",");
            row.append(fishCounts.getOrDefault("Junk", 0)).append(",");
            row.append(fishCounts.getOrDefault("Common", 0)).append(",");
            row.append(fishCounts.getOrDefault("Rare", 0)).append(",");
            row.append(fishCounts.getOrDefault("Epic", 0)).append(",");
            row.append(fishCounts.getOrDefault("Legendary", 0)).append(",");
            row.append(totalFish).append(",");
            row.append(grandTotal).append(",");
            row.append(escapeCsv(biggestRarity)).append(",");
            row.append(escapeCsv(biggestName)).append(",");
            row.append(biggestSize).append(",");
            row.append(escapeCsv(rankStr)).append(",");
            row.append(escapeCsv(prizeCashStr)).append(",");
            row.append(escapeCsv(prizeCbStr)).append(",");
            row.append(escapeCsv(prizeKey)).append(",");
            row.append(fishSoldCount).append(",");
            row.append(formatNumber(fishSoldMoney)).append(",");
            row.append(formatNumber(grandTotalCash)).append(",");
            row.append(formatNumber(rainPercent)).append("\n");

            StringBuilder fullFile = new StringBuilder();
            fullFile.append(String.join(",", getStaticHeaders())).append("\n");
            fullFile.append(row);

            Files.write(CSV_FILE, fullFile.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            debugLog("Exported to CSV: " + CSV_FILE);
            sendExportedFeedback("§a[Fishing] Contest data saved to CSV.");
        } catch (IOException e) {
            System.err.println("[FishingLogger] Failed to export CSV: " + e.getMessage());
            sendFailedFeedback("§c[Fishing] Failed to save contest data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String escapeCsv(String value) {
        if (value == null || value.isEmpty()) return "N/A";
        value = value.replace("\n", " ").replace("\r", " ").trim();
        if (value.contains(",") || value.contains("\"") || value.contains(" ")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String formatNumber(double number) {
        return String.format(Locale.US, "%.2f", number);
    }

    private void resetContestData() {
        fishCounts.clear();
        biggestFish = null;
        playerRank = -1;
        baitsCaught = 0;
        prizeCash = 0;
        prizeClaimblocks = 0;
        prizeKey = "N/A";
        fishSoldCount = 0;
        fishSoldMoney = 0.0;
        rainTicksDuring = 0L;
        totalTicksDuring = 0L;
        lastSaleTime = 0L;
        exportedAlready = false;
    }

    private static void debugLog(String message) {
        if (DEBUG) System.out.println("[FishingLogger DEBUG] " + message);
    }
}