package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EarningsDetector implements ClientModInitializer {

    private static final Pattern EARNINGS_PATTERN =
            Pattern.compile("Merchant: You sold (\\d+) x (.+?) for a total of \\$([\\d,]+(?:\\.\\d+)?)\\.");

    private static double totalAmount = 0;
    private static final Map<String, Integer> itemTotals = new ConcurrentHashMap<>();
    private static volatile long lastSaleTimestamp = 0L;

    public static double getTotalAmount()               { return totalAmount; }
    public static Map<String, Integer> getItemTotals()  { return Collections.unmodifiableMap(itemTotals); }
    public static long getLastSaleTimestamp()           { return lastSaleTimestamp; }
    public static int  getTimeOffset()                  { return Config.get().timeOffset; }

    public static void reset() {
        totalAmount       = 0;
        itemTotals.clear();
        lastSaleTimestamp = 0L;
    }

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!Config.get().earningsDetectorEnabled) return;

            String text     = message.getString();
            Matcher matcher = EARNINGS_PATTERN.matcher(text);

            if (matcher.find()) {
                int quantity    = Integer.parseInt(matcher.group(1));
                String itemName = matcher.group(2);
                double amount   = Double.parseDouble(matcher.group(3).replace(",", ""));

                totalAmount += amount;
                itemTotals.put(itemName, itemTotals.getOrDefault(itemName, 0) + quantity);
                lastSaleTimestamp = System.currentTimeMillis();

                if (Config.get().showEarningsPerSale()) {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal(
                                        "§a[Earnings] Sold §f" + quantity + "x " + itemName +
                                                " §afor §6$" + String.format("%,.2f", amount) +
                                                " §a(Total: §6$" + String.format("%,.2f", totalAmount) + "§a)"),
                                false);
                    }
                }
            }
        });
    }
}