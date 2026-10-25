package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively captures pages of the server's "All Transactions" GUI.
 * A transaction's UUID (the item's display name) is its unique key, so
 * revisiting a page or restarting the client never duplicates a transaction.
 */
public final class TransactionHistoryLogger implements ClientModInitializer {
    private static final Path CONFIG_DIR = ShopLogger.getConfigDir();
    private static final Path LOG_FILE = CONFIG_DIR.resolve("transaction_history.csv");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = "TransactionId,ItemId,ItemName,VanillaName,Count,Seller,Buyer,TransactionDate,FirstCaptured,LastCaptured,SlotIndex,Lore";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SELLER = Pattern.compile("(?:^|\\s*\\|\\s*)Seller:\\s*(.*?)(?=\\s*\\|\\s*Buyer:|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUYER = Pattern.compile("(?:^|\\s*\\|\\s*)Buyer:\\s*(.*?)(?=\\s*\\|\\s*Item name:|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_NAME = Pattern.compile("(?:^|\\s*\\|\\s*)Item name:\\s*(.*?)(?=\\s*\\|\\s*Date:|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?:^|\\s*\\|\\s*)Date:\\s*(.*?)(?=\\s*\\||$)", Pattern.CASE_INSENSITIVE);

    private static String lastPageFingerprint = "";
    private static int pollTicks = 0;

    private record Transaction(String id, String itemId, String itemName, String vanillaName,
                               int count, String seller, String buyer, String date,
                               int slot, String lore) { }

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
            ensureHeader();
        } catch (IOException e) {
            System.err.println("[Transactions] Could not initialize transaction log: " + e.getMessage());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Poll at a low rate; this detects page changes without intercepting clicks.
            if (++pollTicks < 5) return;
            pollTicks = 0;
            if (!(client.currentScreen instanceof HandledScreen<?> screen)
                    || !isTransactionsScreen(screen.getTitle().getString())) {
                lastPageFingerprint = "";
                return;
            }
            try {
                List<Transaction> page = readPage(screen, client);
                if (page.isEmpty()) return; // Avoid marking loading/empty transition as a page.
                String fingerprint = fingerprint(page);
                if (fingerprint.equals(lastPageFingerprint)) return;
                lastPageFingerprint = fingerprint;

                int added = appendNew(page);
                if (added > 0 && client.player != null) {
                    client.player.sendMessage(Text.literal("§a[Transactions] §fCaptured " + added
                            + " new transaction(s). §7Total unique IDs: " + countLoggedIds()), false);
                }
            } catch (Exception e) {
                System.err.println("[Transactions] Capture failed: " + e.getMessage());
            }
        });
    }

    public static boolean isTransactionsScreen(String title) {
        if (title == null) return false;
        String clean = title.replaceAll("§.", "").trim();
        return clean.equalsIgnoreCase("All Transactions");
    }

    private static List<Transaction> readPage(HandledScreen<?> screen, MinecraftClient client) {
        List<Transaction> result = new ArrayList<>();
        if (client.player == null) return result;
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            String id = stack.getName().getString().trim();
            if (!UUID_PATTERN.matcher(id).matches()) continue;

            List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
            StringBuilder loreBuilder = new StringBuilder();
            for (int i = 1; i < tooltip.size(); i++) {
                if (!loreBuilder.isEmpty()) loreBuilder.append(" | ");
                loreBuilder.append(tooltip.get(i).getString().replace('\n', ' ').trim());
            }
            String lore = loreBuilder.toString();
            String seller = extract(SELLER, lore);
            String buyer = extract(BUYER, lore);
            String itemName = extract(ITEM_NAME, lore);
            String date = extract(DATE, lore);
            // Only accept actual transaction cards, not other UUID-named items.
            if (seller.isBlank() || buyer.isBlank() || itemName.isBlank() || date.isBlank()) continue;
            result.add(new Transaction(id.toLowerCase(Locale.ROOT), String.valueOf(stack.getItem()),
                    itemName, stack.getItem().getName().getString(), stack.getCount(), seller,
                    buyer, date, slot.getIndex(), lore));
        }
        return result;
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String fingerprint(List<Transaction> page) {
        StringBuilder b = new StringBuilder();
        for (Transaction t : page) b.append(t.id()).append('@').append(t.slot()).append(';');
        return b.toString();
    }

    private static int appendNew(List<Transaction> page) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        ensureHeader();
        Set<String> known = loadIds();
        int added = 0;
        String now = LocalDateTime.now().format(TIMESTAMP);
        try (BufferedWriter writer = Files.newBufferedWriter(LOG_FILE, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Transaction t : page) {
                if (!known.add(t.id())) continue;
                writer.write(csv(t.id()) + "," + csv(t.itemId()) + "," + csv(t.itemName()) + ","
                        + csv(t.vanillaName()) + "," + t.count() + "," + csv(t.seller()) + ","
                        + csv(t.buyer()) + "," + csv(t.date()) + "," + csv(now) + "," + csv(now)
                        + "," + t.slot() + "," + csv(t.lore()));
                writer.newLine();
                added++;
            }
        }
        return added;
    }

    private static Set<String> loadIds() throws IOException {
        Set<String> ids = new HashSet<>();
        if (!Files.exists(LOG_FILE)) return ids;
        try (BufferedReader reader = Files.newBufferedReader(LOG_FILE, StandardCharsets.UTF_8)) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                // TransactionId is the first CSV column; IDs themselves never contain commas/quotes.
                int comma = line.indexOf(',');
                if (comma > 0) ids.add(line.substring(0, comma).replace("\"", "").toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    private static int countLoggedIds() {
        try { return loadIds().size(); }
        catch (IOException e) { return 0; }
    }

    private static void ensureHeader() throws IOException {
        if (!Files.exists(LOG_FILE) || Files.size(LOG_FILE) == 0) {
            Files.writeString(LOG_FILE, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
