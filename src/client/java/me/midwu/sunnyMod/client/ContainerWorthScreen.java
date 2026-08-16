package me.midwu.sunnyMod.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scrollable F7 chest-worth breakdown.
 *
 * One action button per row: Warp (and SignFinder search after teleport when available).
 * Qty column shows container count, or "have | shopSpace" when the shop cannot take everything.
 * Hovering the qty shows stacks + remainder in a vanilla-style tooltip.
 */
public class ContainerWorthScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H   = 48;
    private static final int FOOTER_H   = 28;
    private static final int PAD        = 12;
    private static final int WARP_BTN_W = 110;
    private static final int STACK_SIZE = 64;

    /** Ticks to wait after /warp before running findsign (chunk load). */
    private static final int FIND_DELAY_TICKS = 40;

    private final List<ContainerWorthHud.Entry> entries;
    private final double total;
    private final int pricedStacks;
    private final int unpricedStacks;
    private final boolean signFinderLoaded;

    private int scrollOffset = 0;
    private int maxScroll    = 0;

    /** Hover hit-boxes for qty tooltips: [x0,y0,x1,y1] per visible row index. */
    private final List<int[]> qtyHitBoxes = new ArrayList<>();
    private final List<List<Text>> qtyTooltips = new ArrayList<>();

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
        int warpX = this.width - PAD - WARP_BTN_W;

        for (int i = scrollOffset; i < end; i++) {
            ContainerWorthHud.Entry e = entries.get(i);
            int rowY = listTop + (i - scrollOffset) * ROW_HEIGHT;

            String warpCmd = normalizeWarp(e.warp);
            if (warpCmd.isEmpty() && !signFinderLoaded) continue;

            String label;
            if (!warpCmd.isEmpty()) {
                label = warpCmd.length() > 14 ? warpCmd.substring(0, 13) + "…" : warpCmd;
            } else {
                label = "Find";
            }

            final String cmd = warpCmd;
            final String itemName = e.name;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> onWarpOrFind(cmd, itemName))
                    .dimensions(warpX, rowY, WARP_BTN_W, 20)
                    .build());
        }
    }

    private void onWarpOrFind(String warpCmd, String itemName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler net = mc.getNetworkHandler();

        if (warpCmd != null && !warpCmd.isEmpty() && net != null) {
            String cmd = warpCmd.startsWith("/") ? warpCmd.substring(1) : warpCmd;
            net.sendChatCommand(cmd);
            if (signFinderLoaded) {
                // After teleport, run a whole-word regex search so "Stick" ≠ "Sticky Piston"
                PendingFindsign.schedule(buildFindsignCommand(itemName), FIND_DELAY_TICKS);
            }
            close();
            return;
        }

        // No warp — just find nearby
        if (signFinderLoaded) {
            runFindsignNow(buildFindsignCommand(itemName));
            close();
        }
    }

    /**
     * SignFinder text search is a substring match ("Stick" hits "Sticky Piston").
     * Use regex mode with word boundaries for an exact item-name token match.
     */
    static String buildFindsignCommand(String itemName) {
        // SignFinder: /findsign regex <pattern> — pattern must be ONE brigadier
        // argument. Multi-word names (e.g. "Netherite Ingot") require quotes or
        // the parser stops at the first space:
        //   Expected whitespace to end one argument ... at ...ign regex
        // Docs example: /findsign regex "chest|storage" 100
        //
        // Text mode is substring ("Stick" hits "Sticky Piston"); regex with
        // word boundaries avoids that. \Q..\E keeps the name literal.
        String safe = itemName.replace("\\", "\\\\").replace("\"", "");
        String pattern = "(?i)\\b\\Q" + safe + "\\E\\b";
        return "findsign regex \"" + pattern + "\"";
    }

    static void runFindsignNow(String commandWithoutSlash) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        // Prefer Fabric's client-command executor when present
        try {
            Class<?> internals = Class.forName(
                    "net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
            var method = internals.getMethod("executeCommand", String.class);
            Object ok = method.invoke(null, commandWithoutSlash);
            if (ok instanceof Boolean b && b) return;
        } catch (Throwable ignored) {
            // fall through
        }

        // Fallback: open chat pre-filled (user presses Enter)
        mc.setScreen(new ChatScreen("/" + commandWithoutSlash, false));
    }

    private static String normalizeWarp(String warp) {
        if (warp == null || warp.isBlank()) return "";
        String w = warp.trim();
        if (w.startsWith("/warp ")) return w;
        if (w.startsWith("warp ")) return "/" + w;
        if (w.startsWith("/")) return w;
        return "/warp " + w;
    }

    /** Parse shop buy-space from Stock/Space column; -1 if unknown. */
    static int parseShopSpace(String stockSpace) {
        if (stockSpace == null || stockSpace.isBlank()) return -1;
        try {
            String s = stockSpace.trim().replace(",", "");
            // Sometimes "15/3456" style — take the first number as remaining space
            if (s.contains("/")) s = s.split("/")[0].trim();
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "300" if shop can take all (or space unknown); "300 | 150" if limited. */
    static String formatQty(int containerCount, int shopSpace) {
        if (shopSpace < 0 || shopSpace >= containerCount) {
            return String.format(Locale.US, "%,d", containerCount);
        }
        return String.format(Locale.US, "%,d | %,d", containerCount, shopSpace);
    }

    static List<Text> qtyTooltip(int containerCount, int shopSpace, String itemName) {
        List<Text> lines = new ArrayList<>();
        int stacks = containerCount / STACK_SIZE;
        int rem = containerCount % STACK_SIZE;
        if (stacks > 0 && rem > 0) {
            lines.add(Text.literal(String.format(Locale.US,
                    "%,d = %d stack(s) + %d", containerCount, stacks, rem)));
        } else if (stacks > 0) {
            lines.add(Text.literal(String.format(Locale.US,
                    "%,d = %d stack(s)", containerCount, stacks)));
        } else {
            lines.add(Text.literal(String.format(Locale.US, "%,d item(s)", containerCount)));
        }

        if (shopSpace >= 0) {
            if (shopSpace >= containerCount) {
                lines.add(Text.literal("Shop can buy all (" +
                        String.format(Locale.US, "%,d", shopSpace) + " space)"));
            } else {
                lines.add(Text.literal("Shop space: " +
                        String.format(Locale.US, "%,d", shopSpace) +
                        " — can only take part of the chest"));
                int leftover = containerCount - shopSpace;
                lines.add(Text.literal(String.format(Locale.US,
                        "Leftover if sold here: %,d", leftover)));
            }
        } else if (itemName != null) {
            lines.add(Text.literal("Shop buy-space unknown"));
        }
        return lines;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        qtyHitBoxes.clear();
        qtyTooltips.clear();

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
        int qtyX  = Math.min(210, this.width / 4);
        int unitX = Math.min(300, this.width / 3 + 20);
        int subX  = Math.min(390, this.width / 2 + 20);
        int warpX = this.width - PAD - WARP_BTN_W;

        ctx.drawText(textRenderer, "Item", nameX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Qty", qtyX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Unit $", unitX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Total $", subX, listTop - 12, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, signFinderLoaded ? "Warp+Find" : "Warp",
                warpX, listTop - 12, 0xFFAAAAAA, false);

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

            int shopSpace = parseShopSpace(e.stockSpace);
            String qtyStr = formatQty(e.count, shopSpace);
            // Dim the "| space" part feel: use gold if capped
            int qtyColor = (shopSpace >= 0 && shopSpace < e.count) ? 0xFFFFAA55 : color;
            ctx.drawText(textRenderer, qtyStr, qtyX, rowY, qtyColor, false);

            int qtyW = textRenderer.getWidth(qtyStr);
            qtyHitBoxes.add(new int[]{qtyX, rowY - 2, qtyX + qtyW + 4, rowY + 12});
            qtyTooltips.add(qtyTooltip(e.count, shopSpace, e.name));

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

        // Vanilla-style tooltip when hovering qty
        for (int h = 0; h < qtyHitBoxes.size(); h++) {
            int[] box = qtyHitBoxes.get(h);
            if (mouseX >= box[0] && mouseX <= box[2] && mouseY >= box[1] && mouseY <= box[3]) {
                ctx.drawTooltip(textRenderer, qtyTooltips.get(h), mouseX, mouseY);
                break;
            }
        }

        if (maxScroll > 0) {
            String scrollHint = "Scroll " + (scrollOffset + 1) + "–" + end + " / " + entries.size();
            ctx.drawText(textRenderer, scrollHint, PAD, this.height - FOOTER_H + 8, 0xFF888888, false);
        }
        if (signFinderLoaded) {
            ctx.drawText(textRenderer, "Warp runs findsign after teleport",
                    PAD + 140, this.height - FOOTER_H + 8, 0xFF666666, false);
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

    // ── Deferred findsign after warp ─────────────────────────────────────────

    /**
     * Tiny tick-driven scheduler so findsign runs after the server has teleported
     * us and nearby chunks/signs are likely loaded.
     */
    public static final class PendingFindsign {
        private static String command;
        private static int ticksLeft;

        public static void schedule(String commandWithoutSlash, int delayTicks) {
            command = commandWithoutSlash;
            ticksLeft = Math.max(1, delayTicks);
        }

        public static void tick() {
            if (command == null || ticksLeft <= 0) return;
            ticksLeft--;
            if (ticksLeft == 0) {
                String cmd = command;
                command = null;
                runFindsignNow(cmd);
            }
        }
    }
}