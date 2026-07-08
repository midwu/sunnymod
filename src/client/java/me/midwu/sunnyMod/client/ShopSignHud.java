package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public class ShopSignHud {

    // Medium size: ~1.5x a real sign
    public static final int SIGN_WIDTH  = 140;
    public static final int SIGN_HEIGHT = 56;

    private static volatile LastShopInfo lastShop = null;
    private static volatile long         lastShopTime = 0L;

    public static void updateLastShop(LastShopInfo info) {
        lastShop     = info;
        lastShopTime = System.currentTimeMillis();  // Use real time for auto-hide
    }

    public static long getLastShopTime() {
        return lastShopTime;
    }

    public static void render(DrawContext ctx, MinecraftClient client, int x, int y) {
        if (lastShop == null) return;

        TextRenderer tr = client.textRenderer;

        // ── Sign background (wood-tone layers) ────────────────────────────────
        // Outer dark border
        ctx.fill(x,     y,     x + SIGN_WIDTH,     y + SIGN_HEIGHT,     0xFF1A1008);
        // Main wood body
        ctx.fill(x + 2, y + 2, x + SIGN_WIDTH - 2, y + SIGN_HEIGHT - 2, 0xFF6B4F2A);
        // Inner lighter panel (the writable face)
        ctx.fill(x + 6, y + 6, x + SIGN_WIDTH - 6, y + SIGN_HEIGHT - 6, 0xFF8B6914);
        // Subtle inner shadow at top
        ctx.fill(x + 6, y + 6, x + SIGN_WIDTH - 6, y + 8,               0x33000000);

        // ── Post at bottom centre ─────────────────────────────────────────────
        int postW = 6;
        int postH = 10;
        int postX = x + SIGN_WIDTH / 2 - postW / 2;
        ctx.fill(postX, y + SIGN_HEIGHT - 2, postX + postW, y + SIGN_HEIGHT + postH, 0xFF6B4F2A);
        ctx.fill(postX + 1, y + SIGN_HEIGHT - 1, postX + postW - 1, y + SIGN_HEIGHT + postH, 0xFF8B6914);

        // ── Text area ─────────────────────────────────────────────────────────
        int textAreaX = x + 10;
        int textAreaW = SIGN_WIDTH - 20;
        int lineY     = y + 10;
        int lineH     = 11;

        // Line 1: Owner — green if active, red if out of stock/space
        int ownerColor = lastShop.isOutOfStock() ? 0xFFFF5555 : 0xFF55FF55;
        String ownerLine = truncate(tr, lastShop.owner, textAreaW);
        ctx.drawText(tr, ownerLine,
                textAreaX + (textAreaW - tr.getWidth(ownerLine)) / 2,
                lineY, ownerColor, true);
        lineY += lineH;

        // Line 2: Stock/Space
        String stockLine = "Stock: " + lastShop.stockSpace;
        ctx.drawText(tr, stockLine,
                textAreaX + (textAreaW - tr.getWidth(stockLine)) / 2,
                lineY, 0xFFCCCCCC, true);
        lineY += lineH;

        // Line 3: Item name (truncated to fit)
        String itemLine = truncate(tr, lastShop.item, textAreaW);
        ctx.drawText(tr, itemLine,
                textAreaX + (textAreaW - tr.getWidth(itemLine)) / 2,
                lineY, 0xFFFFFFFF, true);
        lineY += lineH;

        // Line 4: Price + action
        String priceStr = lastShop.action + " $" + formatPrice(lastShop.price);
        ctx.drawText(tr, priceStr,
                textAreaX + (textAreaW - tr.getWidth(priceStr)) / 2,
                lineY, 0xFFFFAA00, true);
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private static String truncate(TextRenderer tr, String text, int maxPx) {
        if (tr.getWidth(text) <= maxPx) return text;
        while (!text.isEmpty() && tr.getWidth(text + "…") > maxPx)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }

    private static String formatPrice(double price) {
        if (price == (int) price) return String.valueOf((int) price);
        return String.format("%.2f", price);
    }
}