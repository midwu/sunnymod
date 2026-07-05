package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ShopLogger implements ClientModInitializer {

    private static final boolean DEBUG = false;

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sunnyMod");
    private static final Path CSV_FILE   = CONFIG_DIR.resolve("shop_data.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final StringBuilder messageBuffer = new StringBuilder();
    private static boolean isBuffering     = false;
    private static long    bufferStartTime = 0L;
    private static final long BUFFER_TIMEOUT_MS = 5_000L;

    // ── Warp tracking ─────────────────────────────────────────────────────────
    private static String currentWarp     = "";
    private static String previousWarp    = "";
    private static long   lastWarpActionTime = 0L;

    // ── Public accessors ──────────────────────────────────────────────────────

    public static String getLastWarp()         { return currentWarp; }
    public static Path   getConfigDir()        { return CONFIG_DIR; }
    public static long   getLastWarpActionTime() { return lastWarpActionTime; }

    public static void pushWarp(String newWarp) {
        previousWarp      = currentWarp;
        currentWarp       = newWarp;
        lastWarpActionTime = System.currentTimeMillis();
        debugLog("Warp updated: " + currentWarp + " (prev: " + previousWarp + ")");
    }

    public static void swapWarps() {
        if (previousWarp.startsWith("/warp ")) {
            String temp   = currentWarp;
            currentWarp   = previousWarp;
            previousWarp  = temp;
            lastWarpActionTime = System.currentTimeMillis();
            debugLog("Warps swapped: current=" + currentWarp + " prev=" + previousWarp);
        } else {
            debugLog("/back detected but previous location was not a /warp — not swapping");
        }
    }

    // ── Shop data ─────────────────────────────────────────────────────────────

    private static class ShopData {
        String shopLocation;
        String shopOwner;
        String item;
        int    stockSpace;
        double price;
        String action;
        String status;
        String timestamp;

        public String toCsvLine() {
            String priceStr = (price == (int) price)
                    ? String.valueOf((int) price)
                    : String.valueOf(price);
            return shopLocation          + "," +
                    escapeCsv(shopOwner) + "," +
                    escapeCsv(item)      + "," +
                    stockSpace           + "," +
                    priceStr             + "," +
                    action               + "," +
                    status               + "," +
                    timestamp            + "," +
                    escapeCsv(currentWarp);
        }

        private String escapeCsv(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n"))
                return "\"" + value.replace("\"", "\"\"") + "\"";
            return value;
        }
    }

    // ── Initialise ────────────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[SunnyMod] Failed to create config directory: " + e.getMessage());
        }

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!Config.get().shopLoggerEnabled) return;

            String text = message.getString().replaceAll("§[0-9a-fA-F]", "");

            // Clear stuck buffer
            if (isBuffering && (System.currentTimeMillis() - bufferStartTime) > BUFFER_TIMEOUT_MS) {
                debugLog("Buffer timed out, clearing.");
                isBuffering = false;
                messageBuffer.setLength(0);
            }

            if (!isBuffering && (text.startsWith("+") || text.startsWith("|")
                    || text.contains("Shop Information:"))) {
                debugLog("Starting to buffer shop message");
                isBuffering     = true;
                bufferStartTime = System.currentTimeMillis();
                messageBuffer.setLength(0);
            }

            if (isBuffering) {
                if (!messageBuffer.isEmpty()) messageBuffer.append("\n");
                messageBuffer.append(text);
                if (text.contains("Enter in chat") || text.contains("run out of")) {
                    processBufferedMessage();
                }
            }
        });
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private void processBufferedMessage() {
        if (messageBuffer.isEmpty()) { isBuffering = false; return; }

        String fullMessage = messageBuffer.toString();
        debugLog("Processing full message:\n" + fullMessage);
        messageBuffer.setLength(0);
        isBuffering = false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            debugLog("Not looking at a block, skipping");
            return;
        }

        BlockPos pos   = ((BlockHitResult) hitResult).getBlockPos();
        Block    block = client.world != null ? client.world.getBlockState(pos).getBlock() : null;

        if (block != Blocks.OAK_WALL_SIGN) {
            debugLog("Not looking at an oak wall sign, skipping");
            if (Config.get().showShopNotSign()) {
                client.player.sendMessage(
                        Text.literal("§e[Shop] Not looking at a sign — shop not logged. " +
                                "Right-click the sign to log this shop."), false);
            }
            return;
        }

        ShopData shopData = parseShopMessage(fullMessage);
        if (shopData != null) {
            shopData.shopLocation = String.format("%d %d %d", pos.getX(), pos.getY(), pos.getZ());
            shopData.timestamp    = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            debugLog("Parsed shop at " + shopData.shopLocation + " | Warp=" + currentWarp);
            saveShopData(shopData);
            lastWarpActionTime = System.currentTimeMillis();
        }
    }

    private ShopData parseShopMessage(String message) {
        if (!message.contains("| Owner:")   && !message.contains("| Item:") &&
                !message.contains("| Stock:") && !message.contains("| Space:") &&
                !message.contains("| Price per")) return null;

        ShopData data = new ShopData();

        for (String line : message.split("\n")) {
            line = line.trim();
            if (line.isEmpty()
                    || (line.startsWith("+") && !line.contains("Enter in chat"))
                    || (line.startsWith("|") && line.length() <= 1)) continue;

            if (line.startsWith("| Owner: ")) {
                data.shopOwner = line.substring("| Owner: ".length()).trim();
            } else if (line.startsWith("| Item: ")) {
                String itemLine  = line.substring("| Item: ".length()).trim();
                int previewIndex = itemLine.indexOf("[Item Preview]");
                data.item = previewIndex != -1
                        ? itemLine.substring(0, previewIndex).trim()
                        : itemLine;
            } else if (line.startsWith("| Stock: ")) {
                data.stockSpace = parseIntSafely(line.substring("| Stock: ".length()).trim(), 0);
            } else if (line.startsWith("| Space: ")) {
                data.stockSpace = parseIntSafely(line.substring("| Space: ".length()).trim(), 0);
            } else if (line.startsWith("| Price per ")) {
                String[] priceSplit = line.substring("| Price per ".length()).trim().split("- \\$");
                if (priceSplit.length >= 2)
                    data.price = parseDoubleSafely(priceSplit[1].replace(",", ""), 0.0);
            } else if (line.contains("This shop is SELLING"))          data.action = "SELLING";
            else if (line.contains("This shop is BUYING"))             data.action = "BUYING";
            else if (line.contains("This shop has run out of space"))  data.status = "out of space";
            else if (line.contains("This shop has run out of stock"))  data.status = "out of stock";
        }

        if (data.action    == null) data.action    = "UNKNOWN";
        if (data.status    == null) data.status    = "Active";
        if (data.shopOwner == null) data.shopOwner = "";
        if (data.item      == null) data.item      = "";

        if (data.stockSpace == 0) {
            if ("SELLING".equals(data.action) && !"out of stock".equals(data.status))
                data.status = "out of stock";
            else if ("BUYING".equals(data.action) && !"out of space".equals(data.status))
                data.status = "out of space";
        }

        return data;
    }

    private void saveShopData(ShopData newData) {
        List<String> lines = new ArrayList<>();
        boolean found  = false;
        boolean update = false;
        String  header = "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp";

        if (Files.exists(CSV_FILE)) {
            try (BufferedReader reader = Files.newBufferedReader(CSV_FILE)) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) { lines.add(header); isHeader = false; continue; }
                    int firstComma = line.indexOf(',');
                    String existingLocation = firstComma != -1 ? line.substring(0, firstComma) : line;
                    if (existingLocation.equals(newData.shopLocation)) {
                        lines.add(newData.toCsvLine());
                        found  = true;
                        update = true;
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                System.err.println("[SunnyMod] Error reading shop data: " + e.getMessage());
                return;
            }
        } else {
            lines.add(header);
        }

        if (!found) lines.add(newData.toCsvLine());

        try (BufferedWriter writer = Files.newBufferedWriter(CSV_FILE)) {
            for (String line : lines) { writer.write(line); writer.newLine(); }
        } catch (IOException e) {
            System.err.println("[SunnyMod] Error writing shop data: " + e.getMessage());
            if (Config.get().showShopSaveFailed()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("§c[Shop] Failed to save shop data!"), false);
                }
            }
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            if (update && Config.get().showShopUpdated()) {
                client.player.sendMessage(Text.literal(
                        "§a[Shop] Updated: §f" + newData.item +
                                " §7(" + newData.action + " $" + (int) newData.price + ")"), false);
            } else if (!update && Config.get().showShopAdded()) {
                client.player.sendMessage(Text.literal(
                        "§a[Shop] Added: §f" + newData.item +
                                " §7(" + newData.action + " $" + (int) newData.price + ")"), false);
            }
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private int    parseIntSafely(String v, int d)       { try { return Integer.parseInt(v); }   catch (Exception e) { return d; } }
    private double parseDoubleSafely(String v, double d) { try { return Double.parseDouble(v); } catch (Exception e) { return d; } }
    private static void debugLog(String msg)             { if (DEBUG) System.out.println("[ShopLogger DEBUG] " + msg); }
}