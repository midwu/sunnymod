package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

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
    private static final Map<String, String> snapshotTimestamps = new LinkedHashMap<>();
    private static final Map<String, BlockPos> pendingBoxes = new LinkedHashMap<>();
    private static volatile boolean active = false;
    private static int tickCounter = 0;
    private static final int RECHECK_INTERVAL_TICKS = 20;

    // Highlight color (green)
    private static final float R = 0.3f;
    private static final float G = 1.0f;
    private static final float B = 0.4f;
    private static final float LINE_ALPHA = 0.9f;
    private static final float LINE_WIDTH = 2.0f; // Thicker lines for visibility

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        WorldRenderEvents.AFTER_ENTITIES.register(ShopHighlighter::onRender);
    }

    /**
     * Activates the highlighter for the current warp.
     * @param currentWarp The warp name (e.g., "serstore" from "/warp serstore").
     */
    public static void activateForCurrentShopData(String currentWarp) {
        snapshotTimestamps.clear();
        pendingBoxes.clear();

        for (String[] row : readAllRows()) {
            if (row.length == 0) continue;

            String location = row[0].trim();
            if (location.isBlank()) continue;

            // Filter by warp (column 8)
            String warp = row.length > 8 ? row[8].trim() : "";
            if (!warp.equals(currentWarp) && !warp.equals("/warp " + currentWarp) && !warp.equals("/home " + currentWarp)) {
                continue; // Skip if warp doesn't match
            }

            BlockPos pos = parseLocation(location);
            if (pos == null) continue;

            String timestamp = row.length > 7 ? row[7].trim() : "";
            snapshotTimestamps.put(location, timestamp);
            pendingBoxes.put(location, pos);
        }

        active = !pendingBoxes.isEmpty();
        tickCounter = 0;
    }

    /**
     * Deactivates the highlighter.
     */
    public static void deactivate() {
        active = false;
        snapshotTimestamps.clear();
        pendingBoxes.clear();
        tickCounter = 0;
    }

    /**
     * Periodically checks for timestamp changes in the CSV.
     */
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

    /**
     * Renders the highlight boxes.
     */
    private static void onRender(WorldRenderContext context) {
        if (!active || pendingBoxes.isEmpty()) return;

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        VertexConsumer lineBuffer = consumers.getBuffer(RenderLayers.LINES);
        matrices.push();

        for (BlockPos pos : pendingBoxes.values()) {
            // Create a box centered at the BlockPos with size 1.0 (from -0.5 to +0.5)
            Box box = new Box(
                    pos.getX() - 0.5, pos.getY() - 0.5, pos.getZ() - 0.5,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5
            );
            drawBox(matrices, lineBuffer, box, R, G, B, LINE_ALPHA);
        }

        matrices.pop();
    }

    /**
     * Draws a box using lines.
     */
    private static void drawBox(
            MatrixStack matrices,
            VertexConsumer buffer,
            Box box,
            float r, float g, float b, float a
    ) {
        MatrixStack.Entry entry = matrices.peek();
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        // Bottom face
        drawLine(entry, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        drawLine(entry, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        drawLine(entry, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        drawLine(entry, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);

        // Top face
        drawLine(entry, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        drawLine(entry, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        drawLine(entry, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        drawLine(entry, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);

        // Vertical edges
        drawLine(entry, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        drawLine(entry, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        drawLine(entry, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        drawLine(entry, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
    }

    /**
     * Draws a line segment with Normal and lineWidth attributes.
     */
    private static void drawLine(
            MatrixStack.Entry entry,
            VertexConsumer buffer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float r, float g, float b, float a
    ) {
        // Default normal for lines (no meaningful normal)
        float nx = 0.0f;
        float ny = 1.0f;
        float nz = 0.0f;

        buffer.vertex(entry.getPositionMatrix(), x1, y1, z1)
                .color(r, g, b, a)
                .normal(entry, nx, ny, nz)
                .lineWidth(LINE_WIDTH);

        buffer.vertex(entry.getPositionMatrix(), x2, y2, z2)
                .color(r, g, b, a)
                .normal(entry, nx, ny, nz)
                .lineWidth(LINE_WIDTH);
    }

    // ---------------------------------------------------------------------
    // CSV Parsing
    // ---------------------------------------------------------------------

    /**
     * Reads all rows from shop_data.csv.
     */
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

    /**
     * Parses a CSV line, handling quoted fields and escaped quotes.
     */
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

    /**
     * Parses a location string (e.g., "10 5 -10") into a BlockPos.
     */
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