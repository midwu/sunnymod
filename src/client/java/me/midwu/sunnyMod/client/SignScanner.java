package me.midwu.sunnyMod.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passive shop-sign scanner.
 *
 * It reads SignBlockEntity data that Minecraft has already received from the server.
 * No sign clicking and no interaction packets are required. Shop-like signs are
 * recognized from their four visible front lines and merged into shop_data.csv.
 *
 * Every scan produces a {@link ScanReport} with per-shop change details, shown in
 * chat on manual scans and available to the ProfitScreen "Scan" tab via
 * {@link #lastReport()}.
 */
public final class SignScanner {
    private static final Path CSV_FILE = ShopLogger.getConfigDir().resolve("shop_data.csv");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_SHORT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String HEADER =
            "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp,NoChangeStreak,Source";

    private static final Pattern ACTION = Pattern.compile("^(Selling|Buying|Out of Stock|Out of Space)\\b(?:\\s+(\\d[\\d,]*))?.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("\\$\\s*([0-9][\\d,]*(?:\\.[0-9]+)?)");

    /** Upper bound on change details kept per scan, so a huge scan can't balloon memory. */
    private static final int MAX_CHANGE_DETAILS = 500;

    private static boolean initialized;
    private static KeyBinding scanKey;
    private static KeyBinding reportKey;
    private static long lastScanMs;
    private static int lastCount;

    /** Details of the most recent scan (manual or silent). Null until the first scan. */
    private static volatile ScanReport lastReport;

    private SignScanner() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Report types
    // ─────────────────────────────────────────────────────────────────────────

    /** One shop's old → new state as observed by a single scan. */
    public record ShopChange(
            String location,
            String warp,
            boolean isNew,
            String oldOwner, String newOwner,
            String oldItem, String newItem,
            int oldStock, int newStock,
            double oldPrice, double newPrice,
            String oldAction, String newAction,
            String oldStatus, String newStatus
    ) {}

    /** Summary of one scan, plus per-shop change details for new/changed shops. */
    public record ScanReport(
            String time,       // "HH:mm:ss" wall-clock time of the scan
            String warp,       // warp active during the scan (may be empty)
            int found,         // shop signs recognized in loaded chunks
            int updated,       // rows written = new + changed
            int newCount,      // shops not previously in the CSV
            int changedCount,  // existing shops whose visible data changed
            int unchangedCount,// existing shops seen again with identical data
            int missingCount,  // CSV shops at this warp that the scan did NOT see
            int csvRowsForWarp,// total CSV rows currently tagged with this warp
            List<ShopChange> changes
    ) {}

    public static ScanReport lastReport() {
        return lastReport;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Init / keybind / command
    // ─────────────────────────────────────────────────────────────────────────

    public static void init() {
        if (initialized) return;
        initialized = true;

        scanKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sunnymod.scanshops",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                new KeyBinding.Category(Identifier.of("sunnymod", "general"))
        ));

        // F7 opens the detailed scan report screen (ScanScreen).
        reportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sunnymod.scanreport",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                new KeyBinding.Category(Identifier.of("sunnymod", "general"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Config.get().signScanEnabled || client.world == null || client.player == null) return;
            while (scanKey.wasPressed()) {
                scan(client, Config.get().signScanRadius, true);
            }
            while (reportKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new ScanScreen());
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("shopscan")
                        .executes(ctx -> {
                            int count = scan(MinecraftClient.getInstance(), Config.get().signScanRadius, true);
                            return count;
                        })
                        .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument(
                                        "radius", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> {
                                    int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                    int count = scan(MinecraftClient.getInstance(), radius, true);
                                    return count;
                                }))));
    }

    /**
     * Kept as a compatibility hook for ShopLogger. Scanning is intentionally
     * manual now, so changing warp does not silently scan the world.
     */
    public static void onWarpChanged() {
        // Manual scanner: press the configured Scan Shops key (default F6).
    }

    public static int lastCount() {
        return lastCount;
    }

    /** Silent scan used by other code paths (e.g. the F8 Scan tab's Rescan button). */
    public static int scanNow(MinecraftClient mc) {
        return scan(mc, Config.get().signScanRadius, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scanning
    // ─────────────────────────────────────────────────────────────────────────

    private static int scan(MinecraftClient mc, int radius, boolean feedback) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return 0;

        int chunkRadius = (radius >> 4) + 1;
        ChunkPos center = mc.player.getChunkPos();
        BlockPos origin = mc.player.getBlockPos();
        Map<String, ShopRow> found = new LinkedHashMap<>();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof SignBlockEntity sign)) continue;
                    if (!sign.getPos().isWithinDistance(origin, radius)) continue;
                    ShopRow row = parse(sign);
                    if (row != null) found.put(row.location, row);
                }
            }
        }

        ScanReport report = merge(found);
        lastReport = report;
        lastScanMs = System.currentTimeMillis();
        lastCount = found.size();

        if (feedback && mc.player != null) {
            sendScanFeedback(mc, report);
        }
        return found.size();
    }

    private static void sendScanFeedback(MinecraftClient mc, ScanReport rep) {
        String warpDisplay = rep.warp().isBlank() ? "(no warp)" : rep.warp();
        mc.player.sendMessage(Text.literal(
                "§a[Shop] Scan: §f" + rep.found() + " §7signs · §f" + rep.updated()
                        + " §7updated §8(§f" + rep.newCount() + " §7new · §f" + rep.changedCount()
                        + " §7changed · §f" + rep.unchangedCount() + " §7unchanged§8)"), false);
        mc.player.sendMessage(Text.literal(
                "§7[Shop] §f" + rep.missingCount() + " §7CSV shops at this warp not seen · §f"
                        + rep.csvRowsForWarp() + " §7shops in CSV for §f" + warpDisplay), false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sign parsing
    // ─────────────────────────────────────────────────────────────────────────

    private static ShopRow parse(SignBlockEntity sign) {
        String[] lines = lines(sign.getFrontText());
        Matcher m = ACTION.matcher(lines[1].trim());
        if (!m.matches()) return null;

        String actionToken = m.group(1).toUpperCase(Locale.ROOT);
        String action = actionToken.equals("OUT OF STOCK") || actionToken.equals("OUT OF SPACE")
                ? "UNKNOWN" : actionToken;
        int stock = parseInt(m.group(2), 0);

        String status = "Active";
        if (actionToken.equals("OUT OF STOCK")) {
            status = "out of stock";
            stock = 0;
        } else if (actionToken.equals("OUT OF SPACE")) {
            status = "out of space";
            stock = 0;
        }

        String item = lines[2].trim();
        if (item.isEmpty()) return null;
        // The live server format is: owner | action+stock | item | price+unit.
        // The item and owner are intentionally taken from the plain rendered text,
        // so colour/font components do not affect matching.

        Matcher pm = PRICE.matcher(lines[3]);
        if (!pm.find()) return null;
        double price;
        try {
            price = Double.parseDouble(pm.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }

        String owner = lines[0].trim();
        BlockPos p = sign.getPos();
        String location = p.getX() + " " + p.getY() + " " + p.getZ();
        return new ShopRow(location, owner, item, stock, price, action, status);
    }

    private static String[] lines(SignText text) {
        Text[] messages = text.getMessages(false);
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (i < messages.length && messages[i] != null) ? messages[i].getString() : "";
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merging into the CSV
    // ─────────────────────────────────────────────────────────────────────────

    private static ScanReport merge(Map<String, ShopRow> found) {
        List<String> lines = new ArrayList<>();
        Map<String, String[]> existing = new LinkedHashMap<>();

        if (Files.exists(CSV_FILE)) {
            try (BufferedReader r = Files.newBufferedReader(CSV_FILE)) {
                r.readLine();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] p = parseCsv(line);
                    if (p.length >= 7) existing.put(p[0].trim(), p);
                }
            } catch (IOException e) {
                System.err.println("[SunnyMod] SignScanner read failed: " + e.getMessage());
                return emptyReport(found.size(), ShopLogger.getLastWarp());
            }
        }

        String warp = ShopLogger.getLastWarp();
        int newCount = 0;
        int changedCount = 0;
        int unchangedCount = 0;
        List<ShopChange> changes = new ArrayList<>();

        for (ShopRow row : found.values()) {
            String[] old = existing.get(row.location);
            if (old != null && "chat".equalsIgnoreCase(field(old, 10))) {
                // Chat is authoritative for a freshly inspected shop. Do not immediately
                // overwrite its exact stock with the sign's potentially stale number.
                long age = parseTimestamp(field(old, 7));
                long trust = Math.max(0, Config.get().chatStockTrustMinutes) * 60_000L;
                if (age > 0 && System.currentTimeMillis() - age < trust) continue;
            }

            String action = row.action;
            if ("UNKNOWN".equals(action) && old != null
                    && ("SELLING".equalsIgnoreCase(field(old, 5)) || "BUYING".equalsIgnoreCase(field(old, 5)))) {
                action = field(old, 5);
            }

            // Do not rewrite an unchanged sign every scan. The timestamp is the
            // observation time for the last actual change, not a heartbeat. This
            // prevents constant disk writes and preserves useful freshness data.
            boolean unchanged = old != null
                    && field(old, 1).equals(row.owner)
                    && field(old, 2).equals(row.item)
                    && parseInt(field(old, 3), -1) == row.stock
                    && Math.abs(parseDouble(field(old, 4), -1) - row.price) < 0.0001
                    && field(old, 5).equalsIgnoreCase(action)
                    && field(old, 6).equalsIgnoreCase(row.status);
            if (unchanged) {
                // Use the already-fetched `old` reference rather than re-fetching
                // from the map. Re-fetching could return null if the location key
                // in the CSV does not round-trip identically to row.location,
                // which previously caused a NullPointerException in field().
                String[] cur = old;

                // Legacy shop_data.csv files had no Source column. Treat those
                // records as chat-origin data and normalize the row once so the
                // freshness guard works on the next scan too.
                if (cur.length < 11) {
                    String[] normalized = new String[11];
                    System.arraycopy(cur, 0, normalized, 0, cur.length);
                    normalized[10] = "chat";
                    cur = normalized;
                    existing.put(row.location, cur);
                }

                // FIX: even when the sign itself is unchanged, keep the Warp column
                // in sync with the warp the player was at when the sign was last seen.
                if (!warp.isEmpty() && !field(cur, 8).equalsIgnoreCase(warp)) {
                    // Make sure the row has the full 11 columns before writing index 8.
                    if (cur.length < 11) {
                        String[] normalized = new String[11];
                        System.arraycopy(cur, 0, normalized, 0, cur.length);
                        normalized[10] = "sign";
                        cur = normalized;
                    }
                    cur[8] = warp;
                    existing.put(row.location, cur);
                }
                unchangedCount++;
                continue;
            }

            int streak = 0;
            String oldSource = old == null ? "" : field(old, 10);
            if (old != null && sameVisibleShop(old, row)) {
                streak = parseInt(field(old, 9), 0) + 1;
            } else if (old != null && "sign".equalsIgnoreCase(oldSource)) {
                streak = 0;
            }
            // FIX: never overwrite a previously known warp with an empty one
            // (currentWarp is empty until the player sends /warp this session).
            String effectiveWarp = warp;
            if (effectiveWarp.isEmpty() && old != null) effectiveWarp = field(old, 8);

            // Capture old → new details for the report page.
            if (changes.size() < MAX_CHANGE_DETAILS) {
                changes.add(new ShopChange(
                        row.location, effectiveWarp, old == null,
                        field(old, 1), row.owner,
                        field(old, 2), row.item,
                        old == null ? 0 : parseInt(field(old, 3), 0), row.stock,
                        old == null ? 0.0 : parseDouble(field(old, 4), 0.0), row.price,
                        field(old, 5), action,
                        field(old, 6), row.status));
            }

            if (old == null) newCount++;
            else changedCount++;

            String[] replacement = {
                    row.location, row.owner, row.item, String.valueOf(row.stock),
                    number(row.price), action, row.status,
                    LocalDateTime.now().format(TS), effectiveWarp, String.valueOf(streak), "sign"
            };
            existing.put(row.location, replacement);
        }

        // Shops known at this warp that the scan did not see.
        int csvRowsForWarp = 0;
        int missingCount = 0;
        for (String[] p : existing.values()) {
            if (!warpEquals(field(p, 8), warp)) continue;
            csvRowsForWarp++;
            if (!found.containsKey(p[0].trim())) missingCount++;
        }

        for (String[] p : existing.values()) lines.add(csvLine(p));
        lines.sort(Comparator.comparing(s -> s.split(",", 2)[0]));

        try {
            Files.createDirectories(CSV_FILE.getParent());
            Path tmp = CSV_FILE.resolveSibling("shop_data.csv.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp)) {
                w.write(HEADER);
                w.newLine();
                for (String line : lines) {
                    w.write(line);
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, CSV_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, CSV_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[SunnyMod] SignScanner write failed: " + e.getMessage());
        }

        return new ScanReport(
                LocalDateTime.now().format(TS_SHORT),
                warp,
                found.size(),
                newCount + changedCount,
                newCount,
                changedCount,
                unchangedCount,
                missingCount,
                csvRowsForWarp,
                changes);
    }

    private static ScanReport emptyReport(int found, String warp) {
        return new ScanReport(LocalDateTime.now().format(TS_SHORT), warp, found, 0, 0, 0, 0, 0, 0, List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** "/warp koopa", "/home x" and "koopa" all normalize to "koopa" (or ""). */
    private static String bareWarp(String w) {
        String b = w == null ? "" : w.trim();
        if (b.startsWith("/warp ")) b = b.substring(6).trim();
        else if (b.startsWith("/home ")) b = b.substring(6).trim();
        return b;
    }

    private static boolean warpEquals(String a, String b) {
        String x = bareWarp(a);
        return !x.isEmpty() && x.equalsIgnoreCase(bareWarp(b));
    }

    private static boolean sameVisibleShop(String[] old, ShopRow r) {
        return field(old,1).equals(r.owner)
                && field(old,2).equals(r.item)
                && Math.abs(parseDouble(field(old,4), -1) - r.price) < 0.0001
                && field(old,5).equalsIgnoreCase(r.action)
                && field(old,6).equalsIgnoreCase(r.status)
                && parseInt(field(old,3), -1) == r.stock;
    }

    private static boolean sameRow(String[] a, String[] b) {
        if (a == null || b == null) return false;
        for (int i = 0; i < 11; i++) if (!field(a,i).equals(field(b,i))) return false;
        return true;
    }

    /** Null-safe field access. Returns "" if the array is null or the index is out of range. */
    private static String field(String[] a, int i) {
        if (a == null) return "";
        return i < a.length ? a[i].trim() : "";
    }

    private static String number(double d) {
        return d == (long)d ? Long.toString((long)d) : Double.toString(d);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.replace(",", "").trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.replace(",", "").replace("$", "").trim()); } catch (Exception e) { return def; }
    }

    private static long parseTimestamp(String s) {
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String csvLine(String[] fields) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) b.append(',');
            String v = fields[i] == null ? "" : fields[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                b.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else b.append(v);
        }
        return b.toString();
    }

    private static String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean quotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    b.append('"'); i++;
                } else if (c == '"') {
                    quotes = false;
                } else b.append(c);
            } else if (c == '"') quotes = true;
            else if (c == ',') { out.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        out.add(b.toString());
        return out.toArray(new String[0]);
    }

    private record ShopRow(String location, String owner, String item, int stock,
                           double price, String action, String status) {}
}