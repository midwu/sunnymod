package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * F8 profit finder UI.
 * Top modes: Flips | Self-flip | Ignore lists
 * Ignore sub-tabs: Players | Warps | Items
 */
public class ProfitScreen extends Screen {

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H = 56;
    private static final int FOOTER_H = 28;
    private static final int PAD = 12;

    public enum Mode {
        FLIPS("Flips"),
        SELF("Self-flip"),
        UPDATE("Update"),
        IGNORE("Ignore lists");

        final String label;
        Mode(String label) { this.label = label; }
    }

    /** Default OFF — matches Python max-age unset. */
    static boolean ageFilterEnabled = false;
    static double ageFilterHours = 24.0;
    static Mode mode = Mode.FLIPS;
    static ProfitFinder.IgnoreKind ignoreKind = ProfitFinder.IgnoreKind.PLAYERS;

    /** Skip warps rescanned in the last 15 min in the Update tab — you just checked, nothing to gain yet. */
    static boolean hideRecentlyScanned = true;

    private ProfitFinder.Result result;
    private List<String> ignoreEntries = List.of();
    private List<ProfitFinder.WarpSummary> updatePriorities = List.of();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private TextFieldWidget addField;

    public ProfitScreen(ProfitFinder.Result result) {
        super(Text.literal("Profit finder"));
        this.result = result != null
                ? result
                : new ProfitFinder.Result(List.of(), 0, 0, 0, "empty", false);
        if (mode == Mode.IGNORE) {
            reloadIgnoreEntries();
        } else if (mode == Mode.UPDATE) {
            reloadUpdatePriorities();
        }
    }

    static ProfitFinder.Result runFind() {
        double age = ageFilterEnabled ? ageFilterHours : -1;
        if (mode == Mode.SELF) {
            return ProfitFinder.findSelfFlips(0.01, 500, age);
        }
        return ProfitFinder.findFlips(0.01, 0.0, 500, age);
    }

    private void reloadIgnoreEntries() {
        Set<String> set = ProfitFinder.loadIgnore(ignoreKind);
        ignoreEntries = new ArrayList<>(set);
        scrollOffset = 0;
    }

    private void reloadUpdatePriorities() {
        updatePriorities = ProfitFinder.findUpdatePrioritiesByWarp(hideRecentlyScanned);
        scrollOffset = 0;
    }

    private void switchMode(Mode m) {
        mode = m;
        scrollOffset = 0;
        if (m == Mode.IGNORE) {
            reloadIgnoreEntries();
            if (client != null) client.setScreen(new ProfitScreen(result));
        } else if (m == Mode.UPDATE) {
            reloadUpdatePriorities();
            if (client != null) client.setScreen(new ProfitScreen(result));
        } else {
            if (client != null) client.setScreen(new ProfitScreen(runFind()));
        }
    }

    @Override
    protected void init() {
        // Mode buttons — top left
        int bx = PAD;
        int bw = 72;
        for (Mode m : Mode.values()) {
            Mode captured = m;
            boolean on = mode == m;
            ButtonWidget btn = ButtonWidget.builder(
                    Text.literal(on ? "[" + m.label + "]" : m.label),
                    b -> switchMode(captured)
            ).dimensions(bx, 6, bw, 18).build();
            addDrawableChild(btn);
            bx += bw + 4;
        }

        if (mode == Mode.IGNORE) {
            // Sub-tabs: Players / Warps / Items
            int sx = PAD;
            for (ProfitFinder.IgnoreKind k : ProfitFinder.IgnoreKind.values()) {
                ProfitFinder.IgnoreKind captured = k;
                boolean on = ignoreKind == k;
                addDrawableChild(ButtonWidget.builder(
                        Text.literal(on ? "[" + k.label + "]" : k.label),
                        b -> {
                            ignoreKind = captured;
                            reloadIgnoreEntries();
                            if (client != null) client.setScreen(new ProfitScreen(result));
                        }
                ).dimensions(sx, 28, 64, 16).build());
                sx += 68;
            }

            addField = new TextFieldWidget(textRenderer, PAD, this.height - 24, 160, 16,
                    Text.literal("Add entry"));
            addField.setMaxLength(64);
            addField.setPlaceholder(Text.literal("Type name, Enter to add"));
            addDrawableChild(addField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> tryAddIgnore())
                    .dimensions(PAD + 166, this.height - 24, 40, 16).build());
        } else if (mode == Mode.UPDATE) {
            // Hide-recently-scanned toggle + refresh for the update-priority tab
            String label = hideRecentlyScanned ? "Hide <15m: On" : "Hide <15m: Off";
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                        hideRecentlyScanned = !hideRecentlyScanned;
                        if (client != null) client.setScreen(new ProfitScreen(result));
                    }).dimensions(this.width - PAD - 170, 6, 85, 18)
                    .tooltip(Tooltip.of(Text.literal(
                            "Skip shops you already rescanned in the last 15 minutes.")))
                    .build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                if (client != null) client.setScreen(new ProfitScreen(result));
            }).dimensions(this.width - PAD - 80, 6, 70, 18).build());
        } else {
            // Age toggle + refresh for flip modes
            String ageLabel = ageFilterEnabled
                    ? String.format(Locale.US, "Age ≤%.0fh", ageFilterHours)
                    : "Age: off";
            addDrawableChild(ButtonWidget.builder(Text.literal(ageLabel), b -> {
                        ageFilterEnabled = !ageFilterEnabled;
                        if (client != null) client.setScreen(new ProfitScreen(runFind()));
                    }).dimensions(this.width - PAD - 170, 6, 85, 18)
                    .tooltip(Tooltip.of(Text.literal(
                            "Drop listings older than " + (int) ageFilterHours
                                    + "h vs newest timestamp. Default off.")))
                    .build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                if (client != null) client.setScreen(new ProfitScreen(runFind()));
            }).dimensions(this.width - PAD - 80, 6, 70, 18).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(this.width - PAD - 80, this.height - 24, 70, 18).build());

        int listBottom = this.height - FOOTER_H;
        int listTop = HEADER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int size = switch (mode) {
            case IGNORE -> ignoreEntries.size();
            case UPDATE -> updatePriorities.size();
            default -> result.trades.size();
        };
        maxScroll = Math.max(0, size - visibleRows);
    }

    private void tryAddIgnore() {
        if (addField == null) return;
        String v = addField.getText();
        if (v == null || v.isBlank()) return;
        if (ProfitFinder.addIgnore(ignoreKind, v.trim())) {
            addField.setText("");
            reloadIgnoreEntries();
            if (client != null) client.setScreen(new ProfitScreen(result));
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (mode == Mode.IGNORE && addField != null && addField.isFocused()
                && (input.key() == 257 || input.key() == 335)) { // Enter
            tryAddIgnore();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (vertical < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (mode == Mode.IGNORE && click.button() == 0) {
            int listTop = HEADER_H;
            int listBottom = this.height - FOOTER_H;
            int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
            for (int i = 0; i < visibleRows; i++) {
                int idx = scrollOffset + i;
                if (idx >= ignoreEntries.size()) break;
                int rowY = listTop + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= this.width - PAD - 70 && mouseX < this.width - PAD) {
                    String entry = ignoreEntries.get(idx);
                    ProfitFinder.removeIgnore(ignoreKind, entry);
                    reloadIgnoreEntries();
                    if (client != null) client.setScreen(new ProfitScreen(result));
                    return true;
                }
            }
        }
        if (mode == Mode.UPDATE && click.button() == 0) {
            int listTop = HEADER_H + 4;
            int listBottom = this.height - FOOTER_H;
            int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
            int rowStart = listTop + 12;
            for (int i = 0; i < visibleRows; i++) {
                int idx = scrollOffset + i;
                if (idx >= updatePriorities.size()) break;
                int rowY = rowStart + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= PAD && mouseX < this.width - PAD) {
                    runWarpCommand(updatePriorities.get(idx).warp);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /** Re-issues a saved warp/spawn command string (e.g. "/warp foo") exactly as it was recorded. */
    private void runWarpCommand(String warp) {
        if (warp == null || warp.isBlank() || client == null || client.player == null) return;
        String cmd = warp.startsWith("/") ? warp.substring(1) : warp;
        client.player.networkHandler.sendChatCommand(cmd);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        if (mode == Mode.IGNORE) {
            renderIgnore(ctx, mouseX, mouseY);
        } else if (mode == Mode.UPDATE) {
            renderUpdatePriorities(ctx, mouseX, mouseY);
        } else {
            renderTrades(ctx, mouseX, mouseY);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderTrades(DrawContext ctx, int mouseX, int mouseY) {
        String title = mode == Mode.SELF ? "Self-flip (same owner buy > sell)" : "Shop → Shop flips";
        ctx.drawText(textRenderer, title, PAD, 28, 0xFFCCCCCC, false);
        ctx.drawText(textRenderer, result.loadSummary, PAD, 40, 0xFF888888, false);

        String money;
        if (mode == Mode.SELF) {
            money = String.format(Locale.US, "%d owner mistakes · best margin $%.2f/ea",
                    result.trades.size(),
                    result.trades.isEmpty() ? 0 : result.trades.getFirst().profitPerItem);
        } else {
            money = String.format(Locale.US,
                    "Σ profit $%,.0f  ·  capital $%,.0f  ·  %d shown",
                    result.totalProfit, result.totalCapital, result.trades.size());
        }
        // drawn in header area — secondary line already used; put under columns
        int listTop = HEADER_H + 4;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, result.trades.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        ctx.drawText(textRenderer, money, PAD, listTop - 12, 0xFF88FF88, false);

        int hy = listTop;
        ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
        if (mode == Mode.SELF) {
            ctx.drawText(textRenderer, "Owner", PAD + 110, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Sells@", PAD + 200, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Buys@", PAD + 270, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Margin", this.width - PAD - 100, hy, 0xFF666666, false);
        } else {
            ctx.drawText(textRenderer, "Qty", PAD + 120, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Buy@", PAD + 160, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Sell@", PAD + 230, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Profit", this.width - PAD - 160, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Warps", this.width - PAD - 90, hy, 0xFF666666, false);
        }

        int rowStart = listTop + 12;
        if (result.trades.isEmpty()) {
            String empty = mode == Mode.SELF
                    ? "No same-owner buy>sell mistakes found."
                    : "No profitable flips (check shop_data / ignore lists).";
            ctx.drawText(textRenderer, empty, PAD, rowStart + 8, 0xFF888888, false);
        }

        ProfitFinder.Trade hovered = null;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= result.trades.size()) break;
            ProfitFinder.Trade t = result.trades.get(idx);
            int rowY = rowStart + i * ROW_HEIGHT;

            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= PAD && mouseX < this.width - PAD;
            if (hover) {
                ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
                hovered = t;
            }

            String name = t.item.length() > 16 ? t.item.substring(0, 15) + "…" : t.item;
            ctx.drawText(textRenderer, name, PAD, rowY + 2, 0xFFFFFFAA, false);

            if (mode == Mode.SELF) {
                String owner = t.seller.length() > 12 ? t.seller.substring(0, 11) + "…" : t.seller;
                ctx.drawText(textRenderer, owner, PAD + 110, rowY + 2, 0xFFCCCCCC, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f", t.sellPrice),
                        PAD + 200, rowY + 2, 0xFFFF8888, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f", t.buyPrice),
                        PAD + 270, rowY + 2, 0xFF88FF88, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f/ea", t.profitPerItem),
                        this.width - PAD - 100, rowY + 2, 0xFF55FF55, false);
                ctx.drawText(textRenderer, shortWarp(t.sellerWarp), PAD, rowY + 11, 0xFF666666, false);
            } else {
                ctx.drawText(textRenderer, String.format(Locale.US, "%,d", t.quantity),
                        PAD + 120, rowY + 2, 0xFFFFFFFF, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f", t.sellPrice),
                        PAD + 160, rowY + 2, 0xFFFF8888, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f", t.buyPrice),
                        PAD + 230, rowY + 2, 0xFF88FF88, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", t.totalProfit),
                        this.width - PAD - 160, rowY + 2, 0xFF55FF55, false);
                ctx.drawText(textRenderer, shortWarp(t.sellerWarp) + "→" + shortWarp(t.buyerWarp),
                        this.width - PAD - 90, rowY + 2, 0xFFAAAAFF, false);
                ctx.drawText(textRenderer, t.seller + " → " + t.buyer, PAD, rowY + 11, 0xFF666666, false);
            }
        }

        if (hovered != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hovered.item));
            if (hovered.selfFlip) {
                tip.add(Text.literal(hovered.seller + " sells @ $"
                        + String.format(Locale.US, "%.2f", hovered.sellPrice)
                        + " and buys @ $"
                        + String.format(Locale.US, "%.2f", hovered.buyPrice)));
                tip.add(Text.literal(String.format(Locale.US, "Margin $%.2f / item (unlimited — grind it)",
                        hovered.profitPerItem)));
                tip.add(Text.literal("Warp: " + (hovered.sellerWarp.isEmpty() ? "(none)" : hovered.sellerWarp)));
            } else {
                tip.add(Text.literal(String.format(Locale.US, "Buy %d @ $%.2f from %s",
                        hovered.quantity, hovered.sellPrice, hovered.seller)));
                tip.add(Text.literal(String.format(Locale.US, "Sell @ $%.2f to %s",
                        hovered.buyPrice, hovered.buyer)));
                tip.add(Text.literal(String.format(Locale.US, "Edge $%.2f/ea · total $%,.2f · capital $%,.2f",
                        hovered.profitPerItem, hovered.totalProfit, hovered.capital)));
                tip.add(Text.literal("Warp buy: " + (hovered.sellerWarp.isEmpty() ? "(none)" : hovered.sellerWarp)));
                tip.add(Text.literal("Warp sell: " + (hovered.buyerWarp.isEmpty() ? "(none)" : hovered.buyerWarp)));
            }
            ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }

        ctx.drawText(textRenderer, "F8 · scroll", PAD, this.height - 18, 0xFF666666, false);
    }

    private void renderIgnore(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawText(textRenderer,
                "Ignore " + ignoreKind.label + " — excluded from Flips & Self-flip",
                PAD, 48, 0xFFAAAAAA, false);

        int listTop = HEADER_H + 8;
        int listBottom = this.height - FOOTER_H - 4;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, ignoreEntries.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        ctx.drawText(textRenderer,
                String.format(Locale.US, "%d entries · click Remove · files in config/sunnyMod/",
                        ignoreEntries.size()),
                PAD, listTop - 12, 0xFF888888, false);

        if (ignoreEntries.isEmpty()) {
            ctx.drawText(textRenderer, "List empty — add names below.", PAD, listTop + 8, 0xFF888888, false);
        }

        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= ignoreEntries.size()) break;
            String entry = ignoreEntries.get(idx);
            int rowY = listTop + i * ROW_HEIGHT;
            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= PAD && mouseX < this.width - PAD;
            if (hover) {
                ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            }
            ctx.drawText(textRenderer, entry, PAD, rowY + 6, 0xFFFFFFFF, false);
            int remColor = (hover && mouseX >= this.width - PAD - 70) ? 0xFFFF5555 : 0xFFAA6666;
            ctx.drawText(textRenderer, "Remove", this.width - PAD - 55, rowY + 6, remColor, false);
        }
    }

    private void renderUpdatePriorities(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawText(textRenderer, "Warps to re-check — out of stock/space, ranked by payoff", PAD, 28, 0xFFCCCCCC, false);
        ctx.drawText(textRenderer,
                updatePriorities.size() + " warps · click a row to warp there",
                PAD, 40, 0xFF888888, false);

        int listTop = HEADER_H + 4;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, updatePriorities.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        int hy = listTop;
        ctx.drawText(textRenderer, "Warp", PAD, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Items", PAD + 150, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Total edge", PAD + 210, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Age", PAD + 300, hy, 0xFF666666, false);

        int rowStart = listTop + 12;
        if (updatePriorities.isEmpty()) {
            ctx.drawText(textRenderer, "Nothing stale right now — all tracked shops are Active.",
                    PAD, rowStart + 8, 0xFF888888, false);
        }

        ProfitFinder.WarpSummary hovered = null;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= updatePriorities.size()) break;
            ProfitFinder.WarpSummary w = updatePriorities.get(idx);
            int rowY = rowStart + i * ROW_HEIGHT;

            boolean hasWarp = !w.warp.isBlank();
            boolean hover = hasWarp && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= PAD && mouseX < this.width - PAD;
            if (hover) {
                ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
                hovered = w;
            }

            String warpName = hasWarp ? shortWarp(w.warp) : "(no warp saved)";
            int nameColor = hasWarp ? 0xFFFFFFAA : 0xFF777766;
            ctx.drawText(textRenderer, warpName, PAD, rowY + 2, nameColor, false);
            ctx.drawText(textRenderer, String.valueOf(w.items.size()), PAD + 150, rowY + 2, 0xFFCCCCCC, false);
            ctx.drawText(textRenderer, w.totalPotentialValue > 0
                            ? String.format(Locale.US, "+$%.2f", w.totalPotentialValue) : "—",
                    PAD + 210, rowY + 2, w.totalPotentialValue > 0 ? 0xFF55FF55 : 0xFF666666, false);
            ctx.drawText(textRenderer, String.format(Locale.US, "%.0fh", w.maxAgeHours),
                    PAD + 300, rowY + 2, 0xFFAAAAAA, false);

            String preview = w.items.stream()
                    .map(p -> p.item.length() > 12 ? p.item.substring(0, 11) + "…" : p.item)
                    .limit(3)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (w.items.size() > 3) preview += ", …";
            String sub = preview + (w.maxNoChangeStreak > 0 ? " · unchanged x" + w.maxNoChangeStreak : "");
            ctx.drawText(textRenderer, sub, PAD, rowY + 11, 0xFF666666, false);
        }

        if (hovered != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hovered.warp.isEmpty() ? "(no warp saved)" : hovered.warp));
            tip.add(Text.literal(String.format(Locale.US, "%d stale item%s · total edge +$%.2f",
                    hovered.items.size(), hovered.items.size() == 1 ? "" : "s", hovered.totalPotentialValue)));
            tip.add(Text.literal(String.format(Locale.US, "Oldest scan %.0fh ago · worst streak %d unchanged rescans",
                    hovered.maxAgeHours, hovered.maxNoChangeStreak)));
            int shown = 0;
            for (ProfitFinder.WarpPriority p : hovered.items) {
                if (shown >= 6) {
                    tip.add(Text.literal("…and " + (hovered.items.size() - shown) + " more"));
                    break;
                }
                tip.add(Text.literal("• " + p.item + " (" + p.action + ", " + p.status + ") "
                        + (p.potentialValue > 0
                        ? String.format(Locale.US, "+$%.2f/ea", p.potentialValue)
                        : "no current match")));
                shown++;
            }
            tip.add(Text.literal("Click to warp: " + (hovered.warp.isEmpty() ? "(no warp saved)" : hovered.warp)));
            ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }

        ctx.drawText(textRenderer, "F8 · scroll", PAD, this.height - 18, 0xFF666666, false);
    }

    private static String shortWarp(String w) {
        if (w == null || w.isBlank()) return "—";
        String s = w.startsWith("/warp ") ? w.substring(6) : w;
        if (s.startsWith("warp ")) s = s.substring(5);
        return s.length() > 8 ? s.substring(0, 7) + "…" : s;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}