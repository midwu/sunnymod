package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public class ShopSignHud implements ClientModInitializer {

    public static final int SIGN_WIDTH = 160;
    public static final int SIGN_HEIGHT = 72;

    private static ShopLogger.ShopData lastShopData = null;

    public static void updateLastShop(ShopLogger.ShopData shop) {
        lastShopData = shop;
    }

    @Override
    public void onInitializeClient() {
        // Initialized via Hud.java
    }

    public static void render(DrawContext ctx, MinecraftClient client, int x, int y) {
        if (lastShopData == null) return;

        // Sign background
        ctx.fill(x, y, x + SIGN_WIDTH, y + SIGN_HEIGHT, 0xBB2B2B2B);
        ctx.fill(x + 6, y + 6, x + SIGN_WIDTH - 6, y + SIGN_HEIGHT - 6, 0xCC3C2F1E); // wood-like

        int textX = x + 14;
        int lineY = y + 12;
        int lineH = 13;

        // Line 1: Owner (red if out of stock)
        boolean outOfStock = lastShopData.stockSpace <= 0 ||
                "out of stock".equalsIgnoreCase(lastShopData.status) ||
                "out of space".equalsIgnoreCase(lastShopData.status);
        String ownerColor = outOfStock ? "§c" : "§a";
        ctx.drawText(client.textRenderer, ownerColor + lastShopData.shopOwner, textX, lineY, 0xFFFFFFFF, true);
        lineY += lineH;

        // Line 2: Stock / Space
        String stockText = "Stock: " + lastShopData.stockSpace;
        ctx.drawText(client.textRenderer, stockText, textX, lineY, 0xFFCCCCCC, true);
        lineY += lineH;

        // Line 3: Item
        ctx.drawText(client.textRenderer, lastShopData.item, textX, lineY, 0xFFFFFFFF, true);
        lineY += lineH;

        // Line 4: Price + Action
        String priceText = lastShopData.action + " $" + (int)lastShopData.price + " each";
        ctx.drawText(client.textRenderer, priceText, textX, lineY, 0xFFFFAA00, true);
    }
}