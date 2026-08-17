package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
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
    /** All BUYING offers per item name, each list sorted by price descending. */
    private static Map<String, java.util.List<BestBuyOffer>> bestBuyOfferCache = null;
    private static long cachedFileModTime = -1;

    // Last load diagnostics (filled by getAllBuyOffers, shown on F7).
    private static String lastLoadSummary = "not loaded yet";
    private static boolean lastLoadFromCache = false;

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


    /** Strip § colour/format codes from a name for stable matching. */
    static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }

    /**
     * Lookup BUYING offers for a stack.
     * <ul>
     *   <li>Custom-named items (display ≠ vanilla): match <b>display name only</b>
     *       so OP gear does not pick up server commodity prices.</li>
     *   <li>Plain items (display == vanilla): match that name — covers player
     *       shops and server {@code __server__} rows keyed by Item.getName().</li>
     * </ul>
     * Server shop data is always stored under the vanilla name; we only hit it
     * when the stack itself is plain (or the player shop used the vanilla name).
     */
    static java.util.List<BestBuyOffer> lookupOffers(
            Map<String, java.util.List<BestBuyOffer>> all,
            String displayName,
            String vanillaName) {
        String display = stripFormatting(displayName);
        String vanilla = stripFormatting(vanillaName);
        if (display.isEmpty() && vanilla.isEmpty()) return java.util.List.of();

        boolean plain = display.isEmpty() || display.equalsIgnoreCase(vanilla);
        if (!plain) {
            java.util.List<BestBuyOffer> byDisplay = all.get(display);
            if (byDisplay != null && !byDisplay.isEmpty()) return byDisplay;
            for (var e : all.entrySet()) {
                if (e.getKey().equalsIgnoreCase(display)) return e.getValue();
            }
            return java.util.List.of();
        }
        String key = !vanilla.isEmpty() ? vanilla : display;
        java.util.List<BestBuyOffer> list = all.get(key);
        if (list != null && !list.isEmpty()) return list;
        for (var e : all.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return java.util.List.of();
    }

    /** Single best offer (for AH compare). Same display-vs-vanilla rules. */
    static BestBuyOffer lookupBestOffer(
            Map<String, BestBuyOffer> bestByName,
            String displayName,
            String vanillaName) {
        String display = stripFormatting(displayName);
        String vanilla = stripFormatting(vanillaName);
        boolean plain = display.isEmpty() || display.equalsIgnoreCase(vanilla);
        if (!plain) {
            BestBuyOffer o = bestByName.get(display);
            if (o != null) return o;
            for (var e : bestByName.entrySet()) {
                if (e.getKey().equalsIgnoreCase(display)) return e.getValue();
            }
            return null;
        }
        String key = !vanilla.isEmpty() ? vanilla : display;
        BestBuyOffer o = bestByName.get(key);
        if (o != null) return o;
        for (var e : bestByName.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            System.err.println("[ContainerReader] Failed to create config directory: " + e.getMessage());
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Deferred SignFinder search after a warp from the worth screen
            ContainerWorthScreen.PendingFindsign.tick();
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
                    String title = handledScreen.getTitle().getString();
                    if (AuctionHouseLogger.isAuctionHouse(title)) {
                        evaluateAuctionHouse(handledScreen);
                    } else {
                        evaluateContainerWorth(handledScreen);
                    }
                } else if (client.currentScreen instanceof ContainerWorthScreen) {
                    // Already open — ignore
                } else if (client.player != null) {
                    // Outside a container: reopen last results if any
                    if (ContainerWorthHud.hasResults()) {
                        client.setScreen(new ContainerWorthScreen(
                                ContainerWorthHud.getEntries(),
                                ContainerWorthHud.getTotal(),
                                ContainerWorthHud.getPricedStacks(),
                                ContainerWorthHud.getUnpricedStacks()));
                    } else {
                        client.player.sendMessage(Text.literal(
                                "§e[ContainerReader] No previous scan. Open a chest and press F7 first."), false);
                    }
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
                    "§a[ContainerReader] Dumped §f" + written + " §aslot(s) from §f\"" +
                            screen.getTitle().getString() + "\"§a → §f" + DUMP_FILE.getFileName()), false);
            client.player.sendMessage(Text.literal(
                    "§7[ContainerReader] Full path: §f" + DUMP_FILE.toAbsolutePath()), false);
        }
    }

    // ── F7 on Auction House: compare listings to shop_data ───────────────────

    /**
     * Parse open AH listings, upsert to auction_house.csv, then compare each
     * listing's vanilla item name against shop_data BUYING (sell-to-shop) and
     * SELLING (buy-from-shop) offers. Custom/OP display names rarely hit
     * shop_data — those show as "no shop match".
     */
    private void evaluateAuctionHouse(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        AuctionHouseLogger.CaptureResult result;
        try {
            result = AuctionHouseLogger.captureAndUpsert(screen);
        } catch (Exception e) {
            client.player.sendMessage(Text.literal(
                    "§c[AuctionHouse] Failed to save: " + e.getMessage()), false);
            return;
        }

        List<AuctionHouseLogger.Listing> listings = result.listings;
        if (listings.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§e[AuctionHouse] No listings parsed — empty page or UI-only slots?"), false);
            return;
        }

        Map<String, BestBuyOffer> buyOffers = loadBestOffersByAction("BUYING");
        Map<String, BestBuyOffer> sellOffers = loadBestOffersByAction("SELLING");

        List<AuctionProfitScreen.Opp> opps = new ArrayList<>();
        for (AuctionHouseLogger.Listing L : listings) {
            BestBuyOffer shopBuys = lookupBestOffer(buyOffers, L.displayName, L.vanillaName);
            BestBuyOffer shopSells = lookupBestOffer(sellOffers, L.displayName, L.vanillaName);

            if (shopBuys != null && shopBuys.price > L.price) {
                double profit = (shopBuys.price - L.price) * L.count;
                opps.add(new AuctionProfitScreen.Opp(
                        "AH→Shop", L.displayName, L.vanillaName, L.seller, L.listingType,
                        L.price, shopBuys.price, profit,
                        shopBuys.owner, shopBuys.warp, L.count));
            }
            if (shopSells != null && L.price < shopSells.price) {
                double save = (shopSells.price - L.price) * L.count;
                opps.add(new AuctionProfitScreen.Opp(
                        "AH cheaper", L.displayName, L.vanillaName, L.seller, L.listingType,
                        L.price, shopSells.price, save,
                        shopSells.owner, shopSells.warp, L.count));
            }
            // AH costs more than a player shop SELLING — you'd overpay on AH
            if (shopSells != null && L.price > shopSells.price) {
                double overpay = (L.price - shopSells.price) * L.count;
                opps.add(new AuctionProfitScreen.Opp(
                        "Shop cheaper", L.displayName, L.vanillaName, L.seller, L.listingType,
                        L.price, shopSells.price, overpay,
                        shopSells.owner, shopSells.warp, L.count));
            }
        }

        long flipN = opps.stream().filter(o -> "AH→Shop".equals(o.kind)).count();
        long cheapN = opps.stream().filter(o -> "AH cheaper".equals(o.kind)).count();
        long overN = opps.stream().filter(o -> "Shop cheaper".equals(o.kind)).count();
        client.player.sendMessage(Text.literal(String.format(
                "§a[AH] §f%d §alistings · §f%d §aflip · §f%d §acheaper · §c%d §coverpay · §f%d §anew · §f%d §abidΔ",
                listings.size(), flipN, cheapN, overN,
                result.newListings.size(), result.priceChanges.size())), false);

        // Leave AH open on server? Closing avoids locked-state issues similar to chests.
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        client.setScreen(new AuctionProfitScreen(
                opps, listings.size(),
                result.newListings.size(), result.priceChanges.size()));
    }

    /**
     * Best price per item name for a given Action column (BUYING or SELLING).
     * Lightweight one-shot parse — not the multi-offer cascade used by chest F7.
     */
    private static Map<String, BestBuyOffer> loadBestOffersByAction(String actionWanted) {
        Map<String, BestBuyOffer> best = new HashMap<>();
        if (!Files.exists(SHOP_DATA_FILE)) return best;
        try (BufferedReader br = Files.newBufferedReader(SHOP_DATA_FILE)) {
            String line = br.readLine();
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = parseCsvLine(line);
                if (p.length < 7) continue;
                if (!actionWanted.equalsIgnoreCase(p[5])) continue;
                if ("Dead".equalsIgnoreCase(p[6])) continue;
                double price;
                try { price = Double.parseDouble(p[4]); }
                catch (NumberFormatException e) { continue; }
                String item = stripFormatting(p[2]);
                if (item.isEmpty()) continue;
                String owner = p[1];
                String location = p[0];
                String stock = p[3];
                String warp = p.length > 8 ? p[8] : "";
                BestBuyOffer existing = best.get(item);
                boolean better = existing == null ||
                        ("BUYING".equalsIgnoreCase(actionWanted) ? price > existing.price : price < existing.price);
                if (better) {
                    best.put(item, new BestBuyOffer(price, owner, warp, location, stock));
                }
            }
        } catch (IOException e) {
            System.err.println("[AuctionHouse] shop_data read failed: " + e.getMessage());
        }
        return best;
    }


    // ── F7: worth evaluator (single-sided best-sell) ─────────────────────────

    private void evaluateContainerWorth(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Map<String, java.util.List<BestBuyOffer>> allOffers = getAllBuyOffers();

        double total = 0.0;
        int pricedStacks = 0;
        int unpricedStacks = 0;
        List<String> missingItems = new ArrayList<>();

        // Aggregate by display name (custom OP items stay distinct from vanilla).
        Map<String, Integer> countByName = new LinkedHashMap<>();
        Map<String, String> vanillaByDisplay = new HashMap<>();

        for (Slot slot : screen.getScreenHandler().slots) {
            // HandledScreen includes the player's 36 inv slots at the bottom.
            // Only value the actual container/chest portion.
            if (slot.inventory instanceof PlayerInventory) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String display = stripFormatting(stack.getName().getString());
            String vanilla = stripFormatting(stack.getItem().getName().getString());
            if (display.isEmpty()) display = vanilla;
            int count = stack.getCount();
            countByName.merge(display, count, Integer::sum);
            vanillaByDisplay.putIfAbsent(display, vanilla);
        }

        if (countByName.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§e[ContainerReader] Container is empty — nothing to value."), false);
            return;
        }

        // Allocate each item across shops (highest price first) until the
        // chest amount is covered or no more buy-space remains.
        List<ContainerWorthHud.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : countByName.entrySet()) {
            String name = e.getKey();
            int containerCount = e.getValue();
            String vanilla = vanillaByDisplay.getOrDefault(name, name);
            java.util.List<BestBuyOffer> offers = lookupOffers(allOffers, name, vanilla);

            if (offers.isEmpty()) {
                unpricedStacks++; // counted once per distinct item for summary
                if (missingItems.size() < 6) missingItems.add(name);
                entries.add(ContainerWorthHud.Entry.unsellable(name, containerCount));
                continue;
            }

            int remaining = containerCount;
            boolean anyPriced = false;
            for (BestBuyOffer offer : offers) {
                if (remaining <= 0) break;
                int space = parseShopSpace(offer.stockSpace);
                int take;
                if (space < 0) {
                    // Unknown capacity — treat as able to take the rest
                    take = remaining;
                } else if (space == 0) {
                    continue; // shop full
                } else {
                    take = Math.min(remaining, space);
                }
                if (take <= 0) continue;

                entries.add(new ContainerWorthHud.Entry(
                        name, take, containerCount, offer, space));
                total += offer.price * take;
                remaining -= take;
                anyPriced = true;
            }

            if (anyPriced) {
                pricedStacks++; // distinct items with at least one priced leg
            }
            if (remaining > 0) {
                // Leftover with no more shops — same treatment as "no price"
                unpricedStacks++;
                entries.add(ContainerWorthHud.Entry.unsellable(name, remaining, containerCount));
                if (!missingItems.contains(name) && missingItems.size() < 6) {
                    missingItems.add(name + " (partial)");
                }
            }
        }

        entries.sort((a, b) -> Double.compare(b.subtotal, a.subtotal));
        ContainerWorthHud.update(total, pricedStacks, unpricedStacks, entries);

        // One-line chat summary; full interactive breakdown is on the screen.
        client.player.sendMessage(Text.literal(
                "§a[ContainerReader] Chest worth: §f$" + String.format(Locale.US, "%,.2f", total) +
                        " §7(" + countByName.size() + " items, " +
                        pricedStacks + " priced / " + unpricedStacks + " unpriced stacks)"), false);

        if (!missingItems.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§eNo BUYING price for: §f" + String.join("§7, §f", missingItems) +
                            (unpricedStacks > missingItems.size() ? "§7, …" : "")), false);
        }

        if (pricedStacks == 0 && allOffers.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§cNo BUYING offers loaded. Open a server sell menu or scan player shops first."), false);
        }

        // Close the container on the server first, otherwise the server keeps the
        // chest/shulker "open" (and locked for others) after we swap screens.
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        client.setScreen(new ContainerWorthScreen(entries, total, pricedStacks, unpricedStacks));
    }

    /**
     * All active BUYING offers per item name from shop_data.csv, each list
     * sorted by price descending. Deduplicated by owner+warp+location
     * (keeps the higher price). Used for stock-aware multi-shop allocation.
     */
    private static Map<String, java.util.List<BestBuyOffer>> getAllBuyOffers() {
        try {
            boolean exists = Files.exists(SHOP_DATA_FILE);
            long modTime = exists ? Files.getLastModifiedTime(SHOP_DATA_FILE).toMillis() : -1L;

            if (bestBuyOfferCache != null && modTime == cachedFileModTime) {
                lastLoadFromCache = true;
                return bestBuyOfferCache;
            }
            lastLoadFromCache = false;

            if (!exists) {
                lastLoadSummary = "shop_data.csv §cMISSING§7 — expected at config/sunnyMod/";
                Map<String, java.util.List<BestBuyOffer>> empty = new HashMap<>();
                bestBuyOfferCache = empty;
                cachedFileModTime = modTime;
                return empty;
            }

            // item -> (shopKey -> best offer for that shop)
            Map<String, Map<String, BestBuyOffer>> byItem = new HashMap<>();
            int totalRows = 0, buyingRows = 0, deadSkipped = 0, badPrice = 0, shortRows = 0;
            long fileBytes = Files.size(SHOP_DATA_FILE);

            try (BufferedReader br = Files.newBufferedReader(SHOP_DATA_FILE)) {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    totalRows++;
                    if (line.isBlank()) continue;
                    String[] parts = parseCsvLine(line);
                    if (parts.length < 7) {
                        shortRows++;
                        continue;
                    }
                    // Shop Location, Shop Owner, Item, Stock/Space, Price, Action, Status, Timestamp, Warp
                    String location   = parts[0];
                    String owner      = parts[1];
                    String item       = parts[2];
                    String stockSpace = parts[3];
                    String action     = parts[5];
                    String status     = parts[6];
                    String warp       = parts.length > 8 ? parts[8] : "";

                    if (!"BUYING".equalsIgnoreCase(action)) continue;
                    if ("Dead".equalsIgnoreCase(status)) {
                        deadSkipped++;
                        continue;
                    }

                    double price;
                    try {
                        price = Double.parseDouble(parts[4]);
                    } catch (NumberFormatException e) {
                        badPrice++;
                        continue;
                    }

                    buyingRows++;
                    String itemKey = stripFormatting(item);
                    if (itemKey.isEmpty()) continue;
                    String shopKey = owner + "\0" + warp + "\0" + location;
                    Map<String, BestBuyOffer> shops = byItem.computeIfAbsent(itemKey, k -> new HashMap<>());
                    BestBuyOffer existing = shops.get(shopKey);
                    if (existing == null || price > existing.price) {
                        shops.put(shopKey, new BestBuyOffer(price, owner, warp, location, stockSpace));
                    }
                }
            }

            Map<String, java.util.List<BestBuyOffer>> offers = new HashMap<>();
            for (Map.Entry<String, Map<String, BestBuyOffer>> e : byItem.entrySet()) {
                java.util.List<BestBuyOffer> list = new ArrayList<>(e.getValue().values());
                list.sort((a, b) -> Double.compare(b.price, a.price));
                offers.put(e.getKey(), list);
            }

            lastLoadSummary = String.format(Locale.US,
                    "loaded §f%d§7 items / §f%d§7 shop-offers from §f%d§7 rows (§f%d§7 BUYING, §f%d§7 dead skipped, §f%d§7 bad price, §f%d§7 short) — file §f%,d§7 bytes",
                    offers.size(), buyingRows, totalRows, buyingRows, deadSkipped, badPrice, shortRows, fileBytes);

            bestBuyOfferCache = offers;
            cachedFileModTime = modTime;
            return offers;
        } catch (IOException e) {
            lastLoadFromCache = false;
            lastLoadSummary = "§cIO error reading shop_data.csv: " + e.getMessage();
            System.err.println("[ContainerReader] Failed to load shop_data.csv for valuation: " + e.getMessage());
            return bestBuyOfferCache != null ? bestBuyOfferCache : new HashMap<>();
        }
    }

    /** Parse shop buy-space from Stock/Space; -1 if unknown. */
    static int parseShopSpace(String stockSpace) {
        if (stockSpace == null || stockSpace.isBlank()) return -1;
        try {
            String s = stockSpace.trim().replace(",", "");
            if (s.contains("/")) s = s.split("/")[0].trim();
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
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