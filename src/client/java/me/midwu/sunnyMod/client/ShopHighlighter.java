package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.signfinder.util.ColorUtils;
import net.signfinder.util.RenderUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShopHighlighter implements ClientModInitializer {

    private static final Path CSV_FILE = ShopLogger.getConfigDir().resolve("shop_data.csv");

    // Debug: Log the CSV file path on initialization
    public ShopHighlighter() {
        System.out.println("[ShopHighlighter] Constructor called. CSV file path: " + CSV_FILE);
    }

    /** location string ("x y z") -> timestamp, used to detect when a shop entry changes on disk. */
    private static final Map<String, String> snapshotTimestamps = new LinkedHashMap<>();
    /** location string ("x y z") -> parsed block position, the boxes currently being drawn. */
    private static final Map<String, BlockPos> pendingBoxes = new LinkedHashMap<>();

    private static volatile boolean active = false;
    private static int tickCounter = 0;
    private static final int RECHECK_INTERVAL_TICKS = 20; // re-read the CSV once a second while active

    // Highlight color: bright green, fully opaque outline.
    private static final int LINE_COLOR = ColorUtils.createArgb(255, 76, 255, 102);
    private static final boolean DEPTH_TEST = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[ShopHighlighter] Initializing ShopHighlighter..."); // Debug log
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        WorldRenderEvents.AFTER_ENTITIES.register(ShopHighlighter::onRender);
        System.out.println("[ShopHighlighter] Registered tick and render events."); // Debug log
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public static void activateForCurrentShopData(String currentWarp) {
        System.out.println("[ShopHighlighter] activateForCurrentShopData called with warp: " + currentWarp); // Debug log
        snapshotTimestamps.clear();
        pendingBoxes.clear();

        if (currentWarp == null || currentWarp.isBlank()) {
            System.out.println("[ShopHighlighter] Warp is null or blank, deactivating.");
            active = false;
            return;
        }

        List<String[]> rows = readAllRows();
        System.out.println("[ShopHighlighter] CSV has " + rows.size() + " rows, matching against warp '" + currentWarp + "'");

        for (String[] row : rows) {
            if (row.length == 0) continue;

            String location = row[0].trim();
            if (location.isBlank()) continue;

            String warpColumn = row.length > 8 ? row[8].trim() : "";
            if (!warpMatches(warpColumn, currentWarp)) continue;

            BlockPos pos = parseLocation(location);
            if (pos == null) {
                System.err.println("[ShopHighlighter] Failed to parse location: " + location);
                continue;
            }

            String timestamp = row.length > 7 ? row[7].trim() : "";
            snapshotTimestamps.put(location, timestamp);
            pendingBoxes.put(location, pos);
            System.out.println("[ShopHighlighter] Added box at: " + pos); // Debug log
        }

        active = !pendingBoxes.isEmpty();
        tickCounter = 0;

        System.out.println("[ShopHighlighter] Matched " + pendingBoxes.size() + " shop(s) for warp '" + currentWarp + "'. active=" + active);
    }

    public static void deactivate() {
        System.out.println("[ShopHighlighter] Deactivating..."); // Debug log
        active = false;
        snapshotTimestamps.clear();
        pendingBoxes.clear();
        tickCounter = 0;
    }

    // ---------------------------------------------------------------------
    // Warp matching
    // ---------------------------------------------------------------------

    private static boolean warpMatches(String warpColumn, String currentWarp) {
        System.out.println("[ShopHighlighter] Comparing CSV warp: '" + warpColumn + "' with input: '" + currentWarp + "'"); // Debug log
        if (warpColumn.isBlank() || warpColumn.equals("?")) {
            System.out.println("[ShopHighlighter] Warp column is blank or '?', skipping.");
            return false;
        }

        // Handle both "/warp koopa" and "koopa" in CSV
        String bareWarp = warpColumn.trim();
        if (bareWarp.startsWith("/warp ")) {
            bareWarp = bareWarp.substring(6).trim();
        } else if (bareWarp.startsWith("/home ")) {
            bareWarp = bareWarp.substring(6).trim();
        }

        // Compare with currentWarp (case-insensitive)
        boolean matches = bareWarp.equalsIgnoreCase(currentWarp.trim());
        System.out.println("[ShopHighlighter] Stripped CSV warp: '" + bareWarp + "', matches: " + matches); // Debug log
        return matches;
    }

    // ---------------------------------------------------------------------
    // Tick: prune boxes whose shop entry changed since we snapshotted it
    // ---------------------------------------------------------------------

    private static void onTick() {
        if (!active) return;
        if (++tickCounter < RECHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        System.out.println("[ShopHighlighter] Rechecking CSV for changes..."); // Debug log
        for (String[] row : readAllRows()) {
            if (row.length == 0) continue;

            String location = row[0].trim();
            if (!pendingBoxes.containsKey(location)) continue;

            String currentTimestamp = row.length > 7 ? row[7].trim() : "";
            String snapshotTimestamp = snapshotTimestamps.get(location);

            if (snapshotTimestamp != null && !snapshotTimestamp.equals(currentTimestamp)) {
                System.out.println("[ShopHighlighter] Removing box at " + location + " (timestamp changed)");
                pendingBoxes.remove(location);
                snapshotTimestamps.remove(location);
            }
        }

        if (pendingBoxes.isEmpty()) {
            System.out.println("[ShopHighlighter] No more boxes, deactivating.");
            active = false;
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private static boolean loggedThisActivation = false;

    private static void onRender(WorldRenderContext context) {
        if (!active || pendingBoxes.isEmpty()) {
            loggedThisActivation = false;
            return;
        }

        MatrixStack matrices = context.matrices();
        if (matrices == null) return;

        if (!loggedThisActivation) {
            System.out.println("[ShopHighlighter] Rendering " + pendingBoxes.size() + " box(es) now.");
            loggedThisActivation = true;
        }

        List<Box> boxes = new ArrayList<>(pendingBoxes.size());
        for (BlockPos pos : pendingBoxes.values()) {
            boxes.add(new Box(
                    pos.getX() - 0.5, pos.getY() - 0.5, pos.getZ() - 0.5,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5
            ));
        }

        RenderUtils.drawOutlinedBoxes(matrices, boxes, LINE_COLOR, DEPTH_TEST);
    }

    // ---------------------------------------------------------------------
    // CSV Parsing
    // ---------------------------------------------------------------------

    private static List<String[]> readAllRows() {
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(CSV_FILE)) {
            System.err.println("[ShopHighlighter] CSV file does not exist at: " + CSV_FILE); // Debug log
            return rows;
        }

        System.out.println("[ShopHighlighter] Reading CSV file at: " + CSV_FILE); // Debug log
        try (BufferedReader reader = Files.newBufferedReader(CSV_FILE)) {
            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                if (line.isBlank()) continue;
                rows.add(parseCsvLine(line));
            }
        } catch (IOException e) {
            System.err.println("[ShopHighlighter] Failed to read shop_data.csv: " + e.getMessage());
        }
        return rows;
    }

    /** Parses a CSV line, handling quoted fields and escaped quotes ("" -> "). */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /** Parses a "x y z" location string (column 0) into a BlockPos. */
    private static BlockPos parseLocation(String location) {
        String[] parts = location.trim().split("\\s+");
        if (parts.length < 3) return null;
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}