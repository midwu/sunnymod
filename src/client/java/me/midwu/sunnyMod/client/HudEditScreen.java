package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class HudEditScreen extends Screen {

    private static final int SNAP_DIST   = Hud.SNAP_DISTANCE;
    private static final int PANEL_WIDTH = Hud.PANEL_WIDTH;
    private static final int BAR_HEIGHT  = 24;
    private static final int CHECK_SIZE  = 8;

    private String draggingPanel = null;
    private double dragOffsetX   = 0;
    private double dragOffsetY   = 0;
    private boolean wasPressed   = false;

    public HudEditScreen() {
        super(Text.literal("Edit HUD"));
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        Config cfg = Config.get();
        long windowHandle = MinecraftClient.getInstance().getWindow().getHandle();
        boolean pressed = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (pressed && !wasPressed) {
            handleCheckboxClick(mouseX, mouseY, cfg);
            handleResetButtonClick(mouseX, mouseY, cfg);

            if (draggingPanel == null) {
                List<String> order = new ArrayList<>(cfg.panelOrder);
                for (int i = order.size() - 1; i >= 0; i--) {
                    String panel = order.get(i);
                    int px = getPanelX(cfg, panel);
                    int py = getPanelY(cfg, panel);
                    int ph = getPanelHeight(panel);
                    if (mouseX >= px && mouseX <= px + PANEL_WIDTH && mouseY >= py && mouseY <= py + ph) {
                        draggingPanel = panel;
                        dragOffsetX   = mouseX - px;
                        dragOffsetY   = mouseY - py;
                        break;
                    }
                }
            }
        }

        if (!pressed) draggingPanel = null;
        wasPressed = pressed;

        if (draggingPanel != null && pressed) {
            int newX = (int) Math.clamp(mouseX - dragOffsetX, 0, this.width - PANEL_WIDTH);
            int newY = (int) Math.clamp(mouseY - dragOffsetY, 0, this.height - BAR_HEIGHT - getPanelHeight(draggingPanel));
            newX = snapX(cfg, draggingPanel, newX);
            newY = snapY(cfg, draggingPanel, newY);
            setPanelPos(cfg, draggingPanel, newX, newY);
        }

        // Draw panels
        int gold = 0xFFFFD700;
        for (String panel : cfg.panelOrder) {
            boolean visible = isPanelVisible(cfg, panel);
            int px = getPanelX(cfg, panel);
            int py = getPanelY(cfg, panel);
            int ph = getPanelHeight(panel);

            context.fill(px, py, px + PANEL_WIDTH, py + ph, visible ? 0xBB000000 : 0x55000000);
            int borderColor = panel.equals(draggingPanel) ? 0xFFFFFFFF : gold;
            context.fill(px, py, px + PANEL_WIDTH, py + 1, borderColor);
            context.fill(px, py + ph - 1, px + PANEL_WIDTH, py + ph, borderColor);
            context.fill(px, py, px + 1, py + ph, borderColor);
            context.fill(px + PANEL_WIDTH - 1, py, px + PANEL_WIDTH, py + ph, borderColor);

            String label = panel.substring(0, 1).toUpperCase() + panel.substring(1) + " HUD";
            if (!visible) label += " (hidden)";
            context.drawTextWithShadow(this.textRenderer, Text.literal(label),
                    px + (PANEL_WIDTH - this.textRenderer.getWidth(label)) / 2,
                    py + ph / 2 - 4, gold);
        }

        // Bottom bar
        int barY = this.height - BAR_HEIGHT;
        context.fill(0, barY, this.width, this.height, 0xDD000000);

        String[] panels = {"fishing", "earnings", "shop"};
        String[] labels = {"Fishing", "Earnings", "Shop"};
        int startX = 10;

        for (int i = 0; i < panels.length; i++) {
            boolean visible = isPanelVisible(cfg, panels[i]);
            int cx = startX;
            int cy = barY + (BAR_HEIGHT - CHECK_SIZE) / 2;

            context.fill(cx, cy, cx + CHECK_SIZE, cy + CHECK_SIZE, 0xFF888888);
            if (visible)
                context.fill(cx + 2, cy + 2, cx + CHECK_SIZE - 2, cy + CHECK_SIZE - 2, 0xFFFFD700);

            context.drawTextWithShadow(this.textRenderer, Text.literal(labels[i]),
                    cx + CHECK_SIZE + 3, barY + (BAR_HEIGHT - 8) / 2, 0xFFFFFFFF);

            startX += CHECK_SIZE + this.textRenderer.getWidth(labels[i]) + 20;
        }

        // Reset button
        int resetX = this.width / 2 - 40;
        int resetY = barY + (BAR_HEIGHT - 10) / 2;
        boolean resetHovered = mouseX >= resetX && mouseX <= resetX + 80
                && mouseY >= resetY && mouseY <= resetY + 10;
        context.fill(resetX, resetY, resetX + 80, resetY + 10,
                resetHovered ? 0xAAFF4444 : 0x88FF4444);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Reset Positions"),
                resetX + (80 - this.textRenderer.getWidth("Reset Positions")) / 2,
                resetY + 1, 0xFFFFFFFF);

        // Hint
        String hint = "Drag panels  •  H / Esc to save & close";
        context.drawTextWithShadow(this.textRenderer, Text.literal(hint),
                this.width - this.textRenderer.getWidth(hint) - 10,
                barY + (BAR_HEIGHT - 8) / 2, 0xFFAAAAAA);

        super.render(context, mouseX, mouseY, delta);
    }

    private void handleCheckboxClick(int mouseX, int mouseY, Config cfg) {
        int barY = this.height - BAR_HEIGHT;
        if (mouseY < barY) return;

        String[] panels = {"fishing", "earnings", "shop"};
        String[] labels = {"Fishing", "Earnings", "Shop"};
        int startX = 10;

        for (int i = 0; i < panels.length; i++) {
            int cx     = startX;
            int cy     = barY + (BAR_HEIGHT - CHECK_SIZE) / 2;
            int totalW = CHECK_SIZE + 3 + this.textRenderer.getWidth(labels[i]);

            if (mouseX >= cx && mouseX <= cx + totalW && mouseY >= cy && mouseY <= cy + CHECK_SIZE) {
                toggleVisibility(cfg, panels[i]);
                break;
            }
            startX += CHECK_SIZE + this.textRenderer.getWidth(labels[i]) + 20;
        }
    }

    private void handleResetButtonClick(int mouseX, int mouseY, Config cfg) {
        int barY   = this.height - BAR_HEIGHT;
        int resetX = this.width / 2 - 40;
        int resetY = barY + (BAR_HEIGHT - 10) / 2;
        if (mouseX >= resetX && mouseX <= resetX + 80
                && mouseY >= resetY && mouseY <= resetY + 10) {
            cfg.resetHudDefaults();
        }
    }

    private void toggleVisibility(Config cfg, String panel) {
        switch (panel) {
            case "fishing"  -> cfg.fishingVisible  = !cfg.fishingVisible;
            case "earnings" -> cfg.earningsVisible = !cfg.earningsVisible;
            case "shop"     -> cfg.shopVisible     = !cfg.shopVisible;
        }
    }

    private int snapX(Config cfg, String moving, int newX) {
        for (String other : cfg.panelOrder) {
            if (other.equals(moving)) continue;
            if (Math.abs(newX - getPanelX(cfg, other)) < SNAP_DIST) return getPanelX(cfg, other);
        }
        return newX;
    }

    private int snapY(Config cfg, String moving, int newY) {
        int mh = getPanelHeight(moving);
        for (String other : cfg.panelOrder) {
            if (other.equals(moving)) continue;
            int oy = getPanelY(cfg, other);
            int oh = getPanelHeight(other);
            if (Math.abs((newY + mh) - oy) < SNAP_DIST) return oy - mh;
            if (Math.abs(newY - (oy + oh)) < SNAP_DIST)  return oy + oh;
        }
        return newY;
    }

    private int getPanelX(Config cfg, String panel) {
        return switch (panel) {
            case "fishing"  -> cfg.fishingX;
            case "earnings" -> cfg.earningsX;
            case "shop"     -> cfg.shopX;
            default -> 0;
        };
    }

    private int getPanelY(Config cfg, String panel) {
        return switch (panel) {
            case "fishing"  -> cfg.fishingY;
            case "earnings" -> cfg.earningsY;
            case "shop"     -> cfg.shopY;
            default -> 0;
        };
    }

    private int getPanelHeight(String panel) {
        return switch (panel) {
            case "fishing"  -> Hud.getFishingPanelHeight();
            case "earnings" -> Hud.getEarningsPanelHeight();
            case "shop"     -> Hud.getShopPanelHeight();
            default -> 20;
        };
    }

    private boolean isPanelVisible(Config cfg, String panel) {
        return switch (panel) {
            case "fishing"  -> cfg.fishingVisible;
            case "earnings" -> cfg.earningsVisible;
            case "shop"     -> cfg.shopVisible;
            default -> true;
        };
    }

    private void setPanelPos(Config cfg, String panel, int x, int y) {
        switch (panel) {
            case "fishing"  -> { cfg.fishingX  = x; cfg.fishingY  = y; }
            case "earnings" -> { cfg.earningsX = x; cfg.earningsY = y; }
            case "shop"     -> { cfg.shopX     = x; cfg.shopY     = y; }
        }
    }

    @Override
    public void close() {
        Config.save();
        super.close();
    }

    @Override
    public boolean shouldPause() { return false; }
}