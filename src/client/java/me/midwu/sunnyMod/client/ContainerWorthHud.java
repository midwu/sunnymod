package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/**
 * Renders the F7 container-worth breakdown as a HUD panel, same pattern as
 * ShopSignHud — a plain static utility, driven by Container_reader.update()
 * and drawn by Hud.renderHud() alongside the other panels.
 *
 * Shows one row per distinct item name (identical stacks are merged), sorted
 * by value descending. Each priced row includes the best buyer (owner) and
 * warp when known, so you can see where to sell without leaving the chest.
 * Capped at MAX_VISIBLE_ROWS; anything beyond is collapsed into a "+N more"
 * summary line.
 */
public class ContainerWorthHud {

    public static class Entry {
        public final String name;
        public final int count;
        public final Double unitPrice; // null = no known price
        public final double subtotal;  // 0 if unitPrice is null
        public final String owner;     // best buyer, may be empty
        public final String warp;      // e.g. "/warp foo", may be empty

        /** Preferred constructor — takes the full BestBuyOffer (or null). */
        public Entry(String name, int count, Container_reader.BestBuyOffer offer) {
            this.name = name;
            this.count = count;
            if (offer != null) {
                this.unitPrice = offer.price;
                this.subtotal = offer.price * count;
                this.owner = offer.owner != null ? offer.owner : "";
                this.warp = offer.warp != null ? offer.warp : "";
            } else {
                this.unitPrice = null;
                this.subtotal = 0.0;
                this.owner = "";
                this.warp = "";
            }
        }

        /** Back-compat constructor used only if something still passes a bare price. */
        public Entry(String name, int count, Double unitPrice) {
            this.name = name;
            this.count = count;
            this.unitPrice = unitPrice;
            this.subtotal = (unitPrice != null) ? unitPrice * count : 0.0;
            this.owner = "";
            this.warp = "";
        }
    }

    private static final int MAX_VISIBLE_ROWS = 20;

    private static volatile double total = 0.0;
    private static volatile int pricedStacks = 0;
    private static volatile int unpricedStacks = 0;
    private static volatile List<Entry> entries = List.of();
    private static volatile long lastUpdateTime = 0L;

    public static void update(double total, int pricedStacks, int unpricedStacks, List<Entry> entries) {
        ContainerWorthHud.total = total;
        ContainerWorthHud.pricedStacks = pricedStacks;
        ContainerWorthHud.unpricedStacks = unpricedStacks;
        ContainerWorthHud.entries = entries;
        ContainerWorthHud.lastUpdateTime = System.currentTimeMillis();
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public static int getPanelHeight() {
        List<Entry> snapshot = entries;
        int rows = Math.max(Math.min(snapshot.size(), MAX_VISIBLE_ROWS), 1); // at least 1 for "no priced items"
        boolean hasMore = snapshot.size() > MAX_VISIBLE_ROWS;
        int lineCount = 1 /* header */ + 1 /* total */ + rows + (hasMore ? 1 : 0);
        return Hud.PADDING + (lineCount * Hud.LINE_HEIGHT) + Hud.PADDING;
    }

    public static void render(DrawContext ctx, MinecraftClient client, int x, int y) {
        List<Entry> snapshot = entries;
        int h = getPanelHeight();
        int textX = x + Hud.PADDING;
        int cursor = y + Hud.PADDING;

        ctx.fill(x, y, x + Hud.PANEL_WIDTH, y + h, 0xAA000000);
        ctx.drawText(client.textRenderer, "Container Worth", textX, cursor, 0xFFFFD700, true);
        cursor += Hud.LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + Hud.PANEL_WIDTH - 2, cursor + 1, 0xFF444444);
        cursor += 4;

        String totalLine = "Total: $" + String.format(Locale.US, "%,.2f", total) +
                " (" + pricedStacks + "p/" + unpricedStacks + "u)";
        ctx.drawText(client.textRenderer, totalLine, textX, cursor, 0xFFFFFFFF, true);
        cursor += Hud.LINE_HEIGHT;

        if (snapshot.isEmpty()) {
            ctx.drawText(client.textRenderer, "Press F7 in a container", textX, cursor, 0xFFAAAAAA, true);
            return;
        }

        int shown = Math.min(snapshot.size(), MAX_VISIBLE_ROWS);
        for (int i = 0; i < shown; i++) {
            Entry e = snapshot.get(i);
            String right;
            if (e.unitPrice != null) {
                right = "$" + String.format(Locale.US, "%,.2f", e.subtotal);
                // Prefer a short owner tag; fall back to warp if owner is empty
                // (e.g. some edge cases) or is the synthetic server marker.
                String where = formatWhere(e.owner, e.warp);
                if (!where.isEmpty()) {
                    right = right + " " + where;
                }
            } else {
                right = "no price";
            }

            String left = e.name + " x" + e.count;
            String line = truncate(client, left, right);
            int color = (e.unitPrice != null) ? 0xFFFFFFFF : 0xFF888888;
            ctx.drawText(client.textRenderer, line + " - " + right, textX, cursor, color, true);
            cursor += Hud.LINE_HEIGHT;
        }

        if (snapshot.size() > MAX_VISIBLE_ROWS) {
            int hiddenCount = snapshot.size() - MAX_VISIBLE_ROWS;
            double hiddenValue = 0;
            for (int i = MAX_VISIBLE_ROWS; i < snapshot.size(); i++) hiddenValue += snapshot.get(i).subtotal;
            String moreLine = "+" + hiddenCount + " more ($" + String.format(Locale.US, "%,.2f", hiddenValue) + ")";
            ctx.drawText(client.textRenderer, moreLine, textX, cursor, 0xFFAAAAAA, true);
        }
    }

    /**
     * Compact "where to sell" tag.
     * - "__server__" → "@server"
     * - normal player → "@Name"
     * - if warp present and owner is blank → the warp itself
     * - otherwise empty
     */
    private static String formatWhere(String owner, String warp) {
        if (owner != null && !owner.isEmpty()) {
            if ("__server__".equals(owner)) return "@server";
            // Keep it short for the 160px panel
            String shortOwner = owner.length() > 12 ? owner.substring(0, 11) + "…" : owner;
            return "@" + shortOwner;
        }
        if (warp != null && !warp.isEmpty()) {
            // warp is often stored as "/warp name"
            String w = warp.startsWith("/warp ") ? warp.substring(6) : warp;
            if (w.length() > 12) w = w.substring(0, 11) + "…";
            return "/" + w;
        }
        return "";
    }

    private static String truncate(MinecraftClient client, String text, String priceText) {
        int max = Hud.PANEL_WIDTH - Hud.PADDING * 2 - client.textRenderer.getWidth(" - " + priceText);
        if (client.textRenderer.getWidth(text) <= max) return text;
        while (!text.isEmpty() && client.textRenderer.getWidth(text + "…") > max)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}