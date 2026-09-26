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
import java.util.stream.Collectors;

/**
 * F9 warp-data viewer.
 *
 * Styled to match the compact F8/F7 data screens: centered title/summary,
 * small column headers, compact rows, scrolling, and a simple footer toolbar.
 * Shop warps stay first. Missing shop warps can be hidden with the footer toggle.
 */
public class WarpDataScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H = 52;
    private static final int FOOTER_H = 32;
    private static final int PAD = 12;
    private static final int WARP_BTN_W = 68;
    private static final int SKIP_BTN_W = 52;
    private static final int BUTTON_GAP = 4;

    private List<WarpData.WarpRow> allRows = List.of();
    private List<WarpData.WarpRow> rows = List.of();
    private boolean hideMissing = false;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private final java.util.Set<String> skippedWarps = new java.util.HashSet<>();

    public WarpDataScreen() {
        super(Text.literal("Warp Data"));
        reload();
    }

    private void reload() {
        allRows = WarpData.refreshComparison();
        applyFilter();
    }

    private void applyFilter() {
        if (hideMissing) {
            rows = allRows.stream()
                    .filter(row -> !row.missingFromPublicWarps())
                    .collect(Collectors.toList());
        } else {
            rows = new ArrayList<>(allRows);
        }

        // Keep the requested visual priority: gold first, then green/red,
        // then the remaining public-only rows. Skipped rows always go last.
        rows.sort((a, b) -> {
            int skippedCompare = Boolean.compare(isSkipped(a), isSkipped(b));
            if (skippedCompare != 0) return skippedCompare;

            int priorityCompare = Integer.compare(rowPriority(a), rowPriority(b));
            if (priorityCompare != 0) return priorityCompare;

            return a.warp().compareToIgnoreCase(b.warp());
        });
        scrollOffset = 0;
    }

    private boolean isSkipped(WarpData.WarpRow row) {
        return skippedWarps.contains(WarpData.normalizeWarpName(row.warp()));
    }

    private int rowPriority(WarpData.WarpRow row) {
        if (row.missingFromPublicWarps()) return 2; // red
        if (row.inShopData()) return 1;             // green
        if (WarpData.isShopType(row.type())) return 0; // gold
        return 3;                                  // white
    }

    private void skip(String warp) {
        String key = WarpData.normalizeWarpName(warp);
        if (key.isEmpty()) return;
        skippedWarps.add(key);
        applyFilter();
        rebuildButtons();
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private int visibleRows() {
        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    }

    private void rebuildButtons() {
        clearChildren();

        int visible = visibleRows();
        maxScroll = Math.max(0, rows.size() - visible);
        scrollOffset = Math.min(scrollOffset, maxScroll);

        int footerY = this.height - FOOTER_H + 5;

        addDrawableChild(ButtonWidget.builder(
                        Text.literal(hideMissing ? "Hide Missing: ON" : "Hide Missing: OFF"),
                        b -> {
                            hideMissing = !hideMissing;
                            applyFilter();
                            rebuildButtons();
                        })
                .dimensions(PAD, footerY, 125, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                    // Re-read warp_data.csv AND shop_data.csv, then compare them again.
                    reload();
                    rebuildButtons();
                })
                .dimensions(PAD + 132, footerY, 75, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 75, footerY, 75, 20)
                .build());

        int end = Math.min(rows.size(), scrollOffset + visible);
        int warpX = this.width - PAD - WARP_BTN_W;
        for (int i = scrollOffset; i < end; i++) {
            WarpData.WarpRow row = rows.get(i);
            int y = HEADER_H + (i - scrollOffset) * ROW_HEIGHT;
            final String warp = row.warp();
            int skipX = this.width - PAD - SKIP_BTN_W;
            int actualWarpX = skipX - BUTTON_GAP - WARP_BTN_W;
            addDrawableChild(ButtonWidget.builder(Text.literal("Warp"), b -> warp(warp))
                    .dimensions(actualWarpX, y, WARP_BTN_W, 20)
                    .build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Skip"), b -> skip(warp))
                    .dimensions(skipX, y, SKIP_BTN_W, 20)
                    .build());
        }
    }

    private void warp(String warp) {
        String clean = warp == null ? "" : warp.trim();
        if (clean.isEmpty()) return;

        if (clean.startsWith("/warp ")) clean = clean.substring(6).trim();
        else if (clean.startsWith("warp ")) clean = clean.substring(5).trim();
        else if (clean.startsWith("/")) clean = clean.substring(1).trim();

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;

        net.sendChatCommand("warp " + clean);
        ContainerWorthScreen.PendingFindsign.schedule("findsign each", 40);
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int total = allRows.size();
        int missing = (int) allRows.stream().filter(WarpData.WarpRow::missingFromPublicWarps).count();
        int shops = (int) allRows.stream().filter(r -> WarpData.isShopType(r.type())).count();

        // Same compact title/summary hierarchy as the existing F8/F7 screens.
        ctx.drawCenteredTextWithShadow(textRenderer, "Warp Data", this.width / 2, 10, 0xFFFFD700);
        String summary = String.format(Locale.US,
                "%d warps   ·   %d shop   ·   %d missing%s",
                total, shops, missing, hideMissing ? "   ·   hidden" : "");
        ctx.drawCenteredTextWithShadow(textRenderer, summary, this.width / 2, 24, 0xFFFFFFFF);
        ctx.drawText(textRenderer, "warp_data.csv", PAD, 39, 0xFF666666, false);
        ctx.drawText(textRenderer, "gold = shop   green = both   red = missing   purple = skipped", PAD + 105, 39, 0xFF55CC55, false);

        if (rows.isEmpty()) {
            String empty = hideMissing
                    ? "No visible warps. Turn off Hide Missing to show missing shop warps."
                    : "No warp data found. Press F9 in Public Warps first.";
            ctx.drawCenteredTextWithShadow(textRenderer, empty, this.width / 2, HEADER_H + 22, 0xFFAAAAAA);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int end = Math.min(rows.size(), scrollOffset + visible);

        int nameX = PAD;
        int typeX = Math.min(175, this.width / 4);
        int visitsX = Math.min(330, this.width / 2);
        int skipX = this.width - PAD - SKIP_BTN_W;
        int warpX = skipX - BUTTON_GAP - WARP_BTN_W;

        ctx.drawText(textRenderer, "Warp", nameX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Type", typeX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Monthly / All-time", visitsX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Action", warpX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Skip", skipX, listTop - 12, 0xFFAAAAAA, false);

        // Highlight the entire row under the cursor, matching the F8-style
        // hover feedback instead of limiting the effect to the buttons.
        for (int i = scrollOffset; i < end; i++) {
            int rowTop = listTop + (i - scrollOffset) * ROW_HEIGHT;
            if (mouseX >= PAD && mouseX < this.width - PAD
                    && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT) {
                ctx.fill(PAD, rowTop, this.width - PAD, rowTop + ROW_HEIGHT, 0x55333333);
            }
        }

        // Draw widgets after the row hover background so the buttons stay visible.
        super.render(ctx, mouseX, mouseY, delta);

        for (int i = scrollOffset; i < end; i++) {
            WarpData.WarpRow row = rows.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT + 6;

            boolean skipped = isSkipped(row);
            int nameColor;
            int secondaryColor;
            if (skipped) {
                nameColor = 0xFFAA66FF;
                secondaryColor = 0xFFAA66FF;
            } else if (row.missingFromPublicWarps()) {
                nameColor = 0xFFFF5555;
                secondaryColor = 0xFFFF5555;
            } else if (row.inShopData()) {
                // Present in both shop_data.csv and warp_data.csv.
                nameColor = 0xFF55FF55;
                secondaryColor = 0xFF55CC55;
            } else if (WarpData.isShopType(row.type())) {
                nameColor = 0xFFFFD700;
                secondaryColor = 0xFFAAAAAA;
            } else {
                nameColor = 0xFFFFFFFF;
                secondaryColor = 0xFFAAAAAA;
            }

            String name = row.warp();
            int maxNameW = typeX - nameX - 8;
            if (textRenderer.getWidth(name) > maxNameW) {
                while (!name.isEmpty() && textRenderer.getWidth(name + "…") > maxNameW)
                    name = name.substring(0, name.length() - 1);
                name += "…";
            }
            ctx.drawText(textRenderer, name, nameX, rowY, nameColor, false);

            String type;
            if (skipped) type = "SKIPPED";
            else if (row.missingFromPublicWarps()) type = "MISSING";
            else if (row.inShopData()) type = "SHOP + PUBLIC";
            else type = row.type().isBlank() ? "—" : row.type();
            ctx.drawText(textRenderer, type, typeX, rowY, secondaryColor, false);

            String visits = row.missingFromPublicWarps()
                    ? "not in Public Warps"
                    : String.format(Locale.US, "%,d / %,d", row.monthlyVisits(), row.allTimeVisits());
            ctx.drawText(textRenderer, visits, visitsX, rowY, 0xFFBBBBBB, false);
        }

        if (maxScroll > 0) {
            ctx.drawText(textRenderer,
                    "Scroll " + (scrollOffset + 1) + "–" + end + " / " + rows.size(),
                    PAD, this.height - FOOTER_H + 10, 0xFF888888, false);
        }
        ctx.drawText(textRenderer,
                "Warp → /warp <name> → findsign each",
                PAD + 280, this.height - FOOTER_H + 10, 0xFF666666, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int before = scrollOffset;
        if (verticalAmount < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        else if (verticalAmount > 0) scrollOffset = Math.max(0, scrollOffset - 1);

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
