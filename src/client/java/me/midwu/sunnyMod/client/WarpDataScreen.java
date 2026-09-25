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
 * F9 warp-data viewer.
 *
 * Shows shop_data warps first, marks shop warps missing from Public Warps,
 * and provides a Warp button for every row.
 */
public class WarpDataScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H = 58;
    private static final int FOOTER_H = 30;
    private static final int PAD = 12;
    private static final int WARP_BTN_W = 82;

    private List<WarpData.WarpRow> rows = List.of();
    private boolean missingOnly = false;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public WarpDataScreen() {
        super(Text.literal("Warp Data"));
        reload();
    }

    private void reload() {
        rows = missingOnly ? WarpData.missingShopWarps() : WarpData.buildRows();
        scrollOffset = 0;
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, rows.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        addDrawableChild(ButtonWidget.builder(
                        Text.literal(missingOnly ? "Show All" : "Missing Shops"),
                        b -> {
                            missingOnly = !missingOnly;
                            reload();
                            rebuildButtons();
                        })
                .dimensions(PAD, this.height - FOOTER_H + 4, 105, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                    reload();
                    rebuildButtons();
                })
                .dimensions(PAD + 112, this.height - FOOTER_H + 4, 75, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 75, this.height - FOOTER_H + 4, 75, 20)
                .build());

        int end = Math.min(rows.size(), scrollOffset + visibleRows);
        int warpX = this.width - PAD - WARP_BTN_W;

        for (int i = scrollOffset; i < end; i++) {
            WarpData.WarpRow row = rows.get(i);
            int y = listTop + (i - scrollOffset) * ROW_HEIGHT;

            final String warp = row.warp();
            addDrawableChild(ButtonWidget.builder(Text.literal("Warp"), b -> warp(warp))
                    .dimensions(warpX, y, WARP_BTN_W, 20)
                    .build());
        }
    }

    private void warp(String warp) {
        String clean = warp == null ? "" : warp.trim();
        if (clean.isEmpty()) return;

        if (clean.startsWith("/warp ")) {
            clean = clean.substring(6).trim();
        } else if (clean.startsWith("warp ")) {
            clean = clean.substring(5).trim();
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;

        net.sendChatCommand("warp " + clean);

        // The server needs time to teleport and load the destination chunks.
        ContainerWorthScreen.PendingFindsign.schedule("findsign each", 40);
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int missing = 0;
        int shop = 0;
        for (WarpData.WarpRow row : rows) {
            if (row.missingFromPublicWarps()) missing++;
            if (WarpData.isShopType(row.type())) shop++;
        }

        ctx.drawCenteredTextWithShadow(
                textRenderer,
                "Warp Data",
                this.width / 2,
                10,
                0xFFFFD700);

        String summary = (missingOnly ? "Missing shop warps: " : "Public warps: ")
                + rows.size() + "   ·   shop: " + shop
                + "   ·   missing: " + missing;
        ctx.drawCenteredTextWithShadow(
                textRenderer,
                summary,
                this.width / 2,
                25,
                0xFFFFFFFF);

        ctx.drawText(
                textRenderer,
                "warp_data.csv: " + WarpData.getFile().getFileName(),
                PAD,
                42,
                0xFF888888,
                false);

        if (rows.isEmpty()) {
            ctx.drawCenteredTextWithShadow(
                    textRenderer,
                    missingOnly ? "No shop warps are missing." : "No warp data found. Press F9 in Public Warps first.",
                    this.width / 2,
                    HEADER_H + 24,
                    0xFFAAAAAA);
            return;
        }

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int end = Math.min(rows.size(), scrollOffset + visibleRows);

        int warpX = this.width - PAD - WARP_BTN_W;
        int typeX = Math.min(160, this.width / 4);
        int visitsX = Math.min(290, this.width / 2);

        ctx.drawText(textRenderer, "Warp", PAD, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Type / status", typeX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Visits", visitsX, listTop - 12, 0xFFAAAAAA, false);

        for (int i = scrollOffset; i < end; i++) {
            WarpData.WarpRow row = rows.get(i);
            int y = listTop + (i - scrollOffset) * ROW_HEIGHT + 6;

            int nameColor;
            if (row.missingFromPublicWarps()) {
                nameColor = 0xFFFF5555;
            } else if (WarpData.isShopType(row.type())) {
                nameColor = 0xFFFFD700;
            } else {
                nameColor = 0xFFFFFFFF;
            }

            String name = row.warp();
            int maxNameW = typeX - PAD - 8;
            if (textRenderer.getWidth(name) > maxNameW) {
                while (!name.isEmpty() && textRenderer.getWidth(name + "…") > maxNameW) {
                    name = name.substring(0, name.length() - 1);
                }
                name += "…";
            }
            ctx.drawText(textRenderer, name, PAD, y, nameColor, false);

            String typeText;
            if (row.missingFromPublicWarps()) {
                typeText = "MISSING";
            } else if (!row.type().isBlank()) {
                typeText = row.type();
            } else {
                typeText = "—";
            }
            ctx.drawText(
                    textRenderer,
                    typeText,
                    typeX,
                    y,
                    row.missingFromPublicWarps() ? 0xFFFF5555 : 0xFFAAAAAA,
                    false);

            String visits = row.missingFromPublicWarps()
                    ? "not in Public Warps"
                    : String.format(Locale.US, "%,d / %,d",
                    row.monthlyVisits(), row.allTimeVisits());
            ctx.drawText(textRenderer, visits, visitsX, y, 0xFFBBBBBB, false);
        }

        if (maxScroll > 0) {
            ctx.drawText(
                    textRenderer,
                    "Scroll " + (scrollOffset + 1) + "–" + end + " / " + rows.size(),
                    PAD,
                    this.height - FOOTER_H + 9,
                    0xFF888888,
                    false);
        }

        ctx.drawText(
                textRenderer,
                "Warp → /warp <name> → findsign each",
                PAD + 200,
                this.height - FOOTER_H + 9,
                0xFF666666,
                false);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount) {

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

        int before = scrollOffset;
        if (verticalAmount < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        } else if (verticalAmount > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        }

        if (before != scrollOffset) {
            rebuildButtons();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
