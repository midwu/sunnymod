package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively captures "Ntat's Shop" and "Aurora Forge" — fixed catalogs of
 * one-off custom cosmetic/gear purchases — whenever you naturally open
 * either during normal play, and writes them straight into shop_data.csv
 * as SELLING rows (the shop sells the item to you, mirroring how a normal
 * player shop listing is recorded).
 *
 * Keyed by the shop's own custom product name (stack.getName(), NOT
 * Item.getName()) — unlike the grindable-item shop, the custom name here
 * IS the correct disambiguator: "Aurora Pickaxe (Fortune)" vs
 * "Aurora Pickaxe (Silk Touch)" are genuinely different products the shop
 * itself distinguishes by name, even though both are minecraft:netherite_pickaxe.
 *
 * KNOWN LIMITATION: "Aurora Trident" appears twice in the source data with
 * the same name, same lore, but two different prices ($75,000 / $100,000) —
 * nothing in the tooltip distinguishes them. Since rows are keyed by product
 * name, whichever one is scanned last in a given capture silently overwrites
 * the other. Worth checking in-game directly if that distinction matters.
 *
 * Owner is set to the shop's own name ("Ntat's Shop" / "Aurora Forge") so
 * these rows are easy to tell apart from real player shops in shop_data.csv.
 *
 * Two outputs:
 *   - shop_data.csv            current state, upserted under a synthetic
 *                               per-shop Shop Location, same pattern as
 *                               ServerShopLogger.
 *   - premium_shop_history.csv append-only, a row only when a product's
 *                               price actually changes.
 */
public class Premiumshoplogger implements ClientModInitializer {

    private static final Path CONFIG_DIR     = ShopLogger.getConfigDir();
    private static final Path SHOP_DATA_FILE = CONFIG_DIR.resolve("shop_data.csv");
    private static final Path HISTORY_FILE   = CONFIG_DIR.resolve("premium_shop_history.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SHOP_DATA_HEADER =
            "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp";
    private static final String HISTORY_HEADER =
            "Timestamp,Category,ItemId,ProductName,OldPrice,NewPrice";

    private static final int UNLIMITED_STOCK = 999_999; // no visible per-item quantity limit shown in-game

    // Menu title -> [synthetic Shop Location, Shop Owner label]
    private static final Map<String, String[]> CATALOG_MENUS = Map.of(
            "Ntat's Shop",  new String[]{"PREMIUM_NTAT",  "Ntat's Shop"},
            "Aurora Forge", new String[]{"PREMIUM_FORGE", "Aurora Forge"}
    );

    private static final Pattern PRICE_PATTERN =
            Pattern.compile("(?:Price|Cost): \\$([0-9,]+(?:\\.[0-9]+)?)");

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[PremiumShopLogger] Failed to create config directory: " + e.getMessage());
        }

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof HandledScreen<?> handledScreen)) return;

            String title = screen.getTitle().getString();
            String[] meta = CATALOG_MENUS.get(title);
            if (meta == null) return; // not one of our known catalog menus

            // Capture on close, same reasoning as ServerShopLogger — avoids
            // reading a menu before the server's contents packet has landed.
            ScreenEvents.remove(screen).register(s -> captureMenu(handledScreen, title, meta[0], meta[1]));
        });
    }

    private void captureMenu(HandledScreen<?> screen, String category, String location, String owner) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Slot> slots = screen.getScreenHandler().slots;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // [itemId, productName, priceStr]
        List<String[]> parsed = new ArrayList<>();

        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            List<Text> tooltip = stack.getTooltip(
                    Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
            if (tooltip.isEmpty()) continue;

            String productName = stack.getName().getString(); // custom name IS the correct key here

            for (Text line : tooltip) {
                Matcher m = PRICE_PATTERN.matcher(line.getString());
                if (m.find()) {
                    String itemId   = String.valueOf(stack.getItem());
                    String priceStr = m.group(1).replace(",", "");
                    parsed.add(new String[]{itemId, productName, priceStr});
                    break;
                }
            }
        }

        if (parsed.isEmpty()) return;

        int[] result = upsertShopData(location, owner, category, timestamp, parsed);
        int total = result[0];
        int changed = result[1];

        if (client.player != null) {
            String msg = "§a[PremiumShop] Captured §f" + total + " §aproduct(s) from §f\"" + category + "\"";
            if (changed > 0) msg += " §7(" + changed + " changed)";
            client.player.sendMessage(Text.literal(msg), false);
        }
    }

    /**
     * Rewrites all rows for this synthetic location fresh into shop_data.csv,
     * and appends to premium_shop_history.csv only for products whose price
     * differs from what was previously stored.
     *
     * @return {totalProductsWritten, productsWithChangedPrice}
     */
    private int[] upsertShopData(String location, String owner, String category, String timestamp, List<String[]> parsed) {
        List<String> keptLines = new ArrayList<>();
        Map<String, String> oldPriceByProduct = new HashMap<>(); // productName -> old price

        if (Files.exists(SHOP_DATA_FILE)) {
            try (BufferedReader reader = Files.newBufferedReader(SHOP_DATA_FILE)) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) { keptLines.add(SHOP_DATA_HEADER); isHeader = false; continue; }
                    String[] parts = parseCsvLine(line);
                    if (parts.length > 4 && parts[0].equals(location)) {
                        oldPriceByProduct.put(parts[2], parts[4]); // Item -> Price
                        continue; // drop; we rewrite this location's rows fresh below
                    }
                    keptLines.add(line);
                }
            } catch (IOException e) {
                System.err.println("[PremiumShopLogger] Error reading shop_data.csv: " + e.getMessage());
                return new int[]{0, 0};
            }
        } else {
            keptLines.add(SHOP_DATA_HEADER);
        }

        List<String> historyLines = new ArrayList<>();
        int changed = 0;

        // Last-write-wins per product name within this capture. See class
        // javadoc re: the known "Aurora Trident" duplicate-name case.
        Map<String, String[]> byProductName = new LinkedHashMap<>();
        for (String[] row : parsed) byProductName.put(row[1], row);

        for (String[] row : byProductName.values()) {
            String itemId      = row[0];
            String productName = row[1];
            String priceStr    = row[2];

            keptLines.add(location + "," + escapeCsv(owner) + "," + escapeCsv(productName) + "," +
                    UNLIMITED_STOCK + "," + priceStr + ",SELLING,Active," + timestamp + ",");

            String oldPrice = oldPriceByProduct.get(productName);
            if (oldPrice == null || !oldPrice.equals(priceStr)) {
                changed++;
                historyLines.add(timestamp + "," + escapeCsv(category) + "," + escapeCsv(itemId) + "," +
                        escapeCsv(productName) + "," + (oldPrice == null ? "" : oldPrice) + "," + priceStr);
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(SHOP_DATA_FILE)) {
            for (String line : keptLines) { writer.write(line); writer.newLine(); }
        } catch (IOException e) {
            System.err.println("[PremiumShopLogger] Failed to write shop_data.csv: " + e.getMessage());
            return new int[]{0, 0};
        }

        if (!historyLines.isEmpty()) {
            boolean needsHeader = !Files.exists(HISTORY_FILE);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    HISTORY_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (needsHeader) { writer.write(HISTORY_HEADER); writer.newLine(); }
                for (String line : historyLines) { writer.write(line); writer.newLine(); }
            } catch (IOException e) {
                System.err.println("[PremiumShopLogger] Failed to write premium_shop_history.csv: " + e.getMessage());
            }
        }

        return new int[]{byProductName.size(), changed};
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}