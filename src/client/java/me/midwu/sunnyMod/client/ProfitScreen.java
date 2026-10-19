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
    private static final int FLIPS_WARP_NAME_MAX_CHARS = 24;     // Max characters for warp names in Flips mode
    private static final int FLIPS_IGNORE_BTN_W = 54;


    // --- SELF-FLIP PAGE VARIABLES ---
    private static final int SELF_ITEM_COL_WIDTH = 200;   // Width of the Item column in Self-Flip mode
    private static final int SELF_OWNER_COL_WIDTH = 280; // Width of the Owner column in Self-Flip mode
    private static final int SELF_PROFIT_COL_WIDTH = 240;  // Width of the Margin column in Self-Flip mode
    private static final int SELF_WARP_BTN_W = 180;      // Width of warp button in Self-Flip mode
    private static final int SELF_OWNER_NAME_MAX_CHARS = 40;   // Max characters for owner names in Self-Flip mode
    private static final int SELF_WARP_NAME_MAX_CHARS = 24;     // Max characters for warp names in Self-Flip mode

    // --- SEARCH PAGE VARIABLES ---
    private static final int SEARCH_ITEM_COL_WIDTH = 200;   // Width of the Item column in Search mode
    private static final int SEARCH_OWNER_COL_WIDTH = 220;  // Width of the Owner column in Search mode
    private static final int SEARCH_PRICE_COL_WIDTH = 120;  // Width of the Price column in Search mode
    private static final int SEARCH_WARP_BTN_W = 160;       // Width of warp button in Search mode
    private static final int SEARCH_ITEM_NAME_MAX_CHARS = 24;   // Max characters for item names in Search mode
    private static final int SEARCH_OWNER_NAME_MAX_CHARS = 26;  // Max characters for owner names in Search mode
    private static final int SEARCH_WARP_NAME_MAX_CHARS = 22;   // Max characters for warp names in Search mode

    // --- UPDATE PAGE VARIABLES ---
    private static final int UPDATE_WARP_COL_WIDTH = 230;  // Width of the Warp name column in Update mode
    private static final int UPDATE_SHOPS_COL_WIDTH = 90;  // Width of the Live shops column in Update mode
    private static final int UPDATE_AGE_COL_WIDTH = 110;   // Width of the Oldest shop column in Update mode
    private static final int UPDATE_WARP_BTN_W = 70;       // Width of the Warp button in Update mode
    private static final int UPDATE_WARP_NAME_MAX_CHARS = 32;     // Max characters for warp names in Update mode
    private static final int UPDATE_ROWS_TOP = HEADER_H + 4 + 12; // Y of the first Update row (below the column headings)

    // --- IGNORE TAB VARIABLES ---
    private static final int IGNORE_TEXT_VERTICAL_SPACING = 12; // Vertical spacing between text lines in Ignore tab
    private static final int IGNORE_COL_WIDTH = 120; // Width for each column in Ignore tab
    private static final int IGNORE_COL_1_X = PAD; // X position for first column (Items)
    private static final int IGNORE_COL_2_X = PAD + IGNORE_COL_WIDTH + 20; // X position for second column (Players)
    private static final int IGNORE_COL_3_X = PAD + (IGNORE_COL_WIDTH * 2) + 40; // X position for third column (Warps)
    private static final int IGNORE_COL_4_X = PAD + (IGNORE_COL_WIDTH * 3) + 60;
    // --- END CONFIGURABLE VARIABLES ---

    public enum Mode {
        FLIPS("Flips"),
        SELF("Self-flip"),
        SEARCH("Search"),
        UPDATE("Update"),
        IGNORE("Ignore lists");

        final String label;
        Mode(String label) { this.label = label; }
    }

    static boolean ageFilterEnabled = false;
    static double ageFilterHours = 24.0;
    static Mode mode = Mode.FLIPS;
    static ProfitFinder.IgnoreKind ignoreKind = ProfitFinder.IgnoreKind.ITEMS; // Default to Items

    // Search tab state — persists across screen rebuilds like the other filters above.
    static String searchQueryText = "";
    static boolean searchWantToBuy = true;   // true = "Buy" (shops SELLING it, cheapest first)
    // false = "Sell" (shops BUYING it, best price first)
    static boolean searchActiveOnly = true;  // true = Active listings only, false = Other (out of stock/space)

    private ProfitFinder.Result result;
    private List<String> ignoreItems = List.of();
    private List<String> ignorePlayers = List.of();
    private List<String> ignoreWarps = List.of();
    private List<String> ignoreFlips = List.of();
    private List<UpdateQueue.WarpEntry> updateQueue = List.of();
    private int updateUnseen = 0; // live shops at the current warp not seen since the visit began
    private List<ProfitFinder.ShopEntry> searchResults = List.of();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int itemsScrollOffset = 0;
    private int playersScrollOffset = 0;
    private int warpsScrollOffset = 0;
    private TextFieldWidget addField;
    private TextFieldWidget searchField;

    public ProfitScreen(ProfitFinder.Result result) {
        super(Text.literal("Profit finder"));
        this.result = result != null
                ? result
                : new ProfitFinder.Result(List.of(), 0, 0, 0, "empty", false);
        if (mode == Mode.IGNORE) {
            reloadIgnoreEntries();
        } else if (mode == Mode.UPDATE) {
            reloadUpdateQueue();
        } else if (mode == Mode.SEARCH) {
            runSearch();
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
        ignoreFlips = new ArrayList<>(ProfitFinder.loadIgnore(ProfitFinder.IgnoreKind.FLIPS));
        scrollOffset = 0;
        itemsScrollOffset = 0;
        playersScrollOffset = 0;
        warpsScrollOffset = 0;
    }

    private void reloadUpdateQueue() {
        updateQueue = UpdateQueue.loadQueue();
        updateUnseen = UpdateQueue.countUnseen(ShopLogger.getLastWarp(), ShopLogger.getWarpVisitStartMs());
        scrollOffset = 0;
    }

    private void runSearch() {
        searchResults = ProfitFinder.searchShopData(searchQueryText, searchWantToBuy, searchActiveOnly);
        scrollOffset = 0;
    }

    private void switchMode(Mode m) {
        mode = m;
        scrollOffset = 0;
        if (m == Mode.IGNORE) {
            reloadIgnoreEntries();
            if (client != null) client.setScreen(new ProfitScreen(result));
        } else if (m == Mode.UPDATE) {
            reloadUpdateQueue();
            if (client != null) client.setScreen(new ProfitScreen(result));
        } else if (m == Mode.SEARCH) {
            runSearch();
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

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(ignoreKind == ProfitFinder.IgnoreKind.FLIPS ? "[Flips]" : "Flips"),
                            b -> {
                                ignoreKind = ProfitFinder.IgnoreKind.FLIPS;
                            })
                    .dimensions(PAD + 240, buttonY, 70, 16)
                    .build());

            addField = new TextFieldWidget(textRenderer, PAD + 320, this.height - FOOTER_H + 4, 160, 16,
                    Text.literal("Add entry"));
            addField.setMaxLength(64);
            addField.setPlaceholder(Text.literal("Type name, Enter to add"));
            addDrawableChild(addField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> tryAddIgnore())
                    .dimensions(PAD + 486, this.height - FOOTER_H + 4, 40, 16).build());
        } else if (mode == Mode.UPDATE) {
            ButtonWidget markBtn = ButtonWidget.builder(
                            Text.literal("Mark unseen as dead (" + updateUnseen + ")"),
                            b -> markUnseenAsDead())
                    .dimensions(400, 6, 190, 18)
                    .tooltip(Tooltip.of(Text.literal(
                            "Marks every live shop of your CURRENT warp that was not seen since you\n"
                                    + "warped there (i.e. the scanner did not stamp it) as Dead.\n"
                                    + "Scan the signs first. A backup is saved as shop_data.csv.bak.")))
                    .build();
            markBtn.active = updateUnseen > 0;
            addDrawableChild(markBtn);

            addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> {
                if (client != null) client.setScreen(new ProfitScreen(result));
            }).dimensions(this.width - PAD - 80, 6, 70, 18).build());
        } else if (mode == Mode.SEARCH) {
            int sx = 400; // sits right of the mode tabs (5 tabs end around x=388)

            searchField = new TextFieldWidget(textRenderer, sx, 6, 150, 18, Text.literal("Search"));
            searchField.setMaxLength(48);
            searchField.setPlaceholder(Text.literal("e.g. ancient, spawner"));
            searchField.setText(searchQueryText);
            searchField.setChangedListener(text -> {
                searchQueryText = text;
                runSearch();
            });
            addDrawableChild(searchField);
            // renderSearch() calls clearChildren()+init() every frame (same pattern as the
            // Flips/Update tabs, needed to rebuild the per-row warp buttons), which would
            // otherwise silently steal keyboard focus away from the field on every frame.
            setInitialFocus(searchField);

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(searchWantToBuy ? "[Buy: cheapest]" : "Sell: best $"),
                            b -> {
                                searchWantToBuy = !searchWantToBuy;
                                runSearch();
                                if (client != null) client.setScreen(new ProfitScreen(result));
                            })
                    .dimensions(sx + 158, 6, 110, 18)
                    .tooltip(Tooltip.of(Text.literal(
                            "Buy: shops selling this item, cheapest first.\n"
                                    + "Sell: shops buying this item, best price (most money) first.")))
                    .build());

            addDrawableChild(ButtonWidget.builder(
                            Text.literal(searchActiveOnly ? "[Active]" : "Other"),
                            b -> {
                                searchActiveOnly = !searchActiveOnly;
                                runSearch();
                                if (client != null) client.setScreen(new ProfitScreen(result));
                            })
                    .dimensions(sx + 276, 6, 70, 18)
                    .tooltip(Tooltip.of(Text.literal(
                            "Active: in-stock listings only.\nOther: out of stock / out of space.")))
                    .build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> runSearch())
                    .dimensions(this.width - PAD - 80, 6, 70, 18).build());
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
        int visibleRows = mode == Mode.UPDATE
                ? updateVisibleRows()
                : Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int size = switch (mode) {
            case IGNORE -> Math.max(Math.max(ignoreItems.size(), ignorePlayers.size()), Math.max(ignoreWarps.size(), ignoreFlips.size()));
            case UPDATE -> updateQueue.size();
            case SEARCH -> searchResults.size();
            default -> result.trades.size();
        };
        maxScroll = Math.max(0, size - visibleRows);

        if (mode == Mode.UPDATE) {
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;
            addUpdateRowButtons(visibleRows);
        }
    }

    private int updateVisibleRows() {
        return Math.max(1, (this.height - FOOTER_H - UPDATE_ROWS_TOP) / ROW_HEIGHT);
    }

    /** One "Warp" button per visible Update row. Rebuilt (clearChildren + init) whenever the list scrolls. */
    private void addUpdateRowButtons(int visibleRows) {
        int btnX = this.width - PAD - UPDATE_WARP_BTN_W;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= updateQueue.size()) break;
            UpdateQueue.WarpEntry w = updateQueue.get(idx);
            int rowY = UPDATE_ROWS_TOP + i * ROW_HEIGHT;
            addDrawableChild(ButtonWidget.builder(Text.literal("Warp"), b -> runWarpCommandAndHighlight(w.command))
                    .dimensions(btnX, rowY, UPDATE_WARP_BTN_W, 16)
                    .tooltip(Tooltip.of(Text.literal("Warp to: " + w.command)))
                    .build());
        }
    }

    /** Marks every live shop of the current warp that was not seen since the visit began as Dead. */
    private void markUnseenAsDead() {
        String warp = ShopLogger.getLastWarp();
        int n = UpdateQueue.markUnseenDead(warp, ShopLogger.getWarpVisitStartMs());
        if (client != null && client.player != null) {
            if (n < 0) {
                client.player.sendMessage(Text.literal("§c[Update] Failed to update shop_data.csv"), false);
            } else {
                client.player.sendMessage(Text.literal("§a[Update] Marked §f" + n + " §7unseen shop"
                        + (n == 1 ? "" : "s") + " as dead at §f" + shortWarp(warp, 40)), false);
            }
        }
        ShopHighlighter.deactivate();
        if (client != null) client.setScreen(new ProfitScreen(result)); // constructor reloads the queue
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
            else if (mouseX >= IGNORE_COL_4_X && mouseX < IGNORE_COL_4_X + IGNORE_COL_WIDTH) {
                int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
                if (vertical > 0) warpsScrollOffset = Math.max(0, warpsScrollOffset - 1);
                else if (vertical < 0) warpsScrollOffset = Math.min(Math.max(0, ignoreFlips.size() - visibleRows), warpsScrollOffset + 1);
                return true;
            }
        }

        if (vertical > 0) scrollOffset = Math.max(0, scrollOffset - 1);
        else if (vertical < 0) scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        if (mode == Mode.UPDATE) {
            clearChildren();
            init(); // row buttons depend on scrollOffset
        }
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

            // Check Flips column
            for (int i = 0; i < visibleRows; i++) {
                int idx = warpsScrollOffset + i;
                if (idx >= ignoreFlips.size()) break;
                int rowY = listTop + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                        && mouseX >= IGNORE_COL_4_X && mouseX < IGNORE_COL_4_X + IGNORE_COL_WIDTH) {
                    if (mouseX >= IGNORE_COL_4_X + IGNORE_COL_WIDTH - 50) {
                        String entry = ignoreFlips.get(idx);
                        ProfitFinder.removeIgnore(ProfitFinder.IgnoreKind.FLIPS, entry);
                        reloadIgnoreEntries();
                        if (client != null) client.setScreen(new ProfitScreen(result));
                    }
                    return true;
                }
            }
        }

        if (mode != Mode.IGNORE && click.button() == 0) {
            int listTop = HEADER_H + 4;
            int listBottom = this.height - FOOTER_H;
            int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
            int rowStart = listTop + 12;
            for (int i = 0; i < visibleRows; i++) {
                int idx = scrollOffset + i;
                if (idx >= result.trades.size()) break;
                int rowY = rowStart + i * ROW_HEIGHT;
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    int ignoreX = this.width - PAD - FLIPS_WARP_BTN_W - FLIPS_IGNORE_BTN_W - 8;
                    if (mode == Mode.FLIPS && mouseX >= ignoreX && mouseX < ignoreX + FLIPS_IGNORE_BTN_W) {
                        ignoreTrade(result.trades.get(idx));
                        return true;
                    }
                    if (mode == Mode.SELF) {
                        int selfIgnoreX = this.width - PAD - SELF_WARP_BTN_W - FLIPS_IGNORE_BTN_W - 8;
                        if (mouseX >= selfIgnoreX && mouseX < selfIgnoreX + FLIPS_IGNORE_BTN_W) {
                            ignoreTrade(result.trades.get(idx));
                            return true;
                        }
                    }
                }
            }
        }

        return super.mouseClicked(click, doubled);
    }

    private void ignoreTrade(ProfitFinder.Trade trade) {
        if (trade == null || client == null) return;
        String key = ProfitFinder.flipIgnoreKey(trade);
        if (ProfitFinder.addIgnore(ProfitFinder.IgnoreKind.FLIPS, key)) {
            result = runFind();
        }
        client.setScreen(new ProfitScreen(result));
    }

    private void runWarpCommand(String warp) {
        if (warp == null || warp.isBlank() || client == null || client.player == null) return;
        String cmd = warp.startsWith("/") ? warp.substring(1) : warp;
        client.player.networkHandler.sendChatCommand(cmd);
    }

    /** Same as runWarpCommand, but also lights up every shop_data.csv coord box at that warp.
     *  Used by the Update tab, where one row summarizes a whole warp's worth of stale listings. */
    private void runWarpCommandAndHighlight(String warp) {
        System.out.println("[ProfitScreen] Running warp command and highlighting for warp: " + warp);
        runWarpCommand(warp);

        // Activate the highlighter for every shop at the current warp
        ShopHighlighter.activateForCurrentShopData(stripWarpPrefix(warp));

        // Close the screen after warping
        this.close();
    }

    /** Same as runWarpCommandAndHighlight, but only lights up the specific shop location(s) given,
     *  instead of every shop at the warp. Used by the Flips/Self-flip warp buttons, where each row
     *  is one specific seller/buyer shop, not the whole warp. */
    private void runWarpCommandAndHighlightLocations(String warp, List<String> locations) {
        System.out.println("[ProfitScreen] Running warp command and highlighting locations "
                + locations + " for warp: " + warp);
        runWarpCommand(warp);

        // Activate the highlighter for just the given shop location(s)
        ShopHighlighter.activateForLocations(stripWarpPrefix(warp), locations);

        // Close the screen after warping
        this.close();
    }

    /** Strips a leading "/warp " or "/home " so callers are left with just the warp/home name. */
    private static String stripWarpPrefix(String warp) {
        String currentWarp = warp;
        if (currentWarp.startsWith("/warp ")) {
            currentWarp = currentWarp.substring(6);
        } else if (currentWarp.startsWith("/home ")) {
            currentWarp = currentWarp.substring(6);
        }
        return currentWarp;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC000000);

        if (mode == Mode.IGNORE) {
            renderIgnore(ctx, mouseX, mouseY);
        } else if (mode == Mode.UPDATE) {
            renderUpdatePriorities(ctx, mouseX, mouseY);
        } else if (mode == Mode.SEARCH) {
            renderSearch(ctx, mouseX, mouseY);
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
        int warpNameMaxChars = (mode == Mode.SELF) ? SELF_WARP_NAME_MAX_CHARS : FLIPS_WARP_NAME_MAX_CHARS;

        ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
        if (mode == Mode.SELF) {
            ctx.drawText(textRenderer, "Owner", PAD + itemColWidth + 10, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Margin", this.width - PAD - profitColWidth, hy, 0xFF666666, false);
        } else {
            int ignoreX = this.width - PAD - warpsColWidth - FLIPS_IGNORE_BTN_W - 8;
            ctx.drawText(textRenderer, "Profit", this.width - PAD - profitColWidth - warpsColWidth - FLIPS_IGNORE_BTN_W - 20, hy, 0xFF666666, false);
            ctx.drawText(textRenderer, "Ignore", ignoreX, hy, 0xFF666666, false);
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

            // Full item name, never truncated/capped.
            String name = t.item;
            ctx.drawText(textRenderer, name, PAD, rowY + 2, 0xFFFFFFAA, false);

            if (mode == Mode.SELF) {
                String owner = t.seller.length() > SELF_OWNER_NAME_MAX_CHARS ? t.seller.substring(0, SELF_OWNER_NAME_MAX_CHARS - 1) + "…" : t.seller;
                ctx.drawText(textRenderer, owner, PAD + itemColWidth + 10, rowY + 2, 0xFFCCCCCC, false);
                ctx.drawText(textRenderer, String.format(Locale.US, "$%.2f/ea", t.profitPerItem),
                        this.width - PAD - profitColWidth, rowY + 2, 0xFF55FF55, false);

                // Add warp button for Self-Flip mode
                int selfIgnoreX = this.width - PAD - warpBtnW - FLIPS_IGNORE_BTN_W - 8;
                addDrawableChild(ButtonWidget.builder(Text.literal("Ignore"), b -> ignoreTrade(t))
                        .dimensions(selfIgnoreX, rowY, FLIPS_IGNORE_BTN_W, 16)
                        .tooltip(Tooltip.of(Text.literal("Permanently ignore this exact flip")))
                        .build());

                int warpBtnX = this.width - PAD - warpBtnW;
                String sellerWarpLabel = shortWarp(t.sellerWarp, warpNameMaxChars);
                if (!t.sellerWarp.isBlank()) {
                    ButtonWidget sellerWarpBtn = ButtonWidget.builder(
                                    Text.literal(sellerWarpLabel),
                                    b -> runWarpCommandAndHighlightLocations(
                                            t.sellerWarp, List.of(t.sellerLocation, t.buyerLocation))
                            ).dimensions(warpBtnX, rowY, warpBtnW, 16)
                            .tooltip(Tooltip.of(Text.literal("Warp to: " + t.sellerWarp)))
                            .build();
                    addDrawableChild(sellerWarpBtn);
                }
            } else {
                ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", t.totalProfit),
                        this.width - PAD - profitColWidth - warpsColWidth - FLIPS_IGNORE_BTN_W - 20, rowY + 2, 0xFF55FF55, false);

                ctx.drawText(textRenderer, t.seller + " → " + t.buyer, PAD, rowY + 11, 0xFF666666, false);

                int ignoreX = this.width - PAD - warpBtnW - FLIPS_IGNORE_BTN_W - 8;
                ButtonWidget ignoreBtn = ButtonWidget.builder(Text.literal("Ignore"),
                                b -> ignoreTrade(t))
                        .dimensions(ignoreX, rowY, FLIPS_IGNORE_BTN_W, 16)
                        .tooltip(Tooltip.of(Text.literal("Permanently ignore this exact flip")))
                        .build();
                addDrawableChild(ignoreBtn);

                int warpBtnX = this.width - PAD - warpBtnW - 10;
                String sellerWarpLabel = shortWarp(t.sellerWarp, warpNameMaxChars);
                String buyerWarpLabel = shortWarp(t.buyerWarp, warpNameMaxChars);

                if (!t.sellerWarp.isBlank()) {
                    ButtonWidget sellerWarpBtn = ButtonWidget.builder(
                                    Text.literal(sellerWarpLabel),
                                    b -> runWarpCommandAndHighlightLocations(
                                            t.sellerWarp, List.of(t.sellerLocation))
                            ).dimensions(warpBtnX - warpBtnW - 5, rowY, warpBtnW, 16)
                            .tooltip(Tooltip.of(Text.literal("Warp to seller: " + t.sellerWarp)))
                            .build();
                    addDrawableChild(sellerWarpBtn);
                }

                if (!t.buyerWarp.isBlank()) {
                    ButtonWidget buyerWarpBtn = ButtonWidget.builder(
                                    Text.literal(buyerWarpLabel),
                                    b -> runWarpCommandAndHighlightLocations(
                                            t.buyerWarp, List.of(t.buyerLocation))
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

    private void renderSearch(DrawContext ctx, int mouseX, int mouseY) {
        String modeLabel = searchWantToBuy
                ? "Buy mode — shops selling this item, cheapest first"
                : "Sell mode — shops buying this item, best price (most money) first";
        ctx.drawText(textRenderer, modeLabel, PAD, 28, 0xFFCCCCCC, false);
        ctx.drawText(textRenderer,
                searchResults.size() + " results · " + (searchActiveOnly ? "Active only" : "Other (out of stock/space)"),
                PAD, 40, 0xFF888888, false);

        int listTop = HEADER_H + 4;
        int listBottom = this.height - FOOTER_H;
        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        maxScroll = Math.max(0, searchResults.size() - visibleRows);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        int hy = listTop;
        ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Owner", PAD + SEARCH_ITEM_COL_WIDTH, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Price", PAD + SEARCH_ITEM_COL_WIDTH + SEARCH_OWNER_COL_WIDTH, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Status", PAD + SEARCH_ITEM_COL_WIDTH + SEARCH_OWNER_COL_WIDTH + SEARCH_PRICE_COL_WIDTH, hy, 0xFF666666, false);

        int rowStart = listTop + 12;
        if (searchResults.isEmpty()) {
            String empty = searchQueryText.isBlank()
                    ? "Type an item name above to search (e.g. \"ancient\", \"spawner\")."
                    : "No matches for \"" + searchQueryText + "\".";
            ctx.drawText(textRenderer, empty, PAD, rowStart + 8, 0xFF888888, false);
        }

        clearChildren();
        init();

        ProfitFinder.ShopEntry hovered = null;
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= searchResults.size()) break;
            ProfitFinder.ShopEntry e = searchResults.get(idx);
            int rowY = rowStart + i * ROW_HEIGHT;

            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= PAD && mouseX < this.width - PAD;
            if (hover) {
                ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
                hovered = e;
            }

            String name = e.item.length() > SEARCH_ITEM_NAME_MAX_CHARS
                    ? e.item.substring(0, SEARCH_ITEM_NAME_MAX_CHARS - 1) + "…" : e.item;
            ctx.drawText(textRenderer, name, PAD, rowY + 2, 0xFFFFFFAA, false);

            String owner = e.owner.length() > SEARCH_OWNER_NAME_MAX_CHARS
                    ? e.owner.substring(0, SEARCH_OWNER_NAME_MAX_CHARS - 1) + "…" : e.owner;
            ctx.drawText(textRenderer, owner, PAD + SEARCH_ITEM_COL_WIDTH, rowY + 2, 0xFFCCCCCC, false);

            ctx.drawText(textRenderer, String.format(Locale.US, "$%,.2f", e.price),
                    PAD + SEARCH_ITEM_COL_WIDTH + SEARCH_OWNER_COL_WIDTH, rowY + 2,
                    searchWantToBuy ? 0xFF55FF55 : 0xFFFFAA55, false);

            int statusColor = "Active".equalsIgnoreCase(e.status) ? 0xFF55FF55 : 0xFFFF6666;
            ctx.drawText(textRenderer, e.status,
                    PAD + SEARCH_ITEM_COL_WIDTH + SEARCH_OWNER_COL_WIDTH + SEARCH_PRICE_COL_WIDTH, rowY + 2,
                    statusColor, false);

            // Warp button for each result row
            int warpBtnX = this.width - PAD - SEARCH_WARP_BTN_W;
            if (!e.warp.isBlank()) {
                ButtonWidget warpBtn = ButtonWidget.builder(
                                Text.literal(shortWarp(e.warp, SEARCH_WARP_NAME_MAX_CHARS)),
                                b -> runWarpCommandAndHighlightLocations(
                                        e.warp, List.of(e.location))
                        ).dimensions(warpBtnX, rowY, SEARCH_WARP_BTN_W, 16)
                        .tooltip(Tooltip.of(Text.literal("Warp to: " + e.warp)))
                        .build();
                addDrawableChild(warpBtn);
            }
        }

        if (hovered != null) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(hovered.item));
            tip.add(Text.literal(hovered.action + " @ $" + String.format(Locale.US, "%.2f", hovered.price)
                    + " · " + hovered.owner));
            tip.add(Text.literal("Stock/space: " + (hovered.stockSpace.isBlank() ? "—" : hovered.stockSpace)));
            tip.add(Text.literal("Status: " + hovered.status));
            double age = hovered.ageHours();
            tip.add(Text.literal(age >= 0
                    ? String.format(Locale.US, "Last scanned %.1fh ago", age)
                    : "Last scanned: unknown"));
            tip.add(Text.literal("Warp: " + (hovered.warp.isEmpty() ? "(none)" : hovered.warp)));
            ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
        }

        ctx.drawText(textRenderer, "F8 · scroll · type to filter", PAD, this.height - 18, 0xFF666666, false);
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
        ctx.drawText(textRenderer, "Flips", IGNORE_COL_4_X, listTop - IGNORE_TEXT_VERTICAL_SPACING, 0xFFAAAAAA, false);

        // Highlight the selected column
        int headerY = listTop - IGNORE_TEXT_VERTICAL_SPACING - 2;
        if (ignoreKind == ProfitFinder.IgnoreKind.ITEMS) {
            ctx.fill(IGNORE_COL_1_X - 2, headerY, IGNORE_COL_1_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        } else if (ignoreKind == ProfitFinder.IgnoreKind.PLAYERS) {
            ctx.fill(IGNORE_COL_2_X - 2, headerY, IGNORE_COL_2_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        } else if (ignoreKind == ProfitFinder.IgnoreKind.WARPS) {
            ctx.fill(IGNORE_COL_3_X - 2, headerY, IGNORE_COL_3_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
        } else if (ignoreKind == ProfitFinder.IgnoreKind.FLIPS) {
            ctx.fill(IGNORE_COL_4_X - 2, headerY, IGNORE_COL_4_X + IGNORE_COL_WIDTH + 2, headerY + 12, 0x33FFFFFF);
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

        // Draw Flips column
        for (int i = 0; i < visibleRows; i++) {
            int idx = warpsScrollOffset + i;
            if (idx >= ignoreFlips.size()) break;
            int rowY = listTop + i * ROW_HEIGHT;
            String entry = ignoreFlips.get(idx);
            String displayEntry = entry.length() > 20 ? entry.substring(0, 19) + "…" : entry;
            boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseX >= IGNORE_COL_4_X && mouseX < IGNORE_COL_4_X + IGNORE_COL_WIDTH;
            if (hover) ctx.fill(IGNORE_COL_4_X - 2, rowY - 1, IGNORE_COL_4_X + IGNORE_COL_WIDTH + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            ctx.drawText(textRenderer, displayEntry, IGNORE_COL_4_X, rowY + 6, 0xFFFFFFFF, false);
            int remColor = (hover && mouseX >= IGNORE_COL_4_X + IGNORE_COL_WIDTH - 50) ? 0xFFFF5555 : 0xFFAA6666;
            ctx.drawText(textRenderer, "Remove", IGNORE_COL_4_X + IGNORE_COL_WIDTH - 45, rowY + 6, remColor, false);
        }
    }

    private void renderUpdatePriorities(DrawContext ctx, int mouseX, int mouseY) {
        int liveShops = 0;
        for (UpdateQueue.WarpEntry w : updateQueue) liveShops += w.liveShops;

        ctx.drawText(textRenderer, "Update queue — warps ordered by their OLDEST live shop (dead shops skipped)",
                PAD, 28, 0xFFCCCCCC, false);
        String here = UpdateQueue.bareWarp(ShopLogger.getLastWarp());
        ctx.drawText(textRenderer,
                updateQueue.size() + " warps · " + liveShops + " live shops · current warp: "
                        + (here.isEmpty() ? "(none)" : shortWarp(here, 30))
                        + " · unseen here: " + updateUnseen,
                PAD, 40, 0xFF888888, false);

        int hy = HEADER_H + 4;
        ctx.drawText(textRenderer, "Warp", PAD, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Live shops", PAD + UPDATE_WARP_COL_WIDTH, hy, 0xFF666666, false);
        ctx.drawText(textRenderer, "Oldest shop", PAD + UPDATE_WARP_COL_WIDTH + UPDATE_SHOPS_COL_WIDTH, hy, 0xFF666666, false);

        if (updateQueue.isEmpty()) {
            ctx.drawText(textRenderer, "No warps with live shops in shop_data.csv.",
                    PAD, UPDATE_ROWS_TOP + 8, 0xFF888888, false);
        }

        int visibleRows = updateVisibleRows();
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= updateQueue.size()) break;
            UpdateQueue.WarpEntry w = updateQueue.get(idx);
            int rowY = UPDATE_ROWS_TOP + i * ROW_HEIGHT;

            boolean isHere = !here.isEmpty() && w.bare.equalsIgnoreCase(here);
            if (isHere) {
                ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x3355AA55);
            }

            ctx.drawText(textRenderer, shortWarp(w.bare, UPDATE_WARP_NAME_MAX_CHARS) + (isHere ? " (current)" : ""),
                    PAD, rowY + 4, isHere ? 0xFF88FF88 : 0xFFFFFFAA, false);
            ctx.drawText(textRenderer, String.valueOf(w.liveShops),
                    PAD + UPDATE_WARP_COL_WIDTH, rowY + 4, 0xFFCCCCCC, false);
            ctx.drawText(textRenderer, formatAge(w.oldestMs),
                    PAD + UPDATE_WARP_COL_WIDTH + UPDATE_SHOPS_COL_WIDTH, rowY + 4, ageColor(w.oldestMs), false);
        }

        ctx.drawText(textRenderer,
                "F8 · scroll · Warp, scan the signs, then reopen F8 and press \"Mark unseen as dead\"",
                PAD, this.height - 18, 0xFF666666, false);
    }

    /** "3d 4h", "5h 12m", "9m", "<1m" — age of a shop timestamp. 0 = unknown. */
    private static String formatAge(long epochMs) {
        if (epochMs <= 0) return "unknown";
        long secs = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        long d = secs / 86400;
        long h = (secs % 86400) / 3600;
        long m = (secs % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return "<1m";
    }

    /** Older = redder, so the warps that most need a visit stand out. */
    private static int ageColor(long epochMs) {
        if (epochMs <= 0) return 0xFFFF5555;
        long hours = Math.max(0, (System.currentTimeMillis() - epochMs) / 3_600_000L);
        if (hours >= 72) return 0xFFFF5555;
        if (hours >= 24) return 0xFFFFAA55;
        if (hours >= 6) return 0xFFFFFF55;
        return 0xFF55FF55;
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