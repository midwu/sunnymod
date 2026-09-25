package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the server's Public Warps GUI into warp_data.csv and compares those
 * public warps with the warp column in shop_data.csv.
 */
public final class WarpData {
    private WarpData() {}

    private static final Path CONFIG_DIR = ShopLogger.getConfigDir();
    private static final Path WARP_FILE = CONFIG_DIR.resolve("warp_data.csv");
    private static final Path SHOP_FILE = CONFIG_DIR.resolve("shop_data.csv");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String HEADER =
            "Warp,Type,MonthlyVisits,AllTimeVisits,ItemId,LastSeen";

    public record WarpEntry(
            String warp,
            String type,
            long monthlyVisits,
            long allTimeVisits,
            String itemId,
            String lastSeen
    ) {}

    /**
     * A row used by WarpDataScreen. Missing means the warp exists in shop_data
     * but is not currently present in warp_data.csv.
     */
    public record WarpRow(
            String warp,
            String type,
            long monthlyVisits,
            long allTimeVisits,
            boolean missingFromPublicWarps
    ) {}

    public static boolean isPublicWarps(HandledScreen<?> screen) {
        if (screen == null) return false;
        String title = screen.getTitle().getString();
        return title != null && title.trim().equalsIgnoreCase("Public Warps");
    }

    /**
     * Scan only the container side of the GUI. PlayerInventory slots are ignored.
     * Existing rows are replaced by the current values for the same warp name.
     */
    public static int updateFromContainer(HandledScreen<?> screen) {
        if (!isPublicWarps(screen)) return 0;

        Map<String, WarpEntry> entries = loadMap();
        MinecraftClient client = MinecraftClient.getInstance();
        int found = 0;

        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String warp = stripFormatting(stack.getName().getString()).trim();
            if (warp.isEmpty()) continue;

            List<Text> tooltip = stack.getTooltip(
                    Item.TooltipContext.DEFAULT,
                    client.player,
                    TooltipType.BASIC);

            String type = "";
            long monthly = 0;
            long allTime = 0;
            boolean hasUsefulWarpData = false;

            for (int i = 1; i < tooltip.size(); i++) {
                String line = stripFormatting(tooltip.get(i).getString()).trim();
                if (line.isEmpty()) continue;

                String lower = line.toLowerCase(Locale.ROOT);

                if (lower.startsWith("type:")) {
                    type = line.substring(line.indexOf(':') + 1).trim();
                    hasUsefulWarpData = true;
                } else if (lower.startsWith("monthly visits:")) {
                    monthly = parseNumber(line.substring(line.indexOf(':') + 1));
                    hasUsefulWarpData = true;
                } else if (lower.startsWith("all-time visits:")) {
                    allTime = parseNumber(line.substring(line.indexOf(':') + 1));
                    hasUsefulWarpData = true;
                } else if (lower.startsWith("all time visits:")) {
                    allTime = parseNumber(line.substring(line.indexOf(':') + 1));
                    hasUsefulWarpData = true;
                }
            }

            /*
             * The Public Warps GUI can contain decorative items/buttons. A real
             * warp entry has the metadata above, so skip unrelated GUI items.
             */
            if (!hasUsefulWarpData) continue;

            String key = normalizeWarpName(warp);
            String itemId = String.valueOf(stack.getItem());

            entries.put(key, new WarpEntry(
                    warp,
                    type,
                    monthly,
                    allTime,
                    itemId,
                    LocalDateTime.now().format(TIME)));
            found++;
        }

        write(entries);
        return found;
    }

    public static List<WarpEntry> load() {
        return new ArrayList<>(loadMap().values());
    }

    public static List<WarpRow> buildRows() {
        Map<String, WarpEntry> publicWarps = loadMap();
        Map<String, ShopWarp> shopWarps = loadShopWarps();

        Map<String, WarpRow> rows = new LinkedHashMap<>();

        // Shop warps first, because those are the entries relevant to shop_data.
        for (ShopWarp shop : shopWarps.values()) {
            WarpEntry publicEntry = publicWarps.get(shop.key);
            if (publicEntry == null) {
                rows.put(shop.key, new WarpRow(
                        shop.warp, "shop", 0, 0, true));
            } else {
                String type = publicEntry.type().isBlank() ? shop.type : publicEntry.type();
                rows.put(shop.key, new WarpRow(
                        publicEntry.warp(),
                        type,
                        publicEntry.monthlyVisits(),
                        publicEntry.allTimeVisits(),
                        false));
            }
        }

        // Then add public warps which do not occur in shop_data.
        for (WarpEntry entry : publicWarps.values()) {
            String key = normalizeWarpName(entry.warp());
            rows.putIfAbsent(key, new WarpRow(
                    entry.warp(),
                    entry.type(),
                    entry.monthlyVisits(),
                    entry.allTimeVisits(),
                    false));
        }

        List<WarpRow> result = new ArrayList<>(rows.values());
        result.sort(Comparator
                .comparing((WarpRow r) -> !isShopType(r.type()))
                .thenComparing(WarpRow::missingFromPublicWarps, Comparator.reverseOrder())
                .thenComparing(r -> r.warp().toLowerCase(Locale.ROOT)));
        return result;
    }

    public static List<WarpRow> missingShopWarps() {
        List<WarpRow> out = new ArrayList<>();
        for (WarpRow row : buildRows()) {
            if (row.missingFromPublicWarps()) out.add(row);
        }
        return out;
    }

    public static Path getFile() {
        return WARP_FILE;
    }

    public static boolean isShopType(String type) {
        return type != null && type.trim().equalsIgnoreCase("shop");
    }

    public static String normalizeWarpName(String warp) {
        if (warp == null) return "";
        String w = warp.trim();
        if (w.startsWith("/warp ")) w = w.substring(6).trim();
        else if (w.startsWith("warp ")) w = w.substring(5).trim();
        return w.toLowerCase(Locale.ROOT);
    }

    private static Map<String, WarpEntry> loadMap() {
        Map<String, WarpEntry> result = new LinkedHashMap<>();
        if (!Files.exists(WARP_FILE)) return result;

        try (BufferedReader br = Files.newBufferedReader(WARP_FILE)) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = parseCsvLine(line);
                if (p.length < 6) continue;

                String warp = p[0].trim();
                if (warp.isEmpty()) continue;

                result.put(normalizeWarpName(warp), new WarpEntry(
                        warp,
                        p[1],
                        parseNumber(p[2]),
                        parseNumber(p[3]),
                        p[4],
                        p[5]));
            }
        } catch (IOException e) {
            System.err.println("[WarpData] Failed to read " + WARP_FILE + ": " + e.getMessage());
        }
        return result;
    }

    private static Map<String, ShopWarp> loadShopWarps() {
        Map<String, ShopWarp> result = new LinkedHashMap<>();
        if (!Files.exists(SHOP_FILE)) return result;

        try (BufferedReader br = Files.newBufferedReader(SHOP_FILE)) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] p = parseCsvLine(line);
                if (p.length <= 8) continue;

                String status = p[6].trim();
                if ("Dead".equalsIgnoreCase(status)) continue;

                String warp = p[8].trim();
                if (warp.isEmpty()) continue;

                String key = normalizeWarpName(warp);
                if (key.isEmpty()) continue;

                // shop_data itself may have several rows for the same warp.
                // Keep one entry; the comparison is about warp existence.
                result.putIfAbsent(key, new ShopWarp(key, warp, "shop"));
            }
        } catch (IOException e) {
            System.err.println("[WarpData] Failed to read shop_data.csv: " + e.getMessage());
        }
        return result;
    }

    private record ShopWarp(String key, String warp, String type) {}

    private static void write(Map<String, WarpEntry> entries) {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    WARP_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {

                writer.write(HEADER);
                writer.newLine();

                for (WarpEntry e : entries.values()) {
                    writer.write(escape(e.warp()) + "," +
                            escape(e.type()) + "," +
                            e.monthlyVisits() + "," +
                            e.allTimeVisits() + "," +
                            escape(e.itemId()) + "," +
                            escape(e.lastSeen()));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("[WarpData] Failed to write " + WARP_FILE + ": " + e.getMessage());
        }
    }

    private static long parseNumber(String value) {
        if (value == null) return 0;
        String s = value.replace(",", "").replace(".", "").trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
