package me.midwu.sunnyMod.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
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
            String tipText = !cmd.isEmpty()
                    ? cmd + (signFinderLoaded ? "  → then findsign" : "")
                    : (signFinderLoaded ? ("findsign \"" + itemName + "\"") : "");
            var btnBuilder = ButtonWidget.builder(Text.literal(label), b -> onWarpOrFind(cmd, itemName))
                    .dimensions(warpX, rowY, WARP_BTN_W, 20);
            if (!tipText.isEmpty()) {
                btnBuilder.tooltip(Tooltip.of(Text.literal(tipText)));
            }
            addDrawableChild(btnBuilder.build());
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
    /**
     * Build a SignFinder client command.
     *
     * SignFinder registers pattern/query with StringArgumentType.string() — NOT
     * greedyString(). That means:
     *   - unquoted values are a single "word" and may not contain ( ) \ etc.
     *   - multi-word values MUST be wrapped in double quotes
     *
     * Regex mode was abandoned: patterns like (?i)\b\QTuff\E\b contain
     * characters illegal in an unquoted string(), and ClientCommandInternals
     * often surfaces the command without quotes, which triggers:
     *   Expected whitespace to end one argument ... at ...ign regex
     *
     * Plain text search is substring-based ("Stick" can still match
     * "Sticky Piston") but is reliable. Multi-word names are quoted.
     */
    static String buildFindsignCommand(String itemName) {
        String safe = itemName == null ? "" : itemName.replace("\"", "").trim();
        if (safe.isEmpty()) return "findsign";
        // Always quote — works for both "Tuff" and "Netherite Ingot"
        // and is the form SignFinder's own help recommends for special text.
        return "findsign \"" + safe + "\"";
    }

    static void runFindsignNow(String commandWithoutSlash) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        System.out.println("[ContainerReader] findsign exec: " + commandWithoutSlash);

        // 1) Fabric client-command executor (keeps quotes if the impl is correct)
        try {
            Class<?> internals = Class.forName(
                    "net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
            var method = internals.getMethod("executeCommand", String.class);
            Object ok = method.invoke(null, commandWithoutSlash);
            if (ok instanceof Boolean b && b) {
                return;
            }
        } catch (Throwable t) {
            System.out.println("[ContainerReader] ClientCommandInternals failed: " + t);
        }

        // 2) Fallback: open chat pre-filled. User presses Enter.
        //    This path preserves quotes reliably.
        try {
            mc.setScreen(new ChatScreen("/" + commandWithoutSlash, false));
        } catch (Throwable t) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(
                        "§e[ContainerReader] Run manually: §f/" + commandWithoutSlash), false);
            }
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

    /** Parse shop buy-space from Stock/Space column; -1 if unknown. */
    static int parseShopSpace(String stockSpace) {
        return Container_reader.parseShopSpace(stockSpace);
    }

    /**
     * Qty column for one allocation leg:
     *   - fully covered by this leg alone: "300"
     *   - this leg takes part of the chest: "150 / 300"  (leg amount / chest total)
     *   - unsellable remainder: "50 / 300" in grey (handled by color)
     */
    static String formatQty(ContainerWorthHud.Entry e) {
        if (e.count == e.containerTotal) {
            return String.format(java.util.Locale.US, "%,d", e.count);
        }
        return String.format(java.util.Locale.US, "%,d / %,d", e.count, e.containerTotal);
    }

    static List<Text> qtyTooltip(ContainerWorthHud.Entry e) {
        List<Text> lines = new ArrayList<>();
        int stacks = e.count / STACK_SIZE;
        int rem = e.count % STACK_SIZE;
        if (stacks > 0 && rem > 0) {
            lines.add(Text.literal(String.format(java.util.Locale.US,
                    "This leg: %,d = %d stack(s) + %d", e.count, stacks, rem)));
        } else if (stacks > 0) {
            lines.add(Text.literal(String.format(java.util.Locale.US,
                    "This leg: %,d = %d stack(s)", e.count, stacks)));
        } else {
            lines.add(Text.literal(String.format(java.util.Locale.US,
                    "This leg: %,d item(s)", e.count)));
        }

        if (e.containerTotal != e.count) {
            lines.add(Text.literal(String.format(java.util.Locale.US,
                    "Chest total for %s: %,d", e.name, e.containerTotal)));
        }

        if (e.unitPrice == null) {
            lines.add(Text.literal("No more shops with buy-space — unsellable leftover"));
        } else if (e.shopSpace >= 0) {
            lines.add(Text.literal(String.format(java.util.Locale.US,
                    "Shop space: %,d @ %s", e.shopSpace,
                    e.owner.isEmpty() ? "?" : e.owner)));
            if (e.count < e.containerTotal) {
                lines.add(Text.literal("Further legs sell the rest at the next-best prices"));
            }
        } else {
            lines.add(Text.literal("Shop buy-space unknown — assumed can take this leg"));
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

            String qtyStr = formatQty(e);
            // Orange when this leg is only part of the chest total; grey if unsellable
            int qtyColor = (e.unitPrice == null) ? 0xFF888888
                    : (e.count < e.containerTotal) ? 0xFFFFAA55 : color;
            ctx.drawText(textRenderer, qtyStr, qtyX, rowY, qtyColor, false);

            int qtyW = textRenderer.getWidth(qtyStr);
            qtyHitBoxes.add(new int[]{qtyX, rowY - 2, qtyX + qtyW + 4, rowY + 12});
            qtyTooltips.add(qtyTooltip(e));

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