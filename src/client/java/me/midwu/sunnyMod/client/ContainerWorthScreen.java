package me.midwu.sunnyMod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen (scrollable) breakdown of F7 chest-worth results.
 *
 * Layout per row:
 *   Item name xCount          $unit  $subtotal   [ /warp name ]
 *
 * Clicking a warp button runs the teleport command on the server and
 * closes this screen. ESC / the Close button returns without warping.
 */
public class ContainerWorthScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H   = 48;
    private static final int FOOTER_H   = 28;
    private static final int PAD        = 12;
    private static final int WARP_BTN_W = 120;

    private final List<ContainerWorthHud.Entry> entries;
    private final double total;
    private final int pricedStacks;
    private final int unpricedStacks;

    private int scrollOffset = 0; // in rows
    private int maxScroll    = 0;

    public ContainerWorthScreen(List<ContainerWorthHud.Entry> entries,
                                double total, int pricedStacks, int unpricedStacks) {
        super(Text.literal("Chest Worth"));
        this.entries = entries != null ? List.copyOf(entries) : List.of();
        this.total = total;
        this.pricedStacks = pricedStacks;
        this.unpricedStacks = unpricedStacks;
    }

    @Override
    protected void init() {
        clearChildren();
        scrollOffset = 0;

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, entries.size() - visibleRows);

        // Close button
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
                .build());

        rebuildRowButtons(visibleRows);
    }

    private void rebuildRowButtons(int visibleRows) {
        // Remove previous row buttons (keep the Close button — last added in init,
        // but safer to clear all and re-add close).
        clearChildren();
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
                .build());

        int listTop = HEADER_H;
        int end = Math.min(entries.size(), scrollOffset + visibleRows);

        for (int i = scrollOffset; i < end; i++) {
            ContainerWorthHud.Entry e = entries.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;

            String warpCmd = normalizeWarp(e.warp);
            if (warpCmd.isEmpty()) continue;

            String label = warpCmd.length() > 16 ? warpCmd.substring(0, 15) + "…" : warpCmd;
            final String cmd = warpCmd;
            int btnX = this.width - PAD - WARP_BTN_W;

            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> runWarp(cmd))
                    .dimensions(btnX, rowY, WARP_BTN_W, 20)
                    .build());
        }
    }

    private void runWarp(String warpCmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;

        // sendChatCommand expects no leading slash
        String cmd = warpCmd.startsWith("/") ? warpCmd.substring(1) : warpCmd;
        net.sendChatCommand(cmd);
        close();
    }

    private static String normalizeWarp(String warp) {
        if (warp == null || warp.isBlank()) return "";
        String w = warp.trim();
        if (w.startsWith("/warp ")) return w;
        if (w.startsWith("warp ")) return "/" + w;
        if (w.startsWith("/")) return w;
        return "/warp " + w;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        // Title + total
        ctx.drawCenteredTextWithShadow(textRenderer, "Chest Worth", this.width / 2, 10, 0xFFFFD700);
        String totalLine = "Total: $" + String.format(Locale.US, "%,.2f", total) +
                "   (" + pricedStacks + " priced / " + unpricedStacks + " unpriced stacks, " +
                entries.size() + " items)";
        ctx.drawCenteredTextWithShadow(textRenderer, totalLine, this.width / 2, 24, 0xFFFFFFFF);

        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "No items to show", this.width / 2, HEADER_H + 20, 0xFFAAAAAA);
            return;
        }

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int end = Math.min(entries.size(), scrollOffset + visibleRows);

        // Column headers
        int nameX = PAD;
        int qtyX  = Math.min(220, this.width / 3);
        int unitX = Math.min(300, this.width / 2 - 40);
        int subX  = Math.min(400, this.width / 2 + 40);
        ctx.drawText(textRenderer, "Item", nameX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Qty", qtyX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Unit $", unitX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Total $", subX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Warp", this.width - PAD - WARP_BTN_W, listTop - 12, 0xFFAAAAAA, false);

        for (int i = scrollOffset; i < end; i++) {
            ContainerWorthHud.Entry e = entries.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT + 6;
            int color = (e.unitPrice != null) ? 0xFFFFFFFF : 0xFF888888;

            String name = e.name;
            int maxNameW = qtyX - nameX - 8;
            if (textRenderer.getWidth(name) > maxNameW) {
                while (!name.isEmpty() && textRenderer.getWidth(name + "…") > maxNameW)
                    name = name.substring(0, name.length() - 1);
                name = name + "…";
            }

            ctx.drawText(textRenderer, name, nameX, rowY, color, false);
            ctx.drawText(textRenderer, "x" + e.count, qtyX, rowY, color, false);

            if (e.unitPrice != null) {
                ctx.drawText(textRenderer,
                        "$" + String.format(Locale.US, "%,.2f", e.unitPrice), unitX, rowY, color, false);
                ctx.drawText(textRenderer,
                        "$" + String.format(Locale.US, "%,.2f", e.subtotal), subX, rowY, color, false);
            } else {
                ctx.drawText(textRenderer, "—", unitX, rowY, 0xFF666666, false);
                ctx.drawText(textRenderer, "—", subX, rowY, 0xFF666666, false);
            }

            // Owner hint left of warp button when no warp
            if ((e.warp == null || e.warp.isBlank()) && e.owner != null && !e.owner.isEmpty()) {
                String who = "__server__".equals(e.owner) ? "@server" : "@" + e.owner;
                ctx.drawText(textRenderer, who,
                        this.width - PAD - WARP_BTN_W, rowY, 0xFFAAAAAA, false);
            }
        }

        // Scroll hint
        if (maxScroll > 0) {
            String scrollHint = "Scroll " + (scrollOffset + 1) + "–" + end + " / " + entries.size();
            ctx.drawText(textRenderer, scrollHint, PAD, this.height - FOOTER_H + 8, 0xFF888888, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

        int before = scrollOffset;
        if (verticalAmount < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        else if (verticalAmount > 0) scrollOffset = Math.max(0, scrollOffset - 1);

        if (scrollOffset != before) {
            rebuildRowButtons(visibleRows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}