package me.midwu.sunnyMod.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logic behind the F8 "Update" tab.
 *
 * <p>Every warp that still has at least one live (non-Dead) shop in shop_data.csv is listed,
 * ordered by the age of its OLDEST live shop — the warp whose stalest shop is oldest comes
 * first. Dead shops never count. Warp names are grouped case-insensitively
 * ("/warp BuyingSpawners" and "/warp buyingspawners" are the same warp).
 *
 * <p>Workflow: warp there, scan the signs (the scanner stamps every sign it sees), then use
 * {@link #markUnseenDead} to flag every shop of that warp that was NOT touched since the
 * visit began. Those shops stop counting, so the warp's oldest live shop becomes "moments
 * ago" and the warp drops to the bottom of the list.
 */
public final class UpdateQueue {

    private static final String HEADER =
            "Shop Location,Shop Owner,Item,Stock/Space,Price,Action,Status,Timestamp,Warp,NoChangeStreak,Source";
    private static final int COL_STATUS = 6;
    private static final int COL_TIMESTAMP = 7;
    private static final int COL_WARP = 8;

    private UpdateQueue() {}

    /** One row of the Update tab: a warp and the age of its oldest live shop. */
    public static final class WarpEntry {
        /** Warp command to send, e.g. "/warp koopa" (spelling of the most recently scanned shop). */
        public final String command;
        /** Warp name without the "/warp " prefix. */
        public final String bare;
        public final int liveShops;
        /** Timestamp (epoch ms) of the oldest live shop; 0 if a live shop has no parsable timestamp. */
        public final long oldestMs;

        WarpEntry(String command, String bare, int liveShops, long oldestMs) {
            this.command = command;
            this.bare = bare;
            this.liveShops = liveShops;
            this.oldestMs = oldestMs;
        }
    }

    // ── Public API (default shop_data.csv location) ──────────────────────────

    /** Warps with live shops, oldest-shop-first. */
    public static List<WarpEntry> loadQueue() {
        return loadQueue(csvPath(), ProfitFinder.loadIgnore(ProfitFinder.IgnoreKind.WARPS));
    }

    /** Live shops of {@code warp} whose timestamp is older than {@code cutoffMs}. */
    public static int countUnseen(String warp, long cutoffMs) {
        return countUnseen(csvPath(), warp, cutoffMs);
    }

    /**
     * Marks every live shop of {@code warp} with a timestamp older than {@code cutoffMs} as
     * "Dead". Writes a shop_data.csv.bak backup first. Returns the number of shops marked,
     * or -1 if the file could not be read or written.
     */
    public static int markUnseenDead(String warp, long cutoffMs) {
        return markUnseenDead(csvPath(), warp, cutoffMs);
    }

    // ── Implementation (package-private overloads take an explicit file) ─────

    private static Path csvPath() {
        return ShopLogger.getConfigDir().resolve("shop_data.csv");
    }

    static List<WarpEntry> loadQueue(Path file, Set<String> ignoredWarps) {
        List<WarpEntry> out = new ArrayList<>();
        if (!Files.exists(file)) return out;

        // key = lower-case bare warp
        Map<String, Acc> byWarp = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsv(line);
                if (f.length <= COL_WARP) continue;
                if (isDead(f)) continue;
                String bare = bareWarp(f[COL_WARP]);
                if (bare.isEmpty() || isIgnored(ignoredWarps, bare)) continue;

                long ts = parseTimestamp(f[COL_TIMESTAMP]);
                Acc a = byWarp.computeIfAbsent(bare.toLowerCase(Locale.ROOT), k -> new Acc());
                a.count++;
                if (a.oldest < 0 || ts < a.oldest) a.oldest = ts;
                if (ts >= a.newest) { // newest-scanned shop decides the spelling we send
                    a.newest = ts;
                    a.command = f[COL_WARP].trim();
                    a.bare = bare;
                }
            }
        } catch (IOException e) {
            System.err.println("[UpdateQueue] read failed: " + e.getMessage());
            return out;
        }

        for (Acc a : byWarp.values()) {
            String command = a.command.startsWith("/") ? a.command : "/warp " + a.bare;
            out.add(new WarpEntry(command, a.bare, a.count, Math.max(0, a.oldest)));
        }
        // Oldest shop first; ties broken alphabetically so the order is stable.
        out.sort(Comparator.<WarpEntry>comparingLong(w -> w.oldestMs)
                .thenComparing(w -> w.bare.toLowerCase(Locale.ROOT)));
        return out;
    }

    static int countUnseen(Path file, String warp, long cutoffMs) {
        String target = bareWarp(warp);
        if (target.isEmpty() || cutoffMs <= 0 || !Files.exists(file)) return 0;
        int n = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = parseCsv(line);
                if (isUnseen(f, target, cutoffMs)) n++;
            }
        } catch (IOException e) {
            System.err.println("[UpdateQueue] count failed: " + e.getMessage());
            return 0;
        }
        return n;
    }

    static int markUnseenDead(Path file, String warp, long cutoffMs) {
        String target = bareWarp(warp);
        if (target.isEmpty() || cutoffMs <= 0 || !Files.exists(file)) return 0;

        List<String> lines = new ArrayList<>();
        int marked = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            lines.add(header == null ? HEADER : header);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) { lines.add(line); continue; }
                String[] f = parseCsv(line);
                if (isUnseen(f, target, cutoffMs)) {
                    f[COL_STATUS] = "Dead"; // timestamp deliberately kept = last time it was seen alive
                    lines.add(toCsv(f));
                    marked++;
                } else {
                    lines.add(line); // untouched rows are written back byte-for-byte
                }
            }
        } catch (IOException e) {
            System.err.println("[UpdateQueue] mark-dead read failed: " + e.getMessage());
            return -1;
        }
        if (marked == 0) return 0;

        try {
            Files.copy(file, file.resolveSibling("shop_data.csv.bak"), StandardCopyOption.REPLACE_EXISTING);
            Path tmp = file.resolveSibling("shop_data.csv.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    w.write(l);
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[UpdateQueue] mark-dead write failed: " + e.getMessage());
            return -1;
        }
        return marked;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final class Acc {
        int count;
        long oldest = -1;
        long newest = Long.MIN_VALUE;
        String command = "";
        String bare = "";
    }

    /** A live shop at {@code targetBare} whose timestamp is older than the cutoff. */
    private static boolean isUnseen(String[] f, String targetBare, long cutoffMs) {
        if (f.length <= COL_WARP || isDead(f)) return false;
        if (!bareWarp(f[COL_WARP]).equalsIgnoreCase(targetBare)) return false;
        return parseTimestamp(f[COL_TIMESTAMP]) < cutoffMs;
    }

    private static boolean isDead(String[] f) {
        return f.length > COL_STATUS && "dead".equalsIgnoreCase(f[COL_STATUS].trim());
    }

    private static boolean isIgnored(Set<String> ignored, String bare) {
        if (ignored == null) return false;
        for (String s : ignored) {
            if (bareWarp(s).equalsIgnoreCase(bare)) return true;
        }
        return false;
    }

    /** "/warp koopa", "/home x" and "koopa" all become "koopa" (or ""). */
    static String bareWarp(String w) {
        String b = w == null ? "" : w.trim();
        if (b.startsWith("/warp ")) b = b.substring(6).trim();
        else if (b.startsWith("/home ")) b = b.substring(6).trim();
        return b;
    }

    /** Timestamps are written by the mod in local time ("yyyy-MM-dd HH:mm:ss"). 0 = unparsable. */
    static long parseTimestamp(String s) {
        try {
            return LocalDateTime.parse(s.trim().replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Quote-aware CSV split that keeps empty trailing fields and unescapes "" pairs. */
    static String[] parseCsv(String line) {
        List<String> out = new ArrayList<>();
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
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String toCsv(String[] fields) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) b.append(',');
            String v = fields[i] == null ? "" : fields[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                b.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                b.append(v);
            }
        }
        return b.toString();
    }
}
