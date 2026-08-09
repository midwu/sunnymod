package me.midwu.sunnyMod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

public class Hud implements ClientModInitializer {

    public static final int PANEL_WIDTH   = 160;
    public static final int LINE_HEIGHT   = 10;
    public static final int PADDING       = 6;
    public static final int SNAP_DISTANCE = 12;

    private static final int COLOR_BG      = 0xAA000000;
    private static final int COLOR_HEADER  = 0xFFFFD700;
    private static final int COLOR_LABEL   = 0xFFAAAAAA;
    private static final int COLOR_VALUE   = 0xFFFFFFFF;
    private static final int COLOR_DIVIDER = 0xFF444444;

    public static KeyBinding editKey;
    private static int lastEarningsHeight = -1;

    // ── Panel height helpers ──────────────────────────────────────────────────

    public static int getFishingPanelHeight() {
        return PADDING + LINE_HEIGHT + 3 + LINE_HEIGHT + PADDING;
    }

    public static int getEarningsPanelHeight() {
        Map<String, Integer> items = EarningsDetector.getItemTotals();
        int itemLines = items.isEmpty() ? 1 : items.size();
        return PADDING
                + LINE_HEIGHT + 3
                + LINE_HEIGHT + PADDING / 2
                + LINE_HEIGHT + 3
                + (LINE_HEIGHT * itemLines)
                + PADDING;
    }

    public static int getShopPanelHeight() {
        return PADDING + LINE_HEIGHT + 3 + LINE_HEIGHT + PADDING;
    }

    public static int getSignPanelHeight() {
        return ShopSignHud.SIGN_HEIGHT + 10; // +10 for the post
    }

    public static int getSignPanelWidth() {
        return ShopSignHud.SIGN_WIDTH;
    }

    public static int getWorthPanelHeight() {
        return ContainerWorthHud.getPanelHeight();
    }

    // ── Auto-hide logic ───────────────────────────────────────────────────────

    private boolean earningsShouldAutoHide() {
        long last = EarningsDetector.getLastSaleTimestamp();
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) > Config.get().earningsHideDelayMs();
    }

    private boolean shopShouldAutoHide() {
        long last = ShopLogger.getLastWarpActionTime();
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) > Config.get().shopHideDelayMs();
    }

    private boolean signShouldAutoHide() {
        long last = ShopSignHud.getLastShopTime();
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) > Config.get().signHideDelayMs();
    }

    private boolean worthShouldAutoHide() {
        long last = ContainerWorthHud.getLastUpdateTime();
        if (last == 0L) return true;
        return (System.currentTimeMillis() - last) > Config.get().worthHideDelayMs();
    }

    // ── Initialise ────────────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        Config.load();

        editKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sunnymod.edithud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                new KeyBinding.Category(Identifier.of("sunnymod", "general"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (editKey.wasPressed() && client.player != null) {
                client.setScreen(new HudEditScreen());
            }

            Config cfg = Config.get();
            int currentEarningsHeight = getEarningsPanelHeight();
            if (lastEarningsHeight != -1 && currentEarningsHeight != lastEarningsHeight) {
                int oldBottom = cfg.earningsY + lastEarningsHeight;
                int delta     = currentEarningsHeight - lastEarningsHeight;
                for (String panel : cfg.panelOrder) {
                    if (panel.equals("earnings")) continue;
                    int py = getPanelY(cfg, panel);
                    if (py == oldBottom) setPanelY(cfg, panel, py + delta);
                }
                Config.save();
            }
            lastEarningsHeight = currentEarningsHeight;
        });

        HudRenderCallback.EVENT.register((DrawContext drawContext,
                                          net.minecraft.client.render.RenderTickCounter tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.currentScreen != null) return;
            renderHud(drawContext, client);
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderHud(DrawContext ctx, MinecraftClient client) {
        Config cfg = Config.get();
        for (String panel : cfg.panelOrder) {
            switch (panel) {
                case "fishing"  -> {
                    if (cfg.fishingVisible)
                        renderFishingPanel(ctx, client, cfg.fishingX, cfg.fishingY);
                }
                case "earnings" -> {
                    if (cfg.earningsVisible && !earningsShouldAutoHide())
                        renderEarningsPanel(ctx, client, cfg.earningsX, cfg.earningsY);
                }
                case "shop"     -> {
                    if (cfg.shopVisible && !shopShouldAutoHide())
                        renderShopPanel(ctx, client, cfg.shopX, cfg.shopY);
                }
                case "sign"     -> {
                    if (cfg.signVisible && cfg.shopLoggerEnabled && !signShouldAutoHide())
                        ShopSignHud.render(ctx, client, cfg.signX, cfg.signY);
                }
                case "worth"    -> {
                    if (cfg.worthVisible && !worthShouldAutoHide())
                        ContainerWorthHud.render(ctx, client, cfg.worthX, cfg.worthY);
                }
            }
        }
    }

    // ── Fishing panel ─────────────────────────────────────────────────────────

    private void renderFishingPanel(DrawContext ctx, MinecraftClient client, int x, int y) {
        int h      = getFishingPanelHeight();
        int textX  = x + PADDING;
        int cursor = y + PADDING;

        ctx.fill(x, y, x + PANEL_WIDTH, y + h, COLOR_BG);
        ctx.drawText(client.textRenderer, "Fishing", textX, cursor, COLOR_HEADER, true);
        cursor += LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + PANEL_WIDTH - 2, cursor + 1, COLOR_DIVIDER);
        cursor += 4;
        ctx.drawText(client.textRenderer,
                "Next reset: " + computeFishingTimer(), textX, cursor, COLOR_VALUE, true);
    }

    private String computeFishingTimer() {
        LocalTime now          = LocalTime.now().plusHours(Config.get().timeOffset);
        int currentHour        = now.getHour();
        int nextTargetHour     = ((currentHour / 3) + 1) * 3;
        if (nextTargetHour >= 24) nextTargetHour = 0;
        LocalTime nextTargetTime = (nextTargetHour == 0)
                ? LocalTime.of(0, 0).plusHours(24)
                : LocalTime.of(nextTargetHour, 0);
        long minutesUntilNext  = now.until(nextTargetTime, ChronoUnit.MINUTES);
        if (minutesUntilNext < 0) minutesUntilNext += 24 * 60;
        return String.format("%02d:%02d", minutesUntilNext / 60, minutesUntilNext % 60);
    }

    // ── Earnings panel ────────────────────────────────────────────────────────

    private void renderEarningsPanel(DrawContext ctx, MinecraftClient client, int x, int y) {
        Map<String, Integer> items = EarningsDetector.getItemTotals();
        double total = EarningsDetector.getTotalAmount();
        int h      = getEarningsPanelHeight();
        int textX  = x + PADDING;
        int cursor = y + PADDING;

        ctx.fill(x, y, x + PANEL_WIDTH, y + h, COLOR_BG);
        ctx.drawText(client.textRenderer, "Earnings", textX, cursor, COLOR_HEADER, true);
        cursor += LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + PANEL_WIDTH - 2, cursor + 1, COLOR_DIVIDER);
        cursor += 4;
        ctx.drawText(client.textRenderer,
                "Total: " + String.format(Locale.US, "$%,.2f", total), textX, cursor, COLOR_VALUE, true);
        cursor += LINE_HEIGHT + 4;
        ctx.drawText(client.textRenderer, "Items Sold", textX, cursor, COLOR_LABEL, true);
        cursor += LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + PANEL_WIDTH - 2, cursor + 1, COLOR_DIVIDER);
        cursor += 4;

        if (items.isEmpty()) {
            ctx.drawText(client.textRenderer, "None yet", textX, cursor, COLOR_LABEL, true);
        } else {
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                ctx.drawText(client.textRenderer,
                        truncate(client, entry.getKey()) + ": " + entry.getValue(),
                        textX, cursor, COLOR_VALUE, true);
                cursor += LINE_HEIGHT;
            }
        }
    }

    // ── Shop panel ────────────────────────────────────────────────────────────

    private void renderShopPanel(DrawContext ctx, MinecraftClient client, int x, int y) {
        int h      = getShopPanelHeight();
        int textX  = x + PADDING;
        int cursor = y + PADDING;

        ctx.fill(x, y, x + PANEL_WIDTH, y + h, COLOR_BG);
        ctx.drawText(client.textRenderer, "Shop", textX, cursor, COLOR_HEADER, true);
        cursor += LINE_HEIGHT + 2;
        ctx.fill(x + 2, cursor, x + PANEL_WIDTH - 2, cursor + 1, COLOR_DIVIDER);
        cursor += 4;

        String warp    = ShopLogger.getLastWarp();
        String display = (warp == null || warp.isEmpty()) ? "No warp set" : warp;
        ctx.drawText(client.textRenderer, "Warp: " + display, textX, cursor, COLOR_VALUE, true);
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private int getPanelY(Config cfg, String panel) {
        return switch (panel) {
            case "fishing"  -> cfg.fishingY;
            case "earnings" -> cfg.earningsY;
            case "shop"     -> cfg.shopY;
            case "sign"     -> cfg.signY;
            case "worth"    -> cfg.worthY;
            default -> 0;
        };
    }

    private void setPanelY(Config cfg, String panel, int y) {
        switch (panel) {
            case "fishing"  -> cfg.fishingY  = y;
            case "earnings" -> cfg.earningsY = y;
            case "shop"     -> cfg.shopY     = y;
            case "sign"     -> cfg.signY     = y;
            case "worth"    -> cfg.worthY    = y;
        }
    }

    private String truncate(MinecraftClient client, String text) {
        int max = PANEL_WIDTH - PADDING * 2 - 30;
        if (client.textRenderer.getWidth(text) <= max) return text;
        while (!text.isEmpty() && client.textRenderer.getWidth(text + "…") > max)
            text = text.substring(0, text.length() - 1);
        return text + "…";
    }
}