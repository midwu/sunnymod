package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/**
 * Compact HUD panel for F7 container-worth results.
 *
 * Kept deliberately small: header + total value + one row per item showing
 * only "name  →  warp". Full price breakdown and clickable warps live in
 * chat (see Container_reader.evaluateContainerWorth).
 */
public class ContainerWorthHud {

    public static class Entry {
        public final String name;
        /** Amount allocated to this sell leg (not always the full chest amount). */
        public final int count;
        /** Total of this item in the container (across all legs). */
        public final int containerTotal;
        public final Double unitPrice; // null = no known price / unsellable remainder
        public final double subtotal;
        public final String owner;
        public final String warp;
        public final String location;
        public final String stockSpace;
        /** Parsed shop buy-space for this leg; -1 if unknown. */
        public final int shopSpace;

        public Entry(String name, int allocated, int containerTotal,
                     Container_reader.BestBuyOffer offer, int shopSpace) {
            this.name = name;
            this.count = allocated;
            this.containerTotal = containerTotal;
            this.shopSpace = shopSpace;
            if (offer != null) {
                this.unitPrice = offer.price;
                this.subtotal = offer.price * allocated;
                this.owner = offer.owner != null ? offer.owner : "";
                this.warp = offer.warp != null ? offer.warp : "";
                this.location = offer.location != null ? offer.location : "";
                this.stockSpace = offer.stockSpace != null ? offer.stockSpace : "";
            } else {
                this.unitPrice = null;
                this.subtotal = 0.0;
                this.owner = "";
                this.warp = "";
                this.location = "";
                this.stockSpace = "";
            }
        }

        /** Full amount has no buyer at all. */
        public static Entry unsellable(String name, int count) {
            return new Entry(name, count, count, null, -1);
        }

        /** Leftover after all shops with space were filled. */
        public static Entry unsellable(String name, int leftover, int containerTotal) {
            return new Entry(name, leftover, containerTotal, null, -1);
        }
    }

    /** Last results — used when F7 is pressed outside a container. */
    public static List<Entry> getEntries() { return entries; }
    public static double getTotal() { return total; }
    public static int getPricedStacks() { return pricedStacks; }
    public static int getUnpricedStacks() { return unpricedStacks; }
    public static List<String> getCraftSteps() { return craftSteps; }
    public static boolean hasResults() { return lastUpdateTime > 0L; }

    private static final int MAX_VISIBLE_ROWS = 14;

    private static volatile double total = 0.0;
    private static volatile int pricedStacks = 0;
    private static volatile int unpricedStacks = 0;
    private static volatile List<Entry> entries = List.of();
    private static volatile List<String> craftSteps = List.of();
    private static volatile long lastUpdateTime = 0L;

    public static void update(double total, int pricedStacks, int unpricedStacks, List<Entry> entries) {
        update(total, pricedStacks, unpricedStacks, entries, List.of());
    }

    public static void update(double total, int pricedStacks, int unpricedStacks,
                              List<Entry> entries, List<String> craftSteps) {
        ContainerWorthHud.total = total;
        ContainerWorthHud.pricedStacks = pricedStacks;
        ContainerWorthHud.unpricedStacks = unpricedStacks;
        ContainerWorthHud.entries = entries;
        ContainerWorthHud.craftSteps = craftSteps != null ? List.copyOf(craftSteps) : List.of();
        ContainerWorthHud.lastUpdateTime = System.currentTimeMillis();
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public static int getPanelHeight() {
        List<Entry> snapshot = entries;
        int rows = Math.max(Math.min(snapshot.size(), MAX_VISIBLE_ROWS), 1);
        boolean hasMore = snapshot.size() > MAX_VISIBLE_ROWS;
        // header + total + rows + optional "+N more"
        int lineCount = 1 + 1 + rows + (hasMore ? 1 : 0);
        return Hud.PADDING + (lineCount * Hud.LINE_HEIGHT) + Hud.PADDING;
    }

    public static void render(DrawContext ctx, MinecraftClient client, int x, int y) {
        List<Entry> snapshot = entries;
        int h = getPanelHeight();
        int textX = x + Hud.PADDING;
        int cursor = y + Hud.PADDING;

        ctx.fill(x, y, x + Hud.PANEL_WIDTH, y + h, 0xAA000000);
        ctx.drawText(client.textRenderer, "Chest Worth", textX, cursor, 0xFFFFD700, true);
        cursor += Hud.LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + Hud.PANEL_WIDTH - 2, cursor + 1, 0xFF444444);
        cursor += 4;

        // Always show total value of the chest
        String totalLine = "Total: $" + String.format(Locale.US, "%,.2f", total);
        ctx.drawText(client.textRenderer, totalLine, textX, cursor, 0xFFFFFFFF, true);
        cursor += Hud.LINE_HEIGHT;

        if (snapshot.isEmpty()) {
            ctx.drawText(client.textRenderer, "Press F7 in a chest", textX, cursor, 0xFFAAAAAA, true);
            return;
        }

        int shown = Math.min(snapshot.size(), MAX_VISIBLE_ROWS);
        for (int i = 0; i < shown; i++) {
            Entry e = snapshot.get(i);
            // Simple row: "Tuff x3456  /warp sale"  (or "no warp" / "no price")
            String left = (e.count < e.containerTotal)
                    ? e.name + " x" + e.count + "/" + e.containerTotal
                    : e.name + " x" + e.count;
            String right = shortWarp(e.warp);
            if (right.isEmpty()) {
                right = (e.unitPrice != null) ? "@" + shortOwner(e.owner) : "—";
            }

            String line = truncate(client, left, right);
            int color = (e.unitPrice != null) ? 0xFFFFFFFF : 0xFF888888;
            ctx.drawText(client.textRenderer, line + "  " + right, textX, cursor, color, true);
            cursor += Hud.LINE_HEIGHT;
        }

        if (snapshot.size() > MAX_VISIBLE_ROWS) {
            int hidden = snapshot.size() - MAX_VISIBLE_ROWS;
            ctx.drawText(client.textRenderer, "+" + hidden + " more (see chat)", textX, cursor, 0xFFAAAAAA, true);
        }
    }

    private static String shortWarp(String warp) {
        if (warp == null || warp.isBlank()) return "";
        String w = warp.trim();
        if (w.startsWith("/warp ")) w = w.substring(6);
        else if (w.startsWith("warp ")) w = w.substring(5);
        else if (w.startsWith("/")) w = w.substring(1);
        if (w.length() > 14) w = w.substring(0, 13) + "…";
        return "/" + w;
    }

    private static String shortOwner(String owner) {
        if (owner == null || owner.isEmpty()) return "?";
        if ("__server__".equals(owner)) return "server";
        return owner.length() > 12 ? owner.substring(0, 11) + "…" : owner;
    }

    private static String truncate(MinecraftClient client, String text, String right) {
        int max = Hud.PANEL_WIDTH - Hud.PADDING * 2 - client.textRenderer.getWidth("  " + right);
        if (client.textRenderer.getWidth(text) <= max) return text;
        while (!text.isEmpty() && client.textRenderer.getWidth(text + "…") > max)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}