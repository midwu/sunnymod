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
import org.lwjgl.glfw.GLFW;

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
import java.util.Locale;
import java.util.Map;

/**
 * Two container-related tools, both fired while a container-style menu
 * (HandledScreen — chest shops, server GUIs, your own inventory chests,
 * etc.) is open:
 *
 *   F5 — dumps the full raw contents of the open menu to container_dump.csv.
 *        Experimental/exploratory: grabs everything off every slot, no
 *        filtering — this is how we originally discovered the sell-shop
 *        menu titles and price format.
 *
 *   F7 — estimates the total sell value of the open container's contents,
 *        using the best known "BUYING" price per item from shop_data.csv
 *        (this includes both real player shops and the server's own
 *        grindable-item shop via ServerShopLogger). For each distinct
 *        item it also surfaces the full best-offer row (owner + warp)
 *        so you can see where to sell. Read-only, no file writes.
 *
 * Both are read via raw GLFW state polling (glfwGetKey), not a Minecraft
 * KeyBinding — custom KeyBindings don't reliably update while a screen owns
 * keyboard focus in this version, only Minecraft's own hardcoded shortcuts
 * (F3, screenshot key, etc.) are special-cased for that. Polling GLFW
 * directly sidesteps Minecraft's input routing entirely, the same way
 * HudEditScreen already does for its mouse-drag detection.
 */
public class Container_reader implements ClientModInitializer {

    private static final Path CONFIG_DIR      = ShopLogger.getConfigDir();
    private static final Path DUMP_FILE       = CONFIG_DIR.resolve("container_dump.csv");
    private static final Path SHOP_DATA_FILE  = CONFIG_DIR.resolve("shop_data.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String DUMP_HEADER =
            "DumpTime,ScreenTitle,SlotIndex,ItemId,DisplayName,Count,Lore";

    // Manual edge detection — glfwGetKey returns the *held* state, not a
    // one-shot press event, so we track the previous tick's state ourselves
    // to fire exactly once per press (same pattern HudEditScreen uses for
    // its mouse button).
    private static boolean wasF5Down = false;
    private static boolean wasF7Down = false;

    // Cache for the F7 valuation lookup, keyed off shop_data.csv's
    // last-modified time so repeated F7 presses in the same session don't
    // re-read/re-parse a potentially large CSV every time. Refreshes
    // automatically whenever ServerShopLogger (or anything else) writes a
    // newer version of the file.
    private static Map<String, BestBuyOffer> bestBuyOfferCache = null;
    private static long cachedFileModTime = -1;

    /**
     * Best known BUYING offer for an item: highest price any shop will pay,
     * plus the owner/warp/location that offered it so the HUD can show
     * where to sell.
     */
    public static final class BestBuyOffer {
        public final double price;
        public final String owner;
        public final String warp;
        public final String location;
        public final String stockSpace;

        public BestBuyOffer(double price, String owner, String warp,
                            String location, String stockSpace) {
            this.price = price;
            this.owner = owner != null ? owner : "";
            this.warp = warp != null ? warp : "";
            this.location = location != null ? location : "";
            this.stockSpace = stockSpace != null ? stockSpace : "";
        }
    }

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to create config directory: " + e.getMessage());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long windowHandle = client.getWindow().getHandle();

            boolean isF5Down = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_F5) == GLFW.GLFW_PRESS;
            if (isF5Down && !wasF5Down) {
                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    dumpContainer(handledScreen);
                } else if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "§e[ContainerReader] No container-style menu open — nothing to dump."), false);
                }
            }
            wasF5Down = isF5Down;

            boolean isF7Down = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;
            if (isF7Down && !wasF7Down) {
                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    evaluateContainerWorth(handledScreen);
                } else if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "§e[ContainerReader] No container-style menu open — nothing to value."), false);
                }
            }
            wasF7Down = isF7Down;
        });
    }

    // ── F5: raw dump ─────────────────────────────────────────────────────────

    private void dumpContainer(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        String screenTitle = escapeCsv(screen.getTitle().getString());
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        List<Slot> slots = screen.getScreenHandler().slots;
        int written = 0;

        boolean needsHeader = !Files.exists(DUMP_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(
                DUMP_FILE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            if (needsHeader) {
                writer.write(DUMP_HEADER);
                writer.newLine();
            }

            for (Slot slot : slots) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;

                String itemId = String.valueOf(stack.getItem());
                String displayName = stack.getName().getString();
                int count = stack.getCount();

                List<Text> tooltip = stack.getTooltip(
                        Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
                StringBuilder lore = new StringBuilder();
                for (int i = 1; i < tooltip.size(); i++) { // skip index 0, it's the name again
                    if (!lore.isEmpty()) lore.append(" | ");
                    lore.append(tooltip.get(i).getString());
                }

                String line = timestamp + "," +
                        screenTitle + "," +
                        slot.getIndex() + "," +
                        escapeCsv(itemId) + "," +
                        escapeCsv(displayName) + "," +
                        count + "," +
                        escapeCsv(lore.toString());

                writer.write(line);
                writer.newLine();
                written++;
            }
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to write dump: " + e.getMessage());
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[ContainerReader] Failed to save dump!"), false);
            }
            return;
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal(
                    "§a[ContainerReader] Dumped §f" + written + " §aslot(s) from §f\"" + screen.getTitle().getString() + "\"§a → container_dump.csv"), false);
        }
    }

    // ── F7: worth evaluator (single-sided best-sell) ─────────────────────────

    private void evaluateContainerWorth(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Map<String, BestBuyOffer> bestOffers = getBestBuyOffers();

        double total = 0.0;
        int pricedStacks = 0;   // still counted per physical stack (useful diagnostic)
        int unpricedStacks = 0;
        List<String> missingItems = new ArrayList<>();

        // Aggregate by item name so a 54-slot chest of the same block becomes
        // one HUD row instead of 54. Price/offer is looked up once per name.
        Map<String, Integer> countByName = new LinkedHashMap<>();
        Map<String, BestBuyOffer> offerByName = new LinkedHashMap<>();

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            // Vanilla name — matches ServerShopLogger / commodity shops.
            // (PremiumShopLogger deliberately uses custom display names and
            // lives in its own history file; it does not participate here.)
            String name = stack.getItem().getName().getString();
            int count = stack.getCount();

            countByName.merge(name, count, Integer::sum);

            BestBuyOffer offer = bestOffers.get(name);
            if (offer != null) {
                offerByName.putIfAbsent(name, offer);
                total += offer.price * count;
                pricedStacks++;
            } else {
                unpricedStacks++;
                if (!missingItems.contains(name) && missingItems.size() < 5) {
                    missingItems.add(name);
                }
            }
        }

        if (pricedStacks == 0 && unpricedStacks == 0) {
            client.player.sendMessage(Text.literal(
                    "§e[ContainerReader] Container is empty — nothing to value."), false);
            return;
        }

        List<ContainerWorthHud.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : countByName.entrySet()) {
            String name = e.getKey();
            int count = e.getValue();
            BestBuyOffer offer = offerByName.get(name); // may be null
            entries.add(new ContainerWorthHud.Entry(name, count, offer));
        }

        // Highest-value entries first so the panel's row cap keeps the
        // most useful rows visible.
        entries.sort((a, b) -> Double.compare(b.subtotal, a.subtotal));
        ContainerWorthHud.update(total, pricedStacks, unpricedStacks, entries);

        String msg = "§a[ContainerReader] Estimated worth: §f$" + String.format(Locale.US, "%,.2f", total) +
                " §7(" + pricedStacks + " priced, " + unpricedStacks + " unpriced stack(s)) — see HUD for breakdown";
        client.player.sendMessage(Text.literal(msg), false);

        if (!missingItems.isEmpty()) {
            String extra = unpricedStacks > missingItems.size() ? ", ..." : "";
            client.player.sendMessage(Text.literal(
                    "§7No known buy price for: " + String.join(", ", missingItems) + extra), false);
        }
    }

    /**
     * Best (max price) known "BUYING" offer per item name from shop_data.csv,
     * including owner + warp so the HUD can show where to sell.
     * Cached and only re-parsed when the file's mtime changes.
     *
     * NOTE: this is a quick heads-up estimate, not a stock-aware allocation
     * like profit_finder.py does — it doesn't account for a single shop's
     * remaining buy space possibly being too small to take everything in
     * the container at that price.
     */
    private static Map<String, BestBuyOffer> getBestBuyOffers() {
        try {
            long modTime = Files.exists(SHOP_DATA_FILE)
                    ? Files.getLastModifiedTime(SHOP_DATA_FILE).toMillis() : -1;

            if (bestBuyOfferCache != null && modTime == cachedFileModTime) {
                return bestBuyOfferCache;
            }

            Map<String, BestBuyOffer> offers = new HashMap<>();
            if (Files.exists(SHOP_DATA_FILE)) {
                try (BufferedReader reader = Files.newBufferedReader(SHOP_DATA_FILE)) {
                    String line;
                    boolean isHeader = true;
                    while ((line = reader.readLine()) != null) {
                        if (isHeader) { isHeader = false; continue; }
                        String[] parts = parseCsvLine(line);
                        // Shop Location, Shop Owner, Item, Stock/Space, Price, Action, Status, Timestamp, Warp
                        if (parts.length < 7) continue;

                        String location   = parts[0];
                        String owner      = parts[1];
                        String item       = parts[2];
                        String stockSpace = parts[3];
                        String action     = parts[5];
                        String status     = parts[6];
                        String warp       = parts.length > 8 ? parts[8] : "";

                        if (!"BUYING".equalsIgnoreCase(action)) continue;
                        if ("Dead".equalsIgnoreCase(status)) continue;

                        double price;
                        try {
                            price = Double.parseDouble(parts[4]);
                        } catch (NumberFormatException e) {
                            continue;
                        }

                        BestBuyOffer existing = offers.get(item);
                        if (existing == null || price > existing.price) {
                            offers.put(item, new BestBuyOffer(price, owner, warp, location, stockSpace));
                        }
                    }
                }
            }

            bestBuyOfferCache = offers;
            cachedFileModTime = modTime;
            return offers;
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to load shop_data.csv for valuation: " + e.getMessage());
            return bestBuyOfferCache != null ? bestBuyOfferCache : new HashMap<>();
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

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

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}