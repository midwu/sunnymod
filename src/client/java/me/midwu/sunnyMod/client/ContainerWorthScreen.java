package me.midwu.sunnyMod.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * Full-screen (scrollable) breakdown of F7 chest-worth results.
 *
 * Layout per row:
 *   Item name xCount   $unit  $subtotal   [Warp] [Find]
 *
 * Warp runs /warp … on the server.
 * Find pre-fills a SignFinder search (/findsign …) when that mod is loaded,
 * otherwise shows the stored shop coordinates (and distance if parseable).
 */
public class ContainerWorthScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H   = 48;
    private static final int FOOTER_H   = 28;
    private static final int PAD        = 12;
    private static final int WARP_BTN_W = 100;
    private static final int FIND_BTN_W = 50;

    private final List<ContainerWorthHud.Entry> entries;
    private final double total;
    private final int pricedStacks;
    private final int unpricedStacks;
    private final boolean signFinderLoaded;

    private int scrollOffset = 0; // in rows
    private int maxScroll    = 0;

    public ContainerWorthScreen(List<ContainerWorthHud.Entry> entries,
                                double total, int pricedStacks, int unpricedStacks) {
        super(Text.literal("Chest Worth"));
        this.entries = entries != null ? List.copyOf(entries) : List.of();
        this.total = total;
        this.pricedStacks = pricedStacks;
        this.unpricedStacks = unpricedStacks;
        this.signFinderLoaded = FabricLoader.getInstance().isModLoaded("signfinder");
    }

    @Override
    protected void init() {
        clearChildren();
        scrollOffset = 0;

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, entries.size() - visibleRows);

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
                .build());

        rebuildRowButtons(visibleRows);
    }

    private void rebuildRowButtons(int visibleRows) {
        clearChildren();
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
                .build());

        int listTop = HEADER_H;
        int end = Math.min(entries.size(), scrollOffset + visibleRows);
        int findX = this.width - PAD - FIND_BTN_W;
        int warpX = findX - 4 - WARP_BTN_W;

        for (int i = scrollOffset; i < end; i++) {
            ContainerWorthHud.Entry e = entries.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;

            String warpCmd = normalizeWarp(e.warp);
            if (!warpCmd.isEmpty()) {
                String label = warpCmd.length() > 14 ? warpCmd.substring(0, 13) + "…" : warpCmd;
                final String cmd = warpCmd;
                addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> runWarp(cmd))
                        .dimensions(warpX, rowY, WARP_BTN_W, 20)
                        .build());
            }

            final ContainerWorthHud.Entry entry = e;
            String findLabel = signFinderLoaded ? "Find" : "Pos";
            addDrawableChild(ButtonWidget.builder(Text.literal(findLabel), b -> onFind(entry))
                    .dimensions(findX, rowY, FIND_BTN_W, 20)
                    .build());
        }
    }

    private void runWarp(String warpCmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler net = mc.getNetworkHandler();
        if (net == null) return;
        String cmd = warpCmd.startsWith("/") ? warpCmd.substring(1) : warpCmd;
        net.sendChatCommand(cmd);
        close();
    }

    private void onFind(ContainerWorthHud.Entry e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (signFinderLoaded) {
            // Prefill chat with a SignFinder search for this item name.
            String query = e.name.contains(" ") ? "\"" + e.name + "\"" : e.name;
            close();
            mc.setScreen(new ChatScreen("/findsign " + query, false));
            return;
        }

        String loc = e.location;
        if (loc == null || loc.isBlank()) {
            mc.player.sendMessage(Text.literal(
                    "§e[ContainerReader] No coordinates stored for §f" + e.name +
                            "§e. Scan the shop sign to record them."), false);
            return;
        }

        BlockPos shopPos = parseLocation(loc);
        String msg;
        if (shopPos != null) {
            PlayerEntity p = mc.player;
            double dist = Math.sqrt(p.squaredDistanceTo(
                    shopPos.getX() + 0.5, shopPos.getY() + 0.5, shopPos.getZ() + 0.5));
            msg = String.format(Locale.US,
                    "§a[ContainerReader] §f%s §7shop at §f%d %d %d §7(§f%.0fm §7away) owner §f%s",
                    e.name, shopPos.getX(), shopPos.getY(), shopPos.getZ(), dist,
                    e.owner.isEmpty() ? "?" : e.owner);
        } else {
            msg = "§a[ContainerReader] §f" + e.name + " §7location: §f" + loc +
                    (e.owner.isEmpty() ? "" : " §7owner §f" + e.owner);
        }
        mc.player.sendMessage(Text.literal(msg), false);
    }

    private static BlockPos parseLocation(String loc) {
        try {
            String cleaned = loc.replace(',', ' ').trim();
            String[] parts = cleaned.split("\\s+");
            if (parts.length < 3) return null;
            int x = (int) Double.parseDouble(parts[0]);
            int y = (int) Double.parseDouble(parts[1]);
            int z = (int) Double.parseDouble(parts[2]);
            return new BlockPos(x, y, z);
        } catch (Exception e) {
            return null;
        }
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

        ctx.drawCenteredTextWithShadow(textRenderer, "Chest Worth", this.width / 2, 10, 0xFFFFD700);
        String totalLine = "Total: $" + String.format(Locale.US, "%,.2f", total) +
                "   (" + pricedStacks + " priced / " + unpricedStacks + " unpriced stacks, " +
                entries.size() + " items)";
        ctx.drawCenteredTextWithShadow(textRenderer, totalLine, this.width / 2, 24, 0xFFFFFFFF);

        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "No items to show",
                    this.width / 2, HEADER_H + 20, 0xFFAAAAAA);
            return;
        }

        int listTop = HEADER_H;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int end = Math.min(entries.size(), scrollOffset + visibleRows);

        int nameX = PAD;
        int qtyX  = Math.min(200, this.width / 4);
        int unitX = Math.min(270, this.width / 3);
        int subX  = Math.min(360, this.width / 2);
        int findX = this.width - PAD - FIND_BTN_W;
        int warpX = findX - 4 - WARP_BTN_W;

        ctx.drawText(textRenderer, "Item", nameX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Qty", qtyX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Unit $", unitX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Total $", subX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Warp", warpX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, signFinderLoaded ? "Find" : "Pos",
                findX, listTop - 12, 0xFFAAAAAA, false);

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
        }

        if (maxScroll > 0) {
            String scrollHint = "Scroll " + (scrollOffset + 1) + "–" + end + " / " + entries.size();
            ctx.drawText(textRenderer, scrollHint, PAD, this.height - FOOTER_H + 8, 0xFF888888, false);
        }

        if (signFinderLoaded) {
            ctx.drawText(textRenderer, "Find = SignFinder search",
                    PAD + 120, this.height - FOOTER_H + 8, 0xFF666666, false);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
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