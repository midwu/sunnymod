package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ProfitScreen extends Screen {

    // --- CONFIGURABLE LAYOUT VARIABLES ---

    // Row and section heights
    private static final int ROW_HEIGHT = 22;       // Height of each row in the table
    private static final int HEADER_H = 56;         // Height of the header section
    private static final int FOOTER_H = 28;         // Height of the footer section
    private static final int PAD = 12;              // Padding from the edges

    // --- FLIPS PAGE VARIABLES ---
    private static final int FLIPS_ITEM_COL_WIDTH = 60;   // Width of the Item column in Flips mode
    private static final int FLIPS_PROFIT_COL_WIDTH = 140;  // Width of the Profit column in Flips mode
    private static final int FLIPS_WARPS_COL_WIDTH = 210; // Width of the Warps column in Flips mode
    private static final int FLIPS_WARP_BTN_W = 140;      // Width of warp buttons in Flips mode
    private static final int FLIPS_ITEM_NAME_MAX_CHARS = 12;    // Max characters for item names in Flips mode
    private static final int FLIPS_WARP_NAME_MAX_CHARS = 24;     // Max characters for warp names in Flips mode

    // --- SELF-FLIP PAGE VARIABLES ---
    private static final int SELF_ITEM_COL_WIDTH = 200;   // Width of the Item column in Self-Flip mode
    private static final int SELF_OWNER_COL_WIDTH = 280; // Width of the Owner column in Self-Flip mode
    private static final int SELF_PROFIT_COL_WIDTH = 240;  // Width of the Margin column in Self-Flip mode
    private static final int SELF_WARP_BTN_W = 180;      // Width of warp button in Self-Flip mode
    private static final int SELF_ITEM_NAME_MAX_CHARS = 24;    // Max characters for item names in Self-Flip mode
    private static final int SELF_OWNER_NAME_MAX_CHARS = 40;   // Max characters for owner names in Self-Flip mode
    private static final int SELF_WARP_NAME_MAX_CHARS = 24;     // Max characters for warp names in Self-Flip mode

    // --- UPDATE PAGE VARIABLES ---
    private static final int UPDATE_WARP_COL_WIDTH = 140;  // Width of the Warp column in Update mode
    private static final int UPDATE_ITEMS_COL_WIDTH = 120;  // Width of the Items column in Update mode
    private static final int UPDATE_EDGE_COL_WIDTH = 140;   // Width of the Total Edge column in Update mode
    private static final int UPDATE_AGE_COL_WIDTH = 120;   // Width of the Age column in Update mode
    private static final int UPDATE_WARP_BTN_W = 180;      // Width of warp button in Update mode
    private static final int UPDATE_WARP_NAME_MAX_CHARS = 24;     // Max characters for warp names in Update mode

    // --- IGNORE TAB VARIABLES ---
    private static final int IGNORE_TEXT_VERTICAL_SPACING = 12; // Vertical spacing between text lines in Ignore tab
    private static final int IGNORE_COL_WIDTH = 120; // Width for each column in Ignore tab
    private static final int IGNORE_COL_1_X = PAD; // X position for first column (Items)
    private static final int IGNORE_COL_2_X = PAD + IGNORE_COL_WIDTH + 20; // X position for second column (Players)
    private static final int IGNORE_COL_3_X = PAD + (IGNORE_COL_WIDTH * 2) + 40; // X position for third column (Warps)

    // --- END CONFIGURABLE VARIABLES ---

    public enum Mode {
        FLIPS("Flips"),
        SELF("Self-flip"),
        UPDATE("Update"),
        IGNORE("Ignore lists");

        final String label;
        Mode(String label) { this.label = label; }
    }

    static boolean ageFilterEnabled = false;
    static double ageFilterHours = 24.0;
    static Mode mode = Mode.FLIPS;
    static ProfitFinder.IgnoreKind ignoreKind = ProfitFinder.IgnoreKind.ITEMS; // Default to Items
    static boolean hideRecentlyScanned = true;

    private ProfitFinder.Result result;
    private List<String> ignoreItems = List.of();
    private List<String> ignorePlayers = List.of();
    private List<String> ignoreWarps = List.of();
    private List<ProfitFinder.WarpSummary> updatePriorities = List.of();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int itemsScrollOffset = 0;
    private int playersScrollOffset = 0;
    private int warpsScrollOffset = 0;
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
        ignoreItems = new ArrayList<>(ProfitFinder.loadIgnore(ProfitFinder.IgnoreKind.ITEMS));
        ignorePlayers = new ArrayList<>(ProfitFinder.loadIgnore(ProfitFinder.IgnoreKind.PLAYERS));
        ignoreWarps = new ArrayList<>(ProfitFinder.loadIgnore(ProfitFinder.IgnoreKind.WARPS));
        scrollOffset = 0;
        itemsScrollOffset = 0;
        playersScrollOffset = 0;
        warpsScrollOffset = 0;
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
            // Add column selection buttons at the bottom
            int buttonY = this.height - FOOTER_H + 4;

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(ignoreKind == ProfitFinder.IgnoreKind.ITEMS ? "[Items]" : "Items"),
                            b -> {
                                ignoreKind = ProfitFinder.IgnoreKind.ITEMS;
                            })
                    .dimensions(PAD, buttonY, 70, 16)
                    .build());

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(ignoreKind == ProfitFinder.IgnoreKind.PLAYERS ? "[Players]" : "Players"),
                            b -> {
                                ignoreKind = ProfitFinder.IgnoreKind.PLAYERS;
                            })
                    .dimensions(PAD + 80, buttonY, 70, 16)
                    .build());

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(ignoreKind == ProfitFinder.IgnoreKind.WARPS ? "[Warps]" : "Warps"),
                            b -> {
                                ignoreKind = ProfitFinder.IgnoreKind.WARPS;
                            })
                    .dimensions(PAD + 160, buttonY, 70, 16)
                    .build());

            addField = new TextFieldWidget(textRenderer, PAD + 240, this.height - FOOTER_H + 4, 160, 16,
                    Text.literal("Add entry"));
            addField.setMaxLength(64);
            addField.setPlaceholder(Text.literal("Type name, Enter to add"));
            addDrawableChild(addField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> tryAddIgnore())
                    .dimensions(PAD + 406, this.height - FOOTER_H + 4, 40, 16).build());
        } else if (mode == Mode.UPDATE) {
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
            case IGNORE -> Math.max(ignoreItems.size(), Math.max(ignorePlayers.size(), ignoreWarps.size()));
            case UPDATE -> updatePriorities.size();
            default -> result.trades.size();
        };
        maxScroll = Math.max(0, size - visibleRows);
    }

    private void tryAddIgnore() {
        if (addField == null) return;
        String v = addField.getText();
        if (v == null || v.isBlank()) return;

        // Add to the currently selected ignore kind
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
        if (mode == Mode.IGNORE) {
            // Handle scrolling for each column separately
            int listTop = HEADER_H + 8;
            int listBottom = this.height - FOOTER_H - 4;

            // Check if mouse is over Items column
            if (mouseX >= IGNORE_COL_1_X && mouseX < IGNORE_COL_1_X + IGNORE_COL_WIDTH) {
                int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
                if (vertical > 0) itemsScrollOffset = Math.max(0, itemsScrollOffset - 1);
                else if (vertical < 0) itemsScrollOffset = Math.min(Math.max(0, ignoreItems.size() - visibleRows), itemsScrollOffset + 1);
                return true;
            }
            // Check if mouse is over Players column
            else if (mouseX >= IGNORE_COL_2_X && mouseX < IGNORE_COL_2_X + IGNORE_COL_WIDTH) {
                int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
                if (vertical > 0) playersScrollOffset = Math.max(0, playersScrollOffset - 1);
                else if (vertical < 0) playersScrollOffset = Math.min(Math.max(0, ignorePlayers.size() - visibleRows), playersScrollOffset + 1);
                return true;
            }
            // Check if mouse is over Warps column
            else if (mouseX >= IGNORE_COL_3_X && mouseX < IGNORE_COL_3_X + IGNORE_COL_WIDTH) {
                int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
                if (vertical > 0) warpsScrollOffset = Math.max(0, warpsScrollOffset - 1);
                else if (vertical < 0) warpsScrollOffset = Math.min(Math.max(0, ignoreWarps.size() - visibleRows), warpsScrollOffset + 1);
                return true;
            }
        }

        if (vertical > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (vertical < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();

        if (mode == Mode.IGNORE && click.button() == 0) {
            int listTop = HEADER_H + 8;
            int listBottom = this.height - FOOTER_H - 4;
            int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

            // Check Items column
            for (int i = 0; i < visibleRows; i++) {
                int idx = itemsScrollOffset + i;
                if (idx >= ignoreItems.size()) break;
                int rowY = listTop + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= IGNORE_COL_1_X && mouseX < IGNORE_COL_1_X + IGNORE_COL_WIDTH) {
                    if (mouseX >= IGNORE_COL_1_X + IGNORE_COL_WIDTH - 50) {
                        String entry = ignoreItems.get(idx);
                        ProfitFinder.removeIgnore(ProfitFinder.IgnoreKind.ITEMS, entry);
                        reloadIgnoreEntries();
                        if (client != null) client.setScreen(new ProfitScreen(result));
                    }
                    return true;
                }
            }

            // Check Players column
            for (int i = 0; i < visibleRows; i++) {
                int idx = playersScrollOffset + i;
                if (idx >= ignorePlayers.size()) break;
                int rowY = listTop + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= IGNORE_COL_2_X && mouseX < IGNORE_COL_2_X + IGNORE_COL_WIDTH) {
                    if (mouseX >= IGNORE_COL_2_X + IGNORE_COL_WIDTH - 50) {
                        String entry = ignorePlayers.get(idx);
                        ProfitFinder.removeIgnore(ProfitFinder.IgnoreKind.PLAYERS, entry);
                        reloadIgnoreEntries();
                        if (client != null) client.setScreen(new ProfitScreen(result));
                    }
                    return true;
                }
            }

            // Check Warps column
            for (int i = 0; i < visibleRows; i++) {
                int idx = warpsScrollOffset + i;
                if (idx >= ignoreWarps.size()) break;
                int rowY = listTop + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= IGNORE_COL_3_X && mouseX < IGNORE_COL_3_X + IGNORE_COL_WIDTH) {
                    if (mouseX >= IGNORE_COL_3_X + IGNORE_COL_WIDTH - 50) {
                        String entry = ignoreWarps.get(idx);
                        ProfitFinder.removeIgnore(ProfitFinder.IgnoreKind.WARPS, entry);
                        reloadIgnoreEntries();
                        if (client != null) client.setScreen(new ProfitScreen(result));
                    }
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
                    runWarpCommandAndHighlight(updatePriorities.get(idx).warp);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void runWarpCommand(String warp) {
        if (warp == null || warp.isBlank() || client == null || client.player == null) return;
        String cmd = warp.startsWith("/") ? warp.substring(1) : warp;
        client.player.networkHandler.sendChatCommand(cmd);
    }

    /** Same as runWarpCommand, but also lights up the shop_data.csv coord boxes.
     *  Only the Update tab's warp buttons should call this one. */
    private void runWarpCommandAndHighlight(String warp) {
        System.out.println("[ProfitScreen] Running warp command and highlighting for warp: " + warp);
        runWarpCommand(warp);

        // Strip "/warp " or "/home " prefixes to get the warp name
        String currentWarp = warp;
        if (currentWarp.startsWith("/warp ")) {
            currentWarp = currentWarp.substring(6);
        } else if (currentWarp.startsWith("/home ")) {
            currentWarp = currentWarp.substring(6);
        }
        System.out.println("[ProfitScreen] Stripped warp name: " + currentWarp);

        // Activate the highlighter for the current warp
        ShopHighlighter.activateForCurrentShopData(currentWarp);

        // Close the screen after warping
        this.close();
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
                    "Σ profit $%,.0f  ·  capital $%,.0f  ·  \n%d shown",
                    result.totalProfit, result.totalCapital, result.trades.size());
        }
        int listTop = HEADER_H + 4;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, result.trades.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        ctx.drawText(textRenderer, money, PAD, listTop - 12, 0xFF88FF88, false);

        int hy = listTop;

        // Use Flips page variables for Flips mode, Self-Flip variables for Self-Flip mode
        int itemColWidth = (mode == Mode.SELF) ? SELF_ITEM_COL_WIDTH : FLIPS_ITEM_COL_WIDTH;
        int ownerColWidth = (mode == Mode.SELF) ? SELF_OWNER_COL_WIDTH : 0;
        int profitColWidth = (mode == Mode.SELF) ? SELF_PROFIT_COL_WIDTH : FLIPS_PROFIT_COL_WIDTH;
        int warpsColWidth = (mode == Mode.SELF) ? 0 : FLIPS_WARPS_COL_WIDTH;
        int warpBtnW = (mode == Mode.SELF) ? SELF_WARP_BTN_W : FLIPS_WARP_BTN_W;
        int itemNameMaxChars = (mode == Mode.SELF) ? SELF_ITEM_NAME_MAX_CHARS : FLIPS_ITEM_NAME_MAX_CHARS;
        int warpNameMaxChars = (mode == Mode.SELF) ? SELF_WARP_NAME_MAX_CHARS : FLIPS_WARP_NAME_MAX_CHARS;

        ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
        if (mode == Mode.SELF) {
            ctx.drawText(textRenderer, "Owner", PAD + itemColWidth + 10, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Margin", this.width - PAD - profitColWidth, hy, 0xFF666666, false);
        } else {
            ctx.drawText(textRenderer, "Profit", this.width - PAD - profitColWidth - warpsColWidth - 20, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Warps", this.width - PAD - warpsColWidth, hy, 0xFF666666, false);
        }

        int rowStart = listTop + 12;
        if (result.trades.isEmpty()) {
            String empty = mode == Mode.SELF
                    ? "No same-owner buy>sell mistakes found."
                    : "No profitable flips (check shop_data / ignore lists).";
            ctx.drawText(textRenderer, empty, PAD, rowStart + 8, 0xFF888888, false);
        }

        clearChildren();
        init();

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

            String name = t.item.length() > itemNameMaxChars ? t.item.substring(0, itemNameMaxChars - 1) + "…" : t.item;
            ctx.drawText(textRenderer, name, PAD, rowY + 2, 0xFFFFFFAA, false);

            if (mode == Mode.SELF) {
                String owner = t.seller.length() > SELF_OWNER_NAME_MAX_CHARS ? t.seller.substring(0, SELF_OWNER_NAME_MAX_CHARS - 1) + "…" : t.seller;
                ctx.drawText(textRenderer, owner, PAD + itemColWidth + 10, rowY + 2, 0xFFCCCCCC, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f/ea", t.profitPerItem),
                        this.width - PAD - profitColWidth, rowY + 2, 0xFF55FF55, false);

                // Add warp button for Self-Flip mode
                int warpBtnX = this.width - PAD - warpBtnW;
                String sellerWarpLabel = shortWarp(t.sellerWarp, warpNameMaxChars);
                if (!t.sellerWarp.isBlank()) {
                    ButtonWidget sellerWarpBtn = ButtonWidget.builder(
                                    Text.literal(sellerWarpLabel),
                                    b -> runWarpCommand(t.sellerWarp)
                            ).dimensions(warpBtnX, rowY, warpBtnW, 16)
                            .tooltip(Tooltip.of(Text.literal("Warp to: " + t.sellerWarp)))
                            .build();
                    addDrawableChild(sellerWarpBtn);
                }
            } else {
                ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", t.totalProfit),
                        this.width - PAD - profitColWidth - warpsColWidth - 20, rowY + 2, 0xFF55FF55, false);

                ctx.drawText(textRenderer, t.seller + " → " + t.buyer, PAD, rowY + 11, 0xFF666666, false);

                int warpBtnX = this.width - PAD - warpBtnW - 10;
                String sellerWarpLabel = shortWarp(t.sellerWarp, warpNameMaxChars);
                String buyerWarpLabel = shortWarp(t.buyerWarp, warpNameMaxChars);

                if (!t.sellerWarp.isBlank()) {
                    ButtonWidget sellerWarpBtn = ButtonWidget.builder(
                                    Text.literal(sellerWarpLabel),
                                    b -> runWarpCommand(t.sellerWarp)
                            ).dimensions(warpBtnX - warpBtnW - 5, rowY, warpBtnW, 16)
                            .tooltip(Tooltip.of(Text.literal("Warp to seller: " + t.sellerWarp)))
                            .build();
                    addDrawableChild(sellerWarpBtn);
                }

                if (!t.buyerWarp.isBlank()) {
                    ButtonWidget buyerWarpBtn = ButtonWidget.builder(
                                    Text.literal(buyerWarpLabel),
                                    b -> runWarpCommand(t.buyerWarp)
                            ).dimensions(warpBtnX, rowY, warpBtnW, 16)
                            .tooltip(Tooltip.of(Text.literal("Warp to buyer: " + t.buyerWarp)))
                            .build();
                    addDrawableChild(buyerWarpBtn);
                }
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
                tip.add(Text.literal(String.format(Locale.US, "Qty: %d", hovered.quantity)).formatted(Formatting.GOLD));
                tip.add(Text.literal(String.format(Locale.US, "Buy @ $%.2f from %s",
                        hovered.sellPrice, hovered.seller)).formatted(Formatting.GREEN));
                tip.add(Text.literal(String.format(Locale.US, "Sell @ $%.2f to %s",
                        hovered.buyPrice, hovered.buyer)).formatted(Formatting.RED));
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
        ctx.drawText(textRenderer, "Ignore lists — excluded from Flips & Self-flip",
                PAD, 28, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Files in config/sunnyMod/",
                PAD, 40, 0xFF888888, false);

        int listTop = HEADER_H + 8;
        int listBottom = this.height - FOOTER_H - 4;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

        // Draw column headers
        ctx.drawText(textRenderer, "Items", IGNORE_COL_1_X, listTop - IGNORE_TEXT_VERTICAL_SPACING, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Players", IGNORE_COL_2_X, listTop - IGNORE_TEXT_VERTICAL_SPACING, 0xFFAAAAAA, false);
        ctx.drawText(textRenderer, "Warps", IGNORE_COL_3_X, listTop - IGNORE_TEXT_VERTICAL_SPACING, 0xFFAAAAAA, false);

        // Highlight the selected column
        int headerY = listTop - IGNORE_TEXT_VERTICAL_SPACING - 2;
        if (ignoreKind == ProfitFinder.IgnoreKind.ITEMS) {
            ctx.fill(IGNORE_COL_1_X - 2, headerY, IGNORE_COL_1_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        } else if (ignoreKind == ProfitFinder.IgnoreKind.PLAYERS) {
            ctx.fill(IGNORE_COL_2_X - 2, headerY, IGNORE_COL_2_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        } else if (ignoreKind == ProfitFinder.IgnoreKind.WARPS) {
            ctx.fill(IGNORE_COL_3_X - 2, headerY, IGNORE_COL_3_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        }

        // Draw Items column
        for (int i = 0; i < visibleRows; i++) {
            int idx = itemsScrollOffset + i;
            if (idx >= ignoreItems.size()) break;
            int rowY = listTop + i * ROW_HEIGHT;
            String entry = ignoreItems.get(idx);
            String displayEntry = entry.length() > 20 ? entry.substring(0, 19) + "…" : entry;

            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= IGNORE_COL_1_X && mouseX < IGNORE_COL_1_X + IGNORE_COL_WIDTH;
            if (hover) {
                ctx.fill(IGNORE_COL_1_X - 2, rowY - 1, IGNORE_COL_1_X + IGNORE_COL_WIDTH + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            }

            ctx.drawText(textRenderer, displayEntry, IGNORE_COL_1_X, rowY + 6, 0xFFFFFFFF, false);
            int remColor = (hover && mouseX >= IGNORE_COL_1_X + IGNORE_COL_WIDTH - 50) ? 0xFFFF5555 : 0xFFAA6666;
            ctx.drawText(textRenderer, "Remove", IGNORE_COL_1_X + IGNORE_COL_WIDTH - 45, rowY + 6, remColor, false);
        }

        // Draw Players column
        for (int i = 0; i < visibleRows; i++) {
            int idx = playersScrollOffset + i;
            if (idx >= ignorePlayers.size()) break;
            int rowY = listTop + i * ROW_HEIGHT;
            String entry = ignorePlayers.get(idx);
            String displayEntry = entry.length() > 20 ? entry.substring(0, 19) + "…" : entry;

            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= IGNORE_COL_2_X && mouseX < IGNORE_COL_2_X + IGNORE_COL_WIDTH;
            if (hover) {
                ctx.fill(IGNORE_COL_2_X - 2, rowY - 1, IGNORE_COL_2_X + IGNORE_COL_WIDTH + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            }

            ctx.drawText(textRenderer, displayEntry, IGNORE_COL_2_X, rowY + 6, 0xFFFFFFFF, false);
            int remColor = (hover && mouseX >= IGNORE_COL_2_X + IGNORE_COL_WIDTH - 50) ? 0xFFFF5555 : 0xFFAA6666;
            ctx.drawText(textRenderer, "Remove", IGNORE_COL_2_X + IGNORE_COL_WIDTH - 45, rowY + 6, remColor, false);
        }

        // Draw Warps column
        for (int i = 0; i < visibleRows; i++) {
            int idx = warpsScrollOffset + i;
            if (idx >= ignoreWarps.size()) break;
            int rowY = listTop + i * ROW_HEIGHT;
            String entry = ignoreWarps.get(idx);
            String displayEntry = entry.length() > 20 ? entry.substring(0, 19) + "…" : entry;

            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= IGNORE_COL_3_X && mouseX < IGNORE_COL_3_X + IGNORE_COL_WIDTH;
            if (hover) {
                ctx.fill(IGNORE_COL_3_X - 2, rowY - 1, IGNORE_COL_3_X + IGNORE_COL_WIDTH + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            }

            ctx.drawText(textRenderer, displayEntry, IGNORE_COL_3_X, rowY + 6, 0xFFFFFFFF, false);
            int remColor = (hover && mouseX >= IGNORE_COL_3_X + IGNORE_COL_WIDTH - 50) ? 0xFFFF5555 : 0xFFAA6666;
            ctx.drawText(textRenderer, "Remove", IGNORE_COL_3_X + IGNORE_COL_WIDTH - 45, rowY + 6, remColor, false);
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
        ctx.drawText(textRenderer, "Items", PAD + UPDATE_ITEMS_COL_WIDTH, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Total edge", PAD + UPDATE_ITEMS_COL_WIDTH + UPDATE_EDGE_COL_WIDTH, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Age", PAD + UPDATE_ITEMS_COL_WIDTH + UPDATE_EDGE_COL_WIDTH + UPDATE_AGE_COL_WIDTH, hy, 0xFF666666, false);

        int rowStart = listTop + 12;
        if (updatePriorities.isEmpty()) {
            ctx.drawText(textRenderer, "Nothing stale right now — all tracked shops are Active.",
                    PAD, rowStart + 8, 0xFF888888, false);
        }

        clearChildren();
        init();

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

            String warpName = hasWarp ? shortWarp(w.warp, UPDATE_WARP_NAME_MAX_CHARS) : "(no warp saved)";
            int nameColor = hasWarp ? 0xFFFFFFAA : 0xFF777766;
            ctx.drawText(textRenderer, warpName, PAD, rowY + 2, nameColor, false);
            ctx.drawText(textRenderer, String.valueOf(w.items.size()), PAD + UPDATE_ITEMS_COL_WIDTH, rowY + 2, 0xFFCCCCCC, false);
            ctx.drawText(textRenderer, w.totalPotentialValue > 0
                            ? String.format(Locale.US, "+$%.2f", w.totalPotentialValue) : "—",
                    PAD + UPDATE_ITEMS_COL_WIDTH + UPDATE_EDGE_COL_WIDTH, rowY + 2, w.totalPotentialValue > 0 ? 0xFF55FF55 : 0xFF666666, false);
            ctx.drawText(textRenderer, String.format(Locale.US, "%.0fh", w.maxAgeHours),
                    PAD + UPDATE_ITEMS_COL_WIDTH + UPDATE_EDGE_COL_WIDTH + UPDATE_AGE_COL_WIDTH, rowY + 2, 0xFFAAAAAA, false);

            // Add warp button for Update Priorities mode
            int warpBtnX = this.width - PAD - UPDATE_WARP_BTN_W;
            if (hasWarp) {
                ButtonWidget warpBtn = ButtonWidget.builder(
                                Text.literal(shortWarp(w.warp, UPDATE_WARP_NAME_MAX_CHARS)),
                                b -> runWarpCommandAndHighlight(w.warp)
                        ).dimensions(warpBtnX, rowY, UPDATE_WARP_BTN_W, 16)
                        .tooltip(Tooltip.of(Text.literal("Warp to: " + w.warp)))
                        .build();
                addDrawableChild(warpBtn);
            }
        }

        if (hovered != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hovered.warp.isEmpty() ? "(no warp saved)" : hovered.warp));
            tip.add(Text.literal(String.format(Locale.US, "%d stale items · total edge +$%.2f",
                    hovered.items.size(), hovered.totalPotentialValue)));
            tip.add(Text.literal(String.format(Locale.US, "Oldest scan %.0fh ago · worst streak %d unchanged rescans",
                    hovered.maxAgeHours, hovered.maxNoChangeStreak)));

            tip.add(Text.literal("Click to warp: " + (hovered.warp.isEmpty() ? "(no warp saved)" : hovered.warp)));
            ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }

        ctx.drawText(textRenderer, "F8 · scroll", PAD, this.height - 18, 0xFF666666, false);
    }

    private static String shortWarp(String w, int maxChars) {
        if (w == null || w.isBlank()) return "—";
        String s = w.startsWith("/warp ") ? w.substring(6) : w;
        if (s.startsWith("warp ")) s = s.substring(5);
        return s.length() > maxChars ? s.substring(0, maxChars - 1) + "…" : s;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}