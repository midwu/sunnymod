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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passively captures Auction House listings when the "Auction House" GUI opens.
 * Upserts config/sunnyMod/auction_house.csv. Price changes append
 * auction_house_history.csv.
 *
 * Key includes price so two concurrent listings of the same item by the same
 * seller (e.g. two Nightwatch Helms at $500k and $525k) stay distinct.
 * When a BID price moves, we soft-match the previous row (same seller/item/
 * display/type, different price) and rewrite the key + history.
 *
 * Chat is quiet on open unless something is new or a bid/price moved.
 */
public class AuctionHouseLogger implements ClientModInitializer {

  static final Path CONFIG_DIR = ShopLogger.getConfigDir();
  static final Path AH_FILE = CONFIG_DIR.resolve("auction_house.csv");
  static final Path AH_HISTORY_FILE = CONFIG_DIR.resolve("auction_house_history.csv");

  private static final DateTimeFormatter TS =
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final String HEADER =
          "ListingKey,ItemId,DisplayName,VanillaName,Count,Seller,ListingType,Price,BidIncrement,HighestBidder,TimeLeft,FirstSeen,LastSeen";

  private static final String HISTORY_HEADER =
          "Timestamp,ListingKey,ItemId,DisplayName,Seller,ListingType,OldPrice,NewPrice,TimeLeft";

  static final String AH_TITLE = "Auction House";

  private static final Pattern SELLER = Pattern.compile(
          "Seller:\\s*(.+?)(?:\\s*\\||$)", Pattern.CASE_INSENSITIVE);
  private static final Pattern BUY_NOW = Pattern.compile(
          "Buy Now:\\s*\\$([0-9,]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
  private static final Pattern CURRENT = Pattern.compile(
          "Current Price:\\s*\\$([0-9,]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
  private static final Pattern BID_INC = Pattern.compile(
          "Bid Increment:\\s*\\$([0-9,]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
  private static final Pattern HIGHEST = Pattern.compile(
          "Highest Bidder:\\s*(.+?)(?:\\s*\\||$)", Pattern.CASE_INSENSITIVE);
  private static final Pattern TIME_LEFT = Pattern.compile(
          "Time Left:\\s*([^|]+)", Pattern.CASE_INSENSITIVE);

  private static HandledScreen<?> pendingScreen = null;
  private static int pendingTicks = 0;

  /** Result of an upsert — used for quiet chat + F7 screen. */
  public static final class CaptureResult {
    public final List<Listing> listings;
    public final List<Listing> newListings;
    public final List<PriceChange> priceChanges;
    public final int totalOnPage;

    public CaptureResult(List<Listing> listings, List<Listing> newListings,
                         List<PriceChange> priceChanges) {
      this.listings = listings;
      this.newListings = newListings;
      this.priceChanges = priceChanges;
      this.totalOnPage = listings.size();
    }

    public boolean hasNews() {
      return !newListings.isEmpty() || !priceChanges.isEmpty();
    }
  }

  public static final class PriceChange {
    public final Listing listing;
    public final double oldPrice;
    public final double newPrice;

    public PriceChange(Listing listing, double oldPrice, double newPrice) {
      this.listing = listing;
      this.oldPrice = oldPrice;
      this.newPrice = newPrice;
    }
  }

  public static final class Listing {
    public final String listingKey;
    public final String itemId;
    public final String displayName;
    public final String vanillaName;
    public final int count;
    public final String seller;
    public final String listingType;
    public final double price;
    public final double bidIncrement;
    public final String highestBidder;
    public final String timeLeft;

    public Listing(String listingKey, String itemId, String displayName, String vanillaName,
                   int count, String seller, String listingType, double price,
                   double bidIncrement, String highestBidder, String timeLeft) {
      this.listingKey = listingKey;
      this.itemId = itemId;
      this.displayName = displayName;
      this.vanillaName = vanillaName;
      this.count = count;
      this.seller = seller;
      this.listingType = listingType;
      this.price = price;
      this.bidIncrement = bidIncrement;
      this.highestBidder = highestBidder != null ? highestBidder : "";
      this.timeLeft = timeLeft != null ? timeLeft : "";
    }

    /** Prefix shared by soft-match siblings (price excluded). */
    public String familyKey() {
      return seller + "|" + itemId + "|" + displayName + "|" + listingType;
    }
  }

  @Override
  public void onInitializeClient() {
    try {
      Files.createDirectories(CONFIG_DIR);
    } catch (IOException e) {
      System.err.println("[AuctionHouse] Failed to create config dir: " + e.getMessage());
    }

    ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
      if (!(screen instanceof HandledScreen<?> handled)) return;
      if (!isAuctionHouse(screen.getTitle().getString())) return;
      pendingScreen = handled;
      pendingTicks = 5;
    });

    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
      if (pendingTicks <= 0 || pendingScreen == null) return;
      if (client.currentScreen != pendingScreen) {
        pendingScreen = null;
        pendingTicks = 0;
        return;
      }
      pendingTicks--;
      if (pendingTicks > 0) return;

      HandledScreen<?> screen = pendingScreen;
      pendingScreen = null;
      try {
        CaptureResult result = captureAndUpsert(screen);
        // Quiet: only chat when something is actually new or a bid moved
        if (client.player != null && result.hasNews()) {
          if (!result.newListings.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§a[AH] §f" + result.newListings.size() + " §anew listing(s)"), false);
            int shown = 0;
            for (Listing L : result.newListings) {
              if (shown++ >= 5) {
                client.player.sendMessage(Text.literal("§7  …"), false);
                break;
              }
              client.player.sendMessage(Text.literal(String.format(Locale.US,
                      "§7  + §f%s §7@ §f$%,.0f §8(%s)",
                      L.displayName, L.price, L.seller)), false);
            }
          }
          if (!result.priceChanges.isEmpty()) {
            client.player.sendMessage(Text.literal(
                    "§e[AH] §f" + result.priceChanges.size() + " §eprice/bid change(s)"), false);
            int shown = 0;
            for (PriceChange pc : result.priceChanges) {
              if (shown++ >= 5) {
                client.player.sendMessage(Text.literal("§7  …"), false);
                break;
              }
              client.player.sendMessage(Text.literal(String.format(Locale.US,
                      "§7  §f%s §7$%,.0f → §f$%,.0f §8(%s)",
                      pc.listing.displayName, pc.oldPrice, pc.newPrice, pc.listing.seller)), false);
            }
          }
        }
      } catch (Exception e) {
        System.err.println("[AuctionHouse] Capture failed: " + e.getMessage());
        e.printStackTrace();
      }
    });
  }

  public static boolean isAuctionHouse(String title) {
    if (title == null) return false;
    String t = title.replaceAll("§.", "").trim();
    return t.equalsIgnoreCase(AH_TITLE)
            || t.toLowerCase(Locale.ROOT).contains("auction house");
  }

  public static CaptureResult captureAndUpsert(HandledScreen<?> screen) throws IOException {
    List<Listing> listings = parseListings(screen);
    return upsertListings(listings);
  }

  public static List<Listing> parseListings(HandledScreen<?> screen) {
    MinecraftClient client = MinecraftClient.getInstance();
    List<Listing> out = new ArrayList<>();
    if (client.player == null) return out;

    for (Slot slot : screen.getScreenHandler().slots) {
      ItemStack stack = slot.getStack();
      if (stack.isEmpty()) continue;

      String id = String.valueOf(stack.getItem());
      if (id.contains("stained_glass_pane") || id.contains("black_stained_glass")) continue;

      String displayName = stack.getName().getString().trim();
      if (displayName.isEmpty()) continue;

      List<Text> tooltip = stack.getTooltip(
              Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC);
      StringBuilder loreSb = new StringBuilder();
      for (int i = 1; i < tooltip.size(); i++) {
        if (!loreSb.isEmpty()) loreSb.append(" | ");
        loreSb.append(tooltip.get(i).getString());
      }
      String lore = loreSb.toString();

      Matcher sm = SELLER.matcher(lore);
      if (!sm.find()) continue;
      String seller = sm.group(1).trim();

      String listingType;
      double price;
      Matcher bn = BUY_NOW.matcher(lore);
      Matcher cp = CURRENT.matcher(lore);
      if (bn.find()) {
        listingType = "BUY_NOW";
        price = parseMoney(bn.group(1));
      } else if (cp.find()) {
        listingType = "BID";
        price = parseMoney(cp.group(1));
      } else {
        continue;
      }

      double bidInc = 0;
      Matcher bi = BID_INC.matcher(lore);
      if (bi.find()) bidInc = parseMoney(bi.group(1));

      String highest = "";
      Matcher hm = HIGHEST.matcher(lore);
      if (hm.find()) highest = hm.group(1).trim();

      String timeLeft = "";
      Matcher tm = TIME_LEFT.matcher(lore);
      if (tm.find()) timeLeft = tm.group(1).trim();

      String vanillaName = stack.getItem().getName().getString();
      int count = stack.getCount();
      String key = buildKey(seller, id, displayName, listingType, price);

      out.add(new Listing(key, id, displayName, vanillaName, count,
              seller, listingType, price, bidInc, highest, timeLeft));
    }
    return out;
  }

  /**
   * Key includes price so concurrent same-seller listings stay distinct.
   * BID price moves are handled via soft-match in upsertListings.
   */
  public static String buildKey(String seller, String itemId, String displayName,
                                String listingType, double price) {
    return seller + "|" + itemId + "|" + displayName + "|" + listingType + "|" +
            String.format(Locale.US, "%.2f", price);
  }

  private static double parseMoney(String raw) {
    try {
      return Double.parseDouble(raw.replace(",", "").trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static CaptureResult upsertListings(List<Listing> listings) throws IOException {
    Files.createDirectories(CONFIG_DIR);
    Map<String, String[]> existing = loadExisting();
    String now = LocalDateTime.now().format(TS);

    List<Listing> newListings = new ArrayList<>();
    List<PriceChange> priceChanges = new ArrayList<>();
    List<String> historyLines = new ArrayList<>();
    Set<String> claimedOldKeys = new HashSet<>();

    for (Listing L : listings) {
      if (existing.containsKey(L.listingKey)) {
        // Exact key hit — refresh last seen / time left / bidder
        String[] prev = existing.get(L.listingKey);
        String firstSeen = prev.length > 11 ? prev[11] : now;
        existing.put(L.listingKey, toRow(L, firstSeen, now));
        continue;
      }

      // Soft-match: same family, different price, not yet claimed this scan
      String family = L.familyKey();
      String softKey = null;
      double oldPrice = 0;
      for (Map.Entry<String, String[]> e : existing.entrySet()) {
        if (claimedOldKeys.contains(e.getKey())) continue;
        if (!e.getKey().startsWith(family + "|") && !e.getKey().equals(family)) {
          // family key form is seller|id|name|type — full key adds |price
          if (!e.getKey().startsWith(family + "|")) continue;
        }
        if (!e.getKey().startsWith(family + "|")) continue;
        try {
          oldPrice = Double.parseDouble(e.getValue()[7].replace(",", ""));
        } catch (Exception ignored) {
          oldPrice = 0;
        }
        if (Math.abs(oldPrice - L.price) < 0.001) continue; // same price, different issue
        softKey = e.getKey();
        break;
      }

      if (softKey != null) {
        claimedOldKeys.add(softKey);
        String[] prev = existing.remove(softKey);
        String firstSeen = prev != null && prev.length > 11 ? prev[11] : now;
        existing.put(L.listingKey, toRow(L, firstSeen, now));
        priceChanges.add(new PriceChange(L, oldPrice, L.price));
        historyLines.add(String.join(",",
                escape(now),
                escape(L.listingKey),
                escape(L.itemId),
                escape(L.displayName),
                escape(L.seller),
                L.listingType,
                String.format(Locale.US, "%.2f", oldPrice),
                String.format(Locale.US, "%.2f", L.price),
                escape(L.timeLeft)));
      } else {
        existing.put(L.listingKey, toRow(L, now, now));
        newListings.add(L);
      }
    }

    if (!historyLines.isEmpty()) {
      boolean needHistHeader = !Files.exists(AH_HISTORY_FILE);
      try (BufferedWriter hw = Files.newBufferedWriter(AH_HISTORY_FILE,
              StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        if (needHistHeader) {
          hw.write(HISTORY_HEADER);
          hw.newLine();
        }
        for (String hl : historyLines) {
          hw.write(hl);
          hw.newLine();
        }
      }
    }

    try (BufferedWriter w = Files.newBufferedWriter(AH_FILE,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      w.write(HEADER);
      w.newLine();
      for (String[] row : existing.values()) {
        w.write(String.join(",", row));
        w.newLine();
      }
    }

    return new CaptureResult(listings, newListings, priceChanges);
  }

  private static String[] toRow(Listing L, String firstSeen, String lastSeen) {
    return new String[]{
            escape(L.listingKey),
            escape(L.itemId),
            escape(L.displayName),
            escape(L.vanillaName),
            String.valueOf(L.count),
            escape(L.seller),
            L.listingType,
            String.format(Locale.US, "%.2f", L.price),
            String.format(Locale.US, "%.2f", L.bidIncrement),
            escape(L.highestBidder),
            escape(L.timeLeft),
            escape(firstSeen),
            escape(lastSeen)
    };
  }

  private static Map<String, String[]> loadExisting() throws IOException {
    Map<String, String[]> map = new LinkedHashMap<>();
    if (!Files.exists(AH_FILE)) return map;
    try (BufferedReader br = Files.newBufferedReader(AH_FILE)) {
      String line = br.readLine();
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] parts = parseCsvLine(line);
        if (parts.length < 13) continue;
        map.put(parts[0], parts);
      }
    }
    return map;
  }

  private static String escape(String s) {
    if (s == null) return "";
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
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
}