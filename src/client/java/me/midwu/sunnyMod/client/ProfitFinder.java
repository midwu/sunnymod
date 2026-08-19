package me.midwu.sunnyMod.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-game port of {@code profit_finder.py} + {@code self_flip_finder.py}.
 * Ignore lists live under {@code config/sunnyMod/} (same names as the Python tool).
 */
public final class ProfitFinder {

    private ProfitFinder() {}

    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("sunnyMod");
    private static final Path SHOP_DATA = CONFIG.resolve("shop_data.csv");
    private static final Path IGNORE_ITEMS = CONFIG.resolve("ignore_items.txt");
    private static final Path IGNORE_OWNERS = CONFIG.resolve("ignore_owners.txt");
    private static final Path IGNORE_WARPS = CONFIG.resolve("ignore_warps.txt");

    public enum IgnoreKind {
        ITEMS("Items", IGNORE_ITEMS),
        PLAYERS("Players", IGNORE_OWNERS),
        WARPS("Warps", IGNORE_WARPS);

        public final String label;
        public final Path file;

        IgnoreKind(String label, Path file) {
            this.label = label;
            this.file = file;
        }
    }

    /** One allocated flip (normal) or same-owner margin (self). */
    public static final class Trade {
        public final String item;
        public final String seller;
        public final String sellerWarp;
        public final String sellerLocation;
        public final double sellPrice;
        public final String buyer;
        public final String buyerWarp;
        public final String buyerLocation;
        public final double buyPrice;
        public final int quantity; // 0 = self-flip (unlimited / grind)
        public final double profitPerItem;
        public final double totalProfit;
        public final double capital;
        public final boolean selfFlip;

        public Trade(String item, String seller, String sellerWarp, String sellerLocation,
                     double sellPrice, String buyer, String buyerWarp, String buyerLocation,
                     double buyPrice, int quantity, double profitPerItem, boolean selfFlip) {
            this.item = item;
            this.seller = seller;
            this.sellerWarp = sellerWarp != null ? sellerWarp : "";
            this.sellerLocation = sellerLocation != null ? sellerLocation : "";
            this.sellPrice = sellPrice;
            this.buyer = buyer;
            this.buyerWarp = buyerWarp != null ? buyerWarp : "";
            this.buyerLocation = buyerLocation != null ? buyerLocation : "";
            this.buyPrice = buyPrice;
            this.quantity = quantity;
            this.profitPerItem = profitPerItem;
            this.selfFlip = selfFlip;
            this.totalProfit = selfFlip ? profitPerItem : profitPerItem * Math.max(0, quantity);
            this.capital = selfFlip ? sellPrice : sellPrice * Math.max(0, quantity);
        }
    }

    public static final class Result {
        public final List<Trade> trades;
        public final int sellingRows;
        public final int buyingRows;
        public final int candidatePairs;
        public final String loadSummary;
        public final double totalProfit;
        public final double totalCapital;
        public final boolean selfFlipMode;

        Result(List<Trade> trades, int sellingRows, int buyingRows, int candidatePairs,
               String loadSummary, boolean selfFlipMode) {
            this.trades = trades;
            this.sellingRows = sellingRows;
            this.buyingRows = buyingRows;
            this.candidatePairs = candidatePairs;
            this.loadSummary = loadSummary;
            this.selfFlipMode = selfFlipMode;
            double tp = 0, tc = 0;
            for (Trade t : trades) {
                tp += t.totalProfit;
                tc += t.capital;
            }
            this.totalProfit = tp;
            this.totalCapital = tc;
        }
    }

    private static final class Listing {
        final String item;
        final String owner;
        final String warp;
        final String location;
        final double price;
        final int stock;
        final int id;
        final long epochMs;

        Listing(String item, String owner, String warp, String location,
                double price, int stock, int id, long epochMs) {
            this.item = item;
            this.owner = owner;
            this.warp = warp;
            this.location = location;
            this.price = price;
            this.stock = stock;
            this.id = id;
            this.epochMs = epochMs;
        }
    }

    private static final class Candidate {
        final Listing seller;
        final Listing buyer;
        final double profitPerItem;

        Candidate(Listing seller, Listing buyer) {
            this.seller = seller;
            this.buyer = buyer;
            this.profitPerItem = buyer.price - seller.price;
        }
    }

    // ── Ignore lists ─────────────────────────────────────────────────────────

    public static Set<String> loadIgnore(IgnoreKind kind) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            if (!Files.exists(kind.file)) return out;
            for (String line : Files.readAllLines(kind.file)) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
        } catch (Exception e) {
            System.err.println("[ProfitFinder] load ignore " + kind + ": " + e.getMessage());
        }
        return out;
    }

    public static void saveIgnore(IgnoreKind kind, Set<String> entries) {
        try {
            Files.createDirectories(CONFIG);
            List<String> lines = new ArrayList<>();
            lines.add("# sunnyMod ignore list — one entry per line");
            TreeSet<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            sorted.addAll(entries);
            lines.addAll(sorted);
            Files.write(kind.file, lines);
        } catch (Exception e) {
            System.err.println("[ProfitFinder] save ignore " + kind + ": " + e.getMessage());
        }
    }

    public static boolean addIgnore(IgnoreKind kind, String entry) {
        if (entry == null || entry.isBlank()) return false;
        Set<String> set = loadIgnore(kind);
        boolean added = set.add(entry.trim());
        if (added) saveIgnore(kind, set);
        return added;
    }

    public static boolean removeIgnore(IgnoreKind kind, String entry) {
        if (entry == null || entry.isBlank()) return false;
        Set<String> set = loadIgnore(kind);
        boolean removed = set.removeIf(s -> s.equalsIgnoreCase(entry.trim()));
        if (removed) saveIgnore(kind, set);
        return removed;
    }

    // ── Finders ──────────────────────────────────────────────────────────────

    public static Result findFlips(double minProfitPerItem, double minTotalProfit,
                                   int limit, double maxAgeHours) {
        return findInternal(false, minProfitPerItem, minTotalProfit, limit, maxAgeHours);
    }

    public static Result findSelfFlips(double minProfitPerItem, int limit, double maxAgeHours) {
        return findInternal(true, minProfitPerItem, 0, limit, maxAgeHours);
    }

    private static Result findInternal(boolean selfFlip, double minProfitPerItem,
                                       double minTotalProfit, int limit, double maxAgeHours) {
        if (!Files.exists(SHOP_DATA)) {
            return new Result(List.of(), 0, 0, 0,
                    "shop_data.csv missing — expected config/sunnyMod/", selfFlip);
        }

        Set<String> ignoreItems = loadIgnore(IgnoreKind.ITEMS);
        Set<String> ignoreOwners = loadIgnore(IgnoreKind.PLAYERS);
        Set<String> ignoreWarps = loadIgnore(IgnoreKind.WARPS);

        List<Listing> sellers = new ArrayList<>();
        List<Listing> buyers = new ArrayList<>();
        int totalRows = 0, skipped = 0;

        try (BufferedReader br = Files.newBufferedReader(SHOP_DATA)) {
            String header = br.readLine();
            if (header == null) {
                return new Result(List.of(), 0, 0, 0, "shop_data.csv empty", selfFlip);
            }
            String line;
            int nextId = 0;
            while ((line = br.readLine()) != null) {
                totalRows++;
                if (line.isBlank()) continue;
                String[] p = parseCsvLine(line);
                if (p.length < 7) {
                    skipped++;
                    continue;
                }
                String location = p[0].trim();
                String owner = p[1].trim();
                String item = strip(p[2]);
                String stockSpace = p[3].trim();
                String action = p[5].trim();
                String status = p[6].trim();
                String warp = p.length > 8 ? p[8].trim() : "";
                String timestamp = p.length > 7 ? p[7].trim() : "";

                if (!"Active".equalsIgnoreCase(status)) {
                    skipped++;
                    continue;
                }
                if (item.isEmpty()) {
                    skipped++;
                    continue;
                }
                if (containsIgnore(ignoreItems, item)
                        || containsIgnore(ignoreOwners, owner)
                        || containsIgnore(ignoreWarps, warp)) {
                    skipped++;
                    continue;
                }
                double price;
                try {
                    price = Double.parseDouble(p[4].replace(",", "").replace("$", "").trim());
                } catch (NumberFormatException e) {
                    skipped++;
                    continue;
                }
                if (price < 0) {
                    skipped++;
                    continue;
                }
                int stock = Container_reader.parseShopSpace(stockSpace);
                if (stock < 0) stock = Integer.MAX_VALUE / 4;
                if (stock == 0 && !selfFlip) {
                    skipped++;
                    continue;
                }
                if (stock == 0) stock = 1; // self-flip still lists the margin

                long epochMs = parseTimestampMs(timestamp);
                if ("SELLING".equalsIgnoreCase(action)) {
                    sellers.add(new Listing(item, owner, warp, location, price, stock, nextId++, epochMs));
                } else if ("BUYING".equalsIgnoreCase(action)) {
                    buyers.add(new Listing(item, owner, warp, location, price, stock, nextId++, epochMs));
                } else {
                    skipped++;
                }
            }
        } catch (Exception e) {
            return new Result(List.of(), 0, 0, 0, "IO error: " + e.getMessage(), selfFlip);
        }

        String ageNote = "age filter off";
        if (maxAgeHours > 0) {
            long newest = 0;
            for (Listing L : sellers) if (L.epochMs > newest) newest = L.epochMs;
            for (Listing L : buyers) if (L.epochMs > newest) newest = L.epochMs;
            if (newest <= 0) {
                ageNote = String.format(Locale.US, "age ≤%.0fh (no timestamps)", maxAgeHours);
            } else {
                long cutoff = newest - (long) (maxAgeHours * 3600_000L);
                int beforeS = sellers.size(), beforeB = buyers.size();
                sellers.removeIf(L -> L.epochMs > 0 && L.epochMs < cutoff);
                buyers.removeIf(L -> L.epochMs > 0 && L.epochMs < cutoff);
                ageNote = String.format(Locale.US,
                        "age ≤%.0fh (kept %d/%d sell, %d/%d buy)",
                        maxAgeHours, sellers.size(), beforeS, buyers.size(), beforeB);
            }
        }

        Map<String, List<Listing>> buyersByItem = new HashMap<>();
        for (Listing b : buyers) {
            buyersByItem.computeIfAbsent(b.item.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(b);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Listing s : sellers) {
            List<Listing> bs = buyersByItem.get(s.item.toLowerCase(Locale.ROOT));
            if (bs == null) continue;
            for (Listing b : bs) {
                boolean sameOwner = s.owner.equalsIgnoreCase(b.owner);
                if (selfFlip) {
                    if (!sameOwner) continue;
                } else {
                    if (sameOwner) continue;
                }
                if (b.price <= s.price) continue;
                double edge = b.price - s.price;
                if (edge < minProfitPerItem) continue;
                candidates.add(new Candidate(s, b));
            }
        }

        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.profitPerItem).reversed());

        List<Trade> trades = new ArrayList<>();
        if (selfFlip) {
            // No stock allocation — rank by margin only (grind rate is yours)
            Set<String> seen = new HashSet<>();
            for (Candidate c : candidates) {
                String key = c.seller.owner.toLowerCase(Locale.ROOT) + "|"
                        + c.seller.item.toLowerCase(Locale.ROOT) + "|"
                        + c.seller.price + "|" + c.buyer.price;
                if (!seen.add(key)) continue;
                trades.add(new Trade(
                        c.seller.item,
                        c.seller.owner, c.seller.warp, c.seller.location, c.seller.price,
                        c.buyer.owner, c.buyer.warp, c.buyer.location, c.buyer.price,
                        0, c.profitPerItem, true));
            }
            trades.sort(Comparator.comparingDouble((Trade t) -> t.profitPerItem).reversed());
        } else {
            Map<Integer, Integer> remainingSeller = new HashMap<>();
            Map<Integer, Integer> remainingBuyer = new HashMap<>();
            for (Candidate c : candidates) {
                remainingSeller.putIfAbsent(c.seller.id, c.seller.stock);
                remainingBuyer.putIfAbsent(c.buyer.id, c.buyer.stock);
            }
            for (Candidate c : candidates) {
                int sLeft = remainingSeller.getOrDefault(c.seller.id, 0);
                int bLeft = remainingBuyer.getOrDefault(c.buyer.id, 0);
                int qty = Math.min(sLeft, bLeft);
                if (qty <= 0) continue;
                remainingSeller.put(c.seller.id, sLeft - qty);
                remainingBuyer.put(c.buyer.id, bLeft - qty);
                Trade t = new Trade(
                        c.seller.item,
                        c.seller.owner, c.seller.warp, c.seller.location, c.seller.price,
                        c.buyer.owner, c.buyer.warp, c.buyer.location, c.buyer.price,
                        qty, c.profitPerItem, false);
                if (t.totalProfit < minTotalProfit) continue;
                trades.add(t);
            }
            trades.sort(Comparator.comparingDouble((Trade t) -> t.totalProfit).reversed());
        }

        if (limit > 0 && trades.size() > limit) {
            trades = new ArrayList<>(trades.subList(0, limit));
        }

        String modeTag = selfFlip ? "self-flip" : "flips";
        String summary = String.format(Locale.US,
                "%s · %,d rows · %,d sell · %,d buy · %,d candidates · %,d trades · %s · ignore i%d/p%d/w%d",
                modeTag, totalRows, sellers.size(), buyers.size(), candidates.size(), trades.size(),
                ageNote, ignoreItems.size(), ignoreOwners.size(), ignoreWarps.size());

        return new Result(trades, sellers.size(), buyers.size(), candidates.size(), summary, selfFlip);
    }

    private static boolean containsIgnore(Set<String> set, String value) {
        if (value == null || value.isEmpty() || set.isEmpty()) return false;
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    static long parseTimestampMs(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String s = raw.trim();
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            try {
                double v = Double.parseDouble(s);
                if (v > 1e12) return (long) v;
                if (v > 1e9) return (long) (v * 1000);
            } catch (NumberFormatException ignored) {}
        }
        try {
            String norm = s.replace(' ', 'T');
            if (norm.length() == 10) norm = norm + "T00:00:00";
            if (norm.endsWith("Z")) norm = norm.substring(0, norm.length() - 1);
            int plus = norm.lastIndexOf('+');
            if (plus > 10) norm = norm.substring(0, plus);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(norm);
            return ldt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {}
        try {
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {}
        return 0;
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }

    static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}