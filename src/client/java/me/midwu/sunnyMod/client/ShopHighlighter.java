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

/**
 * Reads shop_data.csv, filters it down to the entries whose "Warp" column
 * matches the warp the player is currently heading to, and draws a
 * see-through (ESP-style) box around each matching shop's coordinates -
 * using SignFinder's own rendering utilities, the same ones it uses for
 * `/findsign`, so we get its actual no-depth-test pipeline instead of
 * re-deriving the new GPU render pipeline API by hand.
 * <p>
 * Entry point from outside this class: {@link #activateForCurrentShopData(String)},
 * called by {@code ProfitScreen.runWarpCommandAndHighlight(String)} when a
 * warp button on the Update tab is clicked.
 * <p>
 * Requires the SignFinder mod to be installed (see fabric.mod.json depends).
 */
public class ShopHighlighter implements ClientModInitializer {

    private static final Path CSV_FILE = ShopLogger.getConfigDir().resolve("shop_data.csv");

    /** location string ("x y z") -> timestamp, used to detect when a shop entry changes on disk. */
    private static final Map<String, String> snapshotTimestamps = new LinkedHashMap<>();
    /** location string ("x y z") -> parsed block position, the boxes currently being drawn. */
    private static final Map<String, BlockPos> pendingBoxes = new LinkedHashMap<>();

    private static volatile boolean active = false;
    private static int tickCounter = 0;
    private static final int RECHECK_INTERVAL_TICKS = 20; // re-read the CSV once a second while active

    // Highlight color: bright green, fully opaque outline.
    // ColorUtils.createArgb(alpha, red, green, blue) -> packed 0xAARRGGBB int,
    // exactly what RenderUtils expects (it's SignFinder's own color format).
    private static final int LINE_COLOR = ColorUtils.createArgb(255, 76, 255, 102);

    /** false = SignFinder's ESP_LINES pipeline (NO_DEPTH_TEST) -> renders through walls. */
    private static final boolean DEPTH_TEST = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        WorldRenderEvents.AFTER_ENTITIES.register(ShopHighlighter::onRender);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * (Re)loads shop_data.csv, keeps only the rows whose Warp column matches
     * {@code currentWarp}, and starts drawing boxes around them.
     *
     * @param currentWarp the bare warp name, e.g. "serstore" (no "/warp " or
     *                     "/home " prefix - ProfitScreen already strips that).
     */
    public static void activateForCurrentShopData(String currentWarp) {
        snapshotTimestamps.clear();
        pendingBoxes.clear();

        if (currentWarp == null || currentWarp.isBlank()) {
            active = false;
            return;
        }

        List<String[]> rows = readAllRows();
        System.out.println("[ShopHighlighter] CSV has " + rows.size()
                + " rows, matching against warp '" + currentWarp + "'");

        for (String[] row : rows) {
            if (row.length == 0) continue;

            String location = row[0].trim();
            if (location.isBlank()) continue;

            String warpColumn = row.length > 8 ? row[8].trim() : "";
            if (!warpMatches(warpColumn, currentWarp)) continue;

            BlockPos pos = parseLocation(location);
            if (pos == null) continue;

            String timestamp = row.length > 7 ? row[7].trim() : "";
            snapshotTimestamps.put(location, timestamp);
            pendingBoxes.put(location, pos);
        }

        active = !pendingBoxes.isEmpty();
        tickCounter = 0;

        System.out.println("[ShopHighlighter] Matched " + pendingBoxes.size()
                + " shop(s) for warp '" + currentWarp + "'. active=" + active);
        if (active) {
            System.out.println("[ShopHighlighter] First box at: "
                    + pendingBoxes.values().iterator().next());
        }
    }

    /** Stops drawing and clears all stored boxes. */
    public static void deactivate() {
        active = false;
        snapshotTimestamps.clear();
        pendingBoxes.clear();
        tickCounter = 0;
    }

    // ---------------------------------------------------------------------
    // Warp matching
    // ---------------------------------------------------------------------

    /**
     * The CSV's Warp column stores the raw command, e.g. "/warp serstore" or
     * "/home rismarine" (or "?" if unknown). {@code currentWarp} is just the
     * bare name. Match either form, case-insensitively.
     */
    private static boolean warpMatches(String warpColumn, String currentWarp) {
        if (warpColumn.isBlank() || warpColumn.equals("?")) return false;

        String bare = warpColumn;
        if (bare.regionMatches(true, 0, "/warp ", 0, 6)) {
            bare = bare.substring(6);
        } else if (bare.regionMatches(true, 0, "/home ", 0, 6)) {
            bare = bare.substring(6);
        }

        return bare.trim().equalsIgnoreCase(currentWarp.trim());
    }

    // ---------------------------------------------------------------------
    // Tick: prune boxes whose shop entry changed since we snapshotted it
    // ---------------------------------------------------------------------

    private static void onTick() {
        if (!active) return;
        if (++tickCounter < RECHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        for (String[] row : readAllRows()) {
            if (row.length == 0) continue;

            String location = row[0].trim();
            if (!pendingBoxes.containsKey(location)) continue;

            String currentTimestamp = row.length > 7 ? row[7].trim() : "";
            String snapshotTimestamp = snapshotTimestamps.get(location);

            if (snapshotTimestamp != null && !snapshotTimestamp.equals(currentTimestamp)) {
                pendingBoxes.remove(location);
                snapshotTimestamps.remove(location);
            }
        }

        if (pendingBoxes.isEmpty()) active = false;
    }

    // ---------------------------------------------------------------------
    // Rendering - delegates straight to SignFinder's own utility
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

        // RenderUtils.drawOutlinedBoxes handles: picking the right RenderLayer
        // (ESP_LINES / no depth test when DEPTH_TEST=false, so it renders
        // through walls exactly like /findsign), the camera-relative offset,
        // and the immediate draw call. We don't need to touch RenderLayer,
        // RenderPipeline, or RenderSetup ourselves at all.
        RenderUtils.drawOutlinedBoxes(matrices, boxes, LINE_COLOR, DEPTH_TEST);
    }

    // ---------------------------------------------------------------------
    // CSV Parsing
    // ---------------------------------------------------------------------

    private static List<String[]> readAllRows() {
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(CSV_FILE)) return rows;

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