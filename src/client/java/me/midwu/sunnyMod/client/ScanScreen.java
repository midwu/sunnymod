package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Detailed rundown of the last SignScanner scan (F7).
 *
 * Header shows the summary — signs found, rows updated (new / changed /
 * unchanged), CSV shops at the current warp that the scan did not see, and
 * the total CSV rows for this warp.
 *
 * Below that, every new or changed shop gets a block in the style:
 *
 *   [CHANGED] 100 25 -100            PlayerName · /warp koopa
 *       Stock  100 → 250
 *       Price  $4.50 → $5.00
 *       Item   stone → stone bricks
 *
 * Clicking a block highlights that shop in the world via ShopHighlighter
 * (same mechanism as the Flips warp buttons) — no warp, screen stays open.
 */
public class ScanScreen extends Screen {

    private static final int PAD        = 12;
    private static final int HEADER_H   = 70;  // title + summary lines
    private static final int FOOTER_H   = 28;
    private static final int BLOCK_H    = 62;  // height of one shop change block

    private int scrollOffset = 0;
    private int maxScroll = 0;

    public ScanScreen() {
        super(Text.literal("Sign scan report"));
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"), b -> {
            SignScanner.scanNow(this.client);
            scrollOffset = 0;
        }).dimensions(this.width - PAD - 170, 6, 70, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                    // Just re-read lastReport() — rendered fresh every frame anyway.
                }).dimensions(this.width - PAD - 90, 6, 70, 18)
                .tooltip(Tooltip.of(Text.literal("Re-reads the last scan report.")))
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - 24, 70, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (vertical < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            SignScanner.ScanReport rep = SignScanner.lastReport();
            if (rep != null && !rep.changes().isEmpty()) {
                int listTop = HEADER_H;
                int block = (int) ((click.y() - listTop) / BLOCK_H);
                int idx = scrollOffset + block;
                if (click.y() >= listTop && block >= 0 && idx < rep.changes().size()
                        && click.y() < listTop + (block + 1) * BLOCK_H
                        && click.x() >= PAD && click.x() < this.width - PAD) {
                    SignScanner.ShopChange c = rep.changes().get(idx);
                    // Highlight this exact shop in the world. Screen stays open.
                    ShopHighlighter.activateForLocations(c.warp(), List.of(c.location()));
                    if (this.client != null && this.client.player != null) {
                        this.client.player.sendMessage(Text.literal(
                                "§a[Shop] Highlighting §f" + c.location()
                                        + (c.warp().isBlank() ? "" : " §7(" + c.warp() + ")")), false);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        SignScanner.ScanReport rep = SignScanner.lastReport();

        ctx.drawText(textRenderer, "Sign scan report", PAD, 28, 0xFFCCCCCC, false);

        if (rep == null) {
            ctx.drawText(textRenderer,
                    "No scan yet — press F6 (Scan Shops) or run /shopscan first.",
                    PAD, 48, 0xFF888888, false);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        String warpDisplay = rep.warp().isBlank() ? "(none)" : rep.warp();
        ctx.drawText(textRenderer, "Scan at " + rep.time() + " · warp: " + warpDisplay,
                PAD, 40, 0xFF888888, false);

        ctx.drawText(textRenderer, String.format(Locale.US,
                        "Found %d · Updated %d  (new %d · changed %d · unchanged %d)",
                        rep.found(), rep.updated(), rep.newCount(),
                        rep.changedCount(), rep.unchangedCount()),
                PAD, 52, 0xFF88FF88, false);

        ctx.drawText(textRenderer, String.format(Locale.US,
                        "Not seen at this warp: %d · CSV rows for this warp: %d",
                        rep.missingCount(), rep.csvRowsForWarp()),
                PAD, 64, 0xFF88FF88, false);

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleBlocks = Math.max(1, (listBottom - listTop) / BLOCK_H);
        maxScroll = Math.max(0, rep.changes().size() - visibleBlocks);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        if (rep.changes().isEmpty()) {
            ctx.drawText(textRenderer, "No shop changes in the last scan.",
                    PAD, listTop + 8, 0xFF888888, false);
        }

        int y = listTop;
        for (int i = 0; i < visibleBlocks; i++) {
            int idx = scrollOffset + i;
            if (idx >= rep.changes().size()) break;
            SignScanner.ShopChange c = rep.changes().get(idx);

            boolean hover = mouseY >= y && mouseY < y + BLOCK_H
                    && mouseX >= PAD && mouseX < this.width - PAD;
            if (hover) {
                ctx.fill(PAD - 2, y - 1, this.width - PAD + 2, y + BLOCK_H - 2, 0x33FFFFFF);
            }

            // First line: tag + location + owner/warp on the right.
            String tag = c.isNew() ? "[NEW]" : "[CHANGED]";
            int tagColor = c.isNew() ? 0xFF88FF88 : 0xFFFFFFAA;
            ctx.drawText(textRenderer, tag, PAD, y, tagColor, false);
            int hx = PAD + textRenderer.getWidth(c.isNew() ? "[NEW]" : "[CHANGED]") + 6;
            ctx.drawText(textRenderer, c.location(), hx, y, 0xFFFFFFFF, false);
            String owner = c.newOwner() + (c.warp().isBlank() ? "" : "  ·  " + c.warp());
            ctx.drawText(textRenderer, owner,
                    this.width - PAD - textRenderer.getWidth(owner), y, 0xFF888888, false);

            // Detail lines: only the fields that actually changed (new shops: all).
            int yy = y + 12;
            if (c.isNew()) {
                ctx.drawText(textRenderer, "Stock  " + c.newStock(), PAD + 8, yy, 0xFFCCCCCC, false);
                yy += 10;
                ctx.drawText(textRenderer, String.format(Locale.US, "Price  $%.2f", c.newPrice()),
                        PAD + 8, yy, 0xFFCCCCCC, false);
                yy += 10;
                ctx.drawText(textRenderer, "Item  " + c.newItem(), PAD + 8, yy, 0xFFCCCCCC, false);
                yy += 10;
                ctx.drawText(textRenderer, "Action  " + c.newAction() + " · " + c.newStatus(),
                        PAD + 8, yy, 0xFF999999, false);
            } else {
                if (c.oldStock() != c.newStock()) {
                    ctx.drawText(textRenderer, "Stock  " + c.oldStock() + " → " + c.newStock(),
                            PAD + 8, yy, 0xFFCCCCCC, false);
                    yy += 10;
                }
                if (Math.abs(c.oldPrice() - c.newPrice()) > 0.0001) {
                    ctx.drawText(textRenderer, String.format(Locale.US, "Price  $%.2f → $%.2f",
                                    c.oldPrice(), c.newPrice()),
                            PAD + 8, yy, 0xFFCCCCCC, false);
                    yy += 10;
                }
                if (!c.oldItem().equals(c.newItem())) {
                    ctx.drawText(textRenderer, "Item  " + c.oldItem() + " → " + c.newItem(),
                            PAD + 8, yy, 0xFFCCCCCC, false);
                    yy += 10;
                }
                if (!c.oldOwner().equals(c.newOwner())) {
                    ctx.drawText(textRenderer, "Owner  " + c.oldOwner() + " → " + c.newOwner(),
                            PAD + 8, yy, 0xFFCCCCCC, false);
                    yy += 10;
                }
                if (!c.oldAction().equals(c.newAction()) || !c.oldStatus().equals(c.newStatus())) {
                    ctx.drawText(textRenderer, "Action  " + c.oldAction() + " → " + c.newAction()
                                    + "  ·  " + c.oldStatus() + " → " + c.newStatus(),
                            PAD + 8, yy, 0xFF999999, false);
                }
            }

            y += BLOCK_H;
        }

        super.render(ctx, mouseX, mouseY, delta);
    }
}