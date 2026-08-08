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
 * Passively captures prices from the server's fixed "Sell Your X!" shop
 * menus (Blocks, Mob Drops, Farming Supplies, Mineables) whenever you
 * naturally open one during normal play — no hotkey needed. This is the
 * automated follow-up to what Container_reader (F5) helped us discover:
 * the exact menu titles and the "Sell Price: $X.XX" lore format.
 *
 * Two outputs:
 *   - shop_data.csv    gets one live BUYING row per item (owner
 *                       "__server__"), upserted in place under a synthetic
 *                       per-category Shop Location, so profit_finder.py
 *                       always sees the current price without any manual
 *                       scanning.
 *   - server_shop.csv  an append-only history log — a row is written only
 *                       when a price actually changes from what we last
 *                       recorded, so price movement over time is visible.
 *
 * Item names written to shop_data.csv are derived from the item's real id
 * (e.g. "minecraft:melon_slice" -> "Melon Slice"), NOT the shop's own
 * DisplayName — the shop labels both Melon Slice and the Melon block as
 * plain "Melon", which would otherwise collide into one row with two
 * different prices.
 */
public class Servershoplogger implements ClientModInitializer {

    private static final Path CONFIG_DIR     = ShopLogger.getConfigDir();
    private static final Path SHOP_DATA_FILE = CONFIG_DIR.resolve("shop_data.csv");
    private static final Path HISTORY_FILE   = CONFIG_DIR.resolve("server_shop.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SHOP_DATA_HEADER =
            "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp";
    private static final String HISTORY_HEADER =
            "Timestamp,Category,ItemId,DisplayName,OldPrice,NewPrice";

    private static final String SERVER_OWNER    = "__server__";
    private static final int    UNLIMITED_STOCK = 999_999; // effectively uncapped NPC-style buy order

    // Known fixed-price server sell menus -> synthetic Shop Location, so
    // these rows never collide with a real player shop's block-position key.
    private static final Map<String, String> SELL_MENU_LOCATIONS = Map.of(
            "Sell Your Blocks!",           "SERVER_SELL_BLOCKS",
            "Sell Your Mob Drops!",        "SERVER_SELL_MOBDROPS",
            "Sell your Farming Supplies!", "SERVER_SELL_FARMING",
            "Sell Your Mineables!",        "SERVER_SELL_MINEABLES"
    );

    private static final Pattern PRICE_PATTERN = Pattern.compile("Sell Price: \\$([0-9.]+)");

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[ServerShopLogger] Failed to create config directory: " + e.getMessage());
        }

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof HandledScreen<?> handledScreen)) return;

            String title    = screen.getTitle().getString();
            String location = SELL_MENU_LOCATIONS.get(title);
            if (location == null) return; // not one of our known sell menus

            // Capture on close, not on open — a server-opened GUI's slot
            // contents can arrive a tick after the screen itself does, so
            // grabbing immediately on AFTER_INIT risks reading an empty or
            // partially-populated menu. By close time the player has
            // definitely seen it fully populated.
            ScreenEvents.remove(screen).register(s -> captureMenu(handledScreen, title, location));
        });
    }

    private void captureMenu(HandledScreen<?> screen, String category, String location) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<Slot> slots = screen.getScreenHandler().slots;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // [itemId, prettyName, priceStr]
        List<String[]> parsed = new ArrayList<>();

        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            List<Text> tooltip = stack.getTooltip(
                    Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);

            for (Text line : tooltip) {
                Matcher m = PRICE_PATTERN.matcher(line.getString());
                if (m.find()) {
                    String itemId = String.valueOf(stack.getItem());
                    // Item.getName() (no-arg) is the item's real vanilla name
                    // straight from the game's language file — e.g. "Block of
                    // Diamond" for diamond_block, "Melon Slice" for
                    // melon_slice. We deliberately do NOT use
                    // stack.getName()/the tooltip DisplayName here: the shop
                    // plugin overrides that with its own (sometimes
                    // ambiguous, e.g. "Melon" for both the block and the
                    // slice) custom label, and we also don't hand-roll the
                    // name from the id ourselves, since simple
                    // underscore-splitting gets compound names like
                    // "Block of Diamond" backwards ("Diamond Block").
                    String prettyName = stack.getItem().getName().getString();
                    parsed.add(new String[]{itemId, prettyName, m.group(1)});
                    break;
                }
            }
        }

        if (parsed.isEmpty()) return;

        int[] result = upsertShopData(location, category, timestamp, parsed);
        int total = result[0];
        int changed = result[1];

        if (client.player != null) {
            String msg = "§a[ServerShop] Refreshed §f" + total + " §aprice(s) from §f\"" + category + "\"";
            if (changed > 0) msg += " §7(" + changed + " changed)";
            client.player.sendMessage(Text.literal(msg), false);
        }
    }

    /**
     * Rewrites all rows for this synthetic location fresh into shop_data.csv,
     * and appends to server_shop.csv only for items whose price differs from
     * what was previously stored there.
     *
     * @return {totalItemsWritten, itemsWithChangedPrice}
     */
    private int[] upsertShopData(String location, String category, String timestamp, List<String[]> parsed) {
        List<String> keptLines = new ArrayList<>();
        Map<String, String> oldPriceByItem = new HashMap<>(); // prettyName -> old price

        if (Files.exists(SHOP_DATA_FILE)) {
            try (BufferedReader reader = Files.newBufferedReader(SHOP_DATA_FILE)) {
                String line;
                boolean isHeader = true;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) { keptLines.add(SHOP_DATA_HEADER); isHeader = false; continue; }
                    String[] parts = line.split(",", -1);
                    if (parts.length > 4 && parts[0].equals(location)) {
                        oldPriceByItem.put(parts[2], parts[4]); // Item -> Price
                        continue; // drop; we rewrite this location's rows fresh below
                    }
                    keptLines.add(line);
                }
            } catch (IOException e) {
                System.err.println("[ServerShopLogger] Error reading shop_data.csv: " + e.getMessage());
                return new int[]{0, 0};
            }
        } else {
            keptLines.add(SHOP_DATA_HEADER);
        }

        List<String> historyLines = new ArrayList<>();
        int changed = 0;

        // Dedupe by itemId within this capture in case a slot appears twice
        Map<String, String[]> byItemId = new LinkedHashMap<>();
        for (String[] row : parsed) byItemId.put(row[0], row);

        for (String[] row : byItemId.values()) {
            String itemId     = row[0];
            String prettyName = row[1];
            String priceStr   = row[2];

            keptLines.add(location + "," + SERVER_OWNER + "," + escapeCsv(prettyName) + "," +
                    UNLIMITED_STOCK + "," + priceStr + ",BUYING,Active," + timestamp + ",");

            String oldPrice = oldPriceByItem.get(prettyName);
            if (oldPrice == null || !oldPrice.equals(priceStr)) {
                changed++;
                historyLines.add(timestamp + "," + escapeCsv(category) + "," + escapeCsv(itemId) + "," +
                        escapeCsv(prettyName) + "," + (oldPrice == null ? "" : oldPrice) + "," + priceStr);
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(SHOP_DATA_FILE)) {
            for (String line : keptLines) { writer.write(line); writer.newLine(); }
        } catch (IOException e) {
            System.err.println("[ServerShopLogger] Failed to write shop_data.csv: " + e.getMessage());
            return new int[]{0, 0};
        }

        if (!historyLines.isEmpty()) {
            boolean needsHeader = !Files.exists(HISTORY_FILE);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    HISTORY_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (needsHeader) { writer.write(HISTORY_HEADER); writer.newLine(); }
                for (String line : historyLines) { writer.write(line); writer.newLine(); }
            } catch (IOException e) {
                System.err.println("[ServerShopLogger] Failed to write server_shop.csv: " + e.getMessage());
            }
        }

        return new int[]{byItemId.size(), changed};
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}