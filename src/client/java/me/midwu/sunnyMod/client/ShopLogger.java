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
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final StringBuilder messageBuffer = new StringBuilder();
    private static boolean isBuffering = false;
    private static long bufferStartTime = 0L;
    private static final long BUFFER_TIMEOUT_MS = 5_000L;

    // Warp tracking
    private static String currentWarp = "";
    private static String previousWarp = "";
    private static long lastWarpActionTime = 0L;

    public static String getLastWarp() { return currentWarp; }
    public static Path getConfigDir() { return CONFIG_DIR; }
    public static long getLastWarpActionTime() { return lastWarpActionTime; }

    public static void pushWarp(String newWarp) {
        previousWarp = currentWarp;
        currentWarp = newWarp;
        lastWarpActionTime = System.currentTimeMillis();
    }

    public static void swapWarps() {
        if (previousWarp.startsWith("/warp ")) {
            String temp = currentWarp;
            currentWarp = previousWarp;
            previousWarp = temp;
            lastWarpActionTime = System.currentTimeMillis();
        }
    }

    static class ShopData {
        String shopLocation;
        String shopOwner;
        String item;
        int stockSpace;
        double price;
        String action;
        String status;
        String timestamp;

        public String toCsvLine() {
            String priceStr = (price == (int) price) ? String.valueOf((int) price) : String.valueOf(price);
            return shopLocation + "," +
                    escapeCsv(shopOwner) + "," +
                    escapeCsv(item) + "," +
                    stockSpace + "," +
                    priceStr + "," +
                    action + "," +
                    status + "," +
                    timestamp + "," +
                    escapeCsv(currentWarp);
        }

        private String escapeCsv(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n"))
                return "\"" + value.replace("\"", "\"\"") + "\"";
            return value;
        }
    }

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

            if (!isBuffering && (text.startsWith("+") || text.startsWith("|") || text.contains("Shop Information:"))) {
                isBuffering = true;
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

    private void processBufferedMessage() {
        if (messageBuffer.isEmpty()) {
            isBuffering = false;
            return;
        }

        String fullMessage = messageBuffer.toString();
        messageBuffer.setLength(0);
        isBuffering = false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Block block = client.world != null ? client.world.getBlockState(pos).getBlock() : null;

        if (block != Blocks.OAK_WALL_SIGN) {
            if (Config.get().showShopNotSign()) {
                client.player.sendMessage(Text.literal("§e[Shop] Not looking at a sign — shop not logged."), false);
            }
            return;
        }

        ShopData newData = parseShopMessage(fullMessage);
        if (newData != null) {
            newData.shopLocation = String.format("%d %d %d", pos.getX(), pos.getY(), pos.getZ());
            newData.timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

            ShopData oldData = findExistingShop(newData.shopLocation);
            saveShopData(newData, oldData);

            lastWarpActionTime = System.currentTimeMillis();
        }
    }

    private ShopData findExistingShop(String location) {
        if (!Files.exists(CSV_FILE)) return null;
        try (BufferedReader reader = Files.newBufferedReader(CSV_FILE)) {
            String line;
            reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(location + ",")) {
                    String[] parts = line.split(",", -1);
                    ShopData data = new ShopData();
                    data.shopLocation = parts[0];
                    data.shopOwner = parts[1].replace("\"", "");
                    data.item = parts[2].replace("\"", "");
                    data.stockSpace = parseIntSafely(parts[3], 0);
                    data.price = parseDoubleSafely(parts[4], 0.0);
                    data.action = parts[5];
                    data.status = parts[6];
                    return data;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void saveShopData(ShopData newData, ShopData oldData) {
        boolean isUpdate = oldData != null;
        List<String> lines = new ArrayList<>();
        String header = "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp";

        if (Files.exists(CSV_FILE)) {
            try (BufferedReader reader = Files.newBufferedReader(CSV_FILE)) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) { lines.add(header); isHeader = false; continue; }
                    if (line.startsWith(newData.shopLocation + ",")) {
                        lines.add(newData.toCsvLine());
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

        if (!isUpdate) lines.add(newData.toCsvLine());

        try (BufferedWriter writer = Files.newBufferedWriter(CSV_FILE)) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("[SunnyMod] Error writing shop data: " + e.getMessage());
            if (Config.get().showShopSaveFailed()) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§c[Shop] Failed to save shop data!"), false);
            }
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Convert to LastShopInfo for HUD
        LastShopInfo info = new LastShopInfo(
                newData.shopOwner,
                newData.item,
                newData.stockSpace,
                newData.price,
                newData.action,
                newData.timestamp
        );
        ShopSignHud.updateLastShop(info);

        if (isUpdate && Config.get().showShopUpdated()) {
            sendDetailedUpdateFeedback(client, oldData, newData);
        } else if (!isUpdate && Config.get().showShopAdded()) {
            client.player.sendMessage(Text.literal("§a[Shop] Added: §f" + newData.item + " §7(" + newData.action + " $" + (int)newData.price + ")"), false);
        }
    }

    private void sendDetailedUpdateFeedback(MinecraftClient client, ShopData oldData, ShopData newData) {
        StringBuilder msg = new StringBuilder("§a[Shop] Updated: §f");

        List<String> changes = new ArrayList<>();

        if (!oldData.item.equals(newData.item)) {
            changes.add(oldData.item + " §7→ §f" + newData.item);
        }
        if (Math.abs(oldData.price - newData.price) > 0.01) {
            changes.add("$" + (int)oldData.price + " §7→ §a$" + (int)newData.price);
        }
        if (oldData.stockSpace != newData.stockSpace) {
            changes.add("Stock: " + oldData.stockSpace + " §7→ §f" + newData.stockSpace);
        }
        if (!oldData.shopOwner.equals(newData.shopOwner)) {
            changes.add("Owner: " + oldData.shopOwner + " §7→ §f" + newData.shopOwner);
        }
        if (!oldData.action.equals(newData.action)) {
            changes.add("Action: " + oldData.action + " §7→ §f" + newData.action);
        }

        if (changes.isEmpty()) {
            msg.append(newData.item).append(" §7(no visible change)");
        } else {
            msg.append(String.join(" §7| ", changes));
        }

        client.player.sendMessage(Text.literal(msg.toString()), false);
    }

    private ShopData parseShopMessage(String message) {
        ShopData data = new ShopData();

        for (String line : message.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || (line.startsWith("+") && !line.contains("Enter in chat")) ||
                    (line.startsWith("|") && line.length() <= 1)) continue;

            if (line.startsWith("| Owner: ")) {
                data.shopOwner = line.substring("| Owner: ".length()).trim();
            } else if (line.startsWith("| Item: ")) {
                String itemLine = line.substring("| Item: ".length()).trim();
                int idx = itemLine.indexOf("[Item Preview]");
                data.item = (idx != -1) ? itemLine.substring(0, idx).trim() : itemLine;
            } else if (line.startsWith("| Stock: ") || line.startsWith("| Space: ")) {
                data.stockSpace = parseIntSafely(line.substring(line.indexOf(":") + 1).trim(), 0);
            } else if (line.startsWith("| Price per ")) {
                String[] split = line.substring("| Price per ".length()).trim().split("- \\$");
                if (split.length >= 2) data.price = parseDoubleSafely(split[1].replace(",", ""), 0.0);
            } else if (line.contains("This shop is SELLING")) data.action = "SELLING";
            else if (line.contains("This shop is BUYING")) data.action = "BUYING";
            else if (line.contains("run out of space")) data.status = "out of space";
            else if (line.contains("run out of stock")) data.status = "out of stock";
        }

        if (data.action == null) data.action = "UNKNOWN";
        if (data.status == null) data.status = "Active";
        if (data.shopOwner == null) data.shopOwner = "";
        if (data.item == null) data.item = "";

        return data;
    }

    private int parseIntSafely(String s, int def) {
        try { return Integer.parseInt(s.replace(",", "")); } catch (Exception e) { return def; }
    }

    private double parseDoubleSafely(String s, double def) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return def; }
    }

    private static void debugLog(String message) {
        if (DEBUG) System.out.println("[ShopLogger DEBUG] " + message);
    }
}