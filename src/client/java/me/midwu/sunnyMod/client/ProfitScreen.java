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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ProfitScreen extends Screen {

    // =========================================================================
    // GENERAL LAYOUT
    // =========================================================================

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_H = 56;
    private static final int FOOTER_H = 28;
    private static final int PAD = 12;

    // =========================================================================
    // FLIPS
    // =========================================================================

    private static final int FLIPS_ITEM_COL_WIDTH = 60;
    private static final int FLIPS_PROFIT_COL_WIDTH = 140;
    private static final int FLIPS_WARPS_COL_WIDTH = 210;
    private static final int FLIPS_WARP_BTN_W = 140;

    private static final int FLIPS_ITEM_NAME_MAX_CHARS = 12;
    private static final int FLIPS_WARP_NAME_MAX_CHARS = 24;

    // =========================================================================
    // SELF-FLIP
    // =========================================================================

    private static final int SELF_ITEM_COL_WIDTH = 200;
    private static final int SELF_OWNER_COL_WIDTH = 280;
    private static final int SELF_PROFIT_COL_WIDTH = 240;
    private static final int SELF_WARP_BTN_W = 180;

    private static final int SELF_ITEM_NAME_MAX_CHARS = 24;
    private static final int SELF_OWNER_NAME_MAX_CHARS = 40;
    private static final int SELF_WARP_NAME_MAX_CHARS = 24;

    // =========================================================================
    // UPDATE
    // =========================================================================

    private static final int UPDATE_WARP_COL_WIDTH = 140;
    private static final int UPDATE_ITEMS_COL_WIDTH = 120;
    private static final int UPDATE_EDGE_COL_WIDTH = 140;
    private static final int UPDATE_AGE_COL_WIDTH = 120;
    private static final int UPDATE_WARP_BTN_W = 180;
    private static final int UPDATE_WARP_NAME_MAX_CHARS = 24;

    // =========================================================================
    // IGNORE LISTS
    // =========================================================================

    private static final int IGNORE_TEXT_VERTICAL_SPACING = 12;
    private static final int IGNORE_COL_WIDTH = 120;

    private static final int IGNORE_COL_1_X = PAD;
    private static final int IGNORE_COL_2_X =
            PAD + IGNORE_COL_WIDTH + 20;
    private static final int IGNORE_COL_3_X =
            PAD + (IGNORE_COL_WIDTH * 2) + 40;

    // =========================================================================
    // SHOP SEARCH
    // =========================================================================

    private static final int SHOP_SEARCH_ITEM_COL_WIDTH = 200;
    private static final int SHOP_SEARCH_OWNER_COL_WIDTH = 150;
    private static final int SHOP_SEARCH_PRICE_COL_WIDTH = 100;
    private static final int SHOP_SEARCH_WARP_COL_WIDTH = 120;
    private static final int SHOP_SEARCH_WARP_BTN_W = 100;

    private static final int SHOP_SEARCH_MAX_CHARS = 24;

    // =========================================================================
    // MODES
    // =========================================================================

    public enum Mode {
        FLIPS("Flips"),
        SELF("Self-flip"),
        UPDATE("Update"),
        IGNORE("Ignore lists"),
        SHOP_SEARCH("Shop Search");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    // =========================================================================
    // EXISTING STATIC STATE
    // =========================================================================

    static boolean ageFilterEnabled = false;
    static double ageFilterHours = 24.0;

    static Mode mode = Mode.FLIPS;

    static ProfitFinder.IgnoreKind ignoreKind =
            ProfitFinder.IgnoreKind.ITEMS;

    static boolean hideRecentlyScanned = true;

    // =========================================================================
    // EXISTING SCREEN STATE
    // =========================================================================

    private ProfitFinder.Result result;

    private List<String> ignoreItems = List.of();
    private List<String> ignorePlayers = List.of();
    private List<String> ignoreWarps = List.of();

    private List<ProfitFinder.WarpSummary> updatePriorities =
            List.of();

    private int scrollOffset = 0;
    private int maxScroll = 0;

    private int itemsScrollOffset = 0;
    private int playersScrollOffset = 0;
    private int warpsScrollOffset = 0;

    private TextFieldWidget addField;

    // =========================================================================
    // SHOP SEARCH STATE
    // =========================================================================

    private TextFieldWidget shopSearchField;

    private String shopSearchTerm = "";

    /*
     * true  = BUYING
     * false = SELLING
     */
    private boolean shopSearchIsBuying = true;

    /*
     * true  = Active
     * false = Other
     */
    private boolean shopSearchIsActive = true;

    private List<ShopData> shopDataList =
            new ArrayList<>();

    private List<ShopData> filteredShopData =
            new ArrayList<>();

    // =========================================================================
    // SHOP DATA
    // =========================================================================

    /**
     * One raw row from shop_data.csv.
     *
     * CSV structure used by ProfitFinder:
     *
     * 0 = location
     * 1 = owner
     * 2 = item
     * 3 = stock/space
     * 4 = price
     * 5 = action
     * 6 = status
     * 7 = timestamp
     * 8 = warp
     */
    private static class ShopData {

        String name;
        String owner;
        String warp;
        String location;

        double price;

        boolean isBuying;
        boolean isSelling;
        boolean isActive;

        ShopData(
                String name,
                String owner,
                String warp,
                String location,
                double price,
                boolean isBuying,
                boolean isSelling,
                boolean isActive
        ) {
            this.name = name;
            this.owner = owner;
            this.warp = warp;
            this.location = location;
            this.price = price;
            this.isBuying = isBuying;
            this.isSelling = isSelling;
            this.isActive = isActive;
        }
    }

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public ProfitScreen(ProfitFinder.Result result) {
        super(Text.literal("Profit finder"));

        this.result = result != null
                ? result
                : new ProfitFinder.Result(
                List.of(),
                0,
                0,
                0,
                "empty",
                false
        );

        if (mode == Mode.IGNORE) {
            reloadIgnoreEntries();

        } else if (mode == Mode.UPDATE) {
            reloadUpdatePriorities();

        } else if (mode == Mode.SHOP_SEARCH) {
            loadShopData();
            updateFilteredShopData();
        }
    }

    // =========================================================================
    // EXISTING PROFIT FINDER
    // =========================================================================

    static ProfitFinder.Result runFind() {

        double age =
                ageFilterEnabled
                        ? ageFilterHours
                        : -1;

        if (mode == Mode.SELF) {
            return ProfitFinder.findSelfFlips(
                    0.01,
                    500,
                    age
            );
        }

        return ProfitFinder.findFlips(
                0.01,
                0.0,
                500,
                age
        );
    }

    // =========================================================================
    // IGNORE LISTS
    // =========================================================================

    private void reloadIgnoreEntries() {

        ignoreItems =
                new ArrayList<>(
                        ProfitFinder.loadIgnore(
                                ProfitFinder.IgnoreKind.ITEMS
                        )
                );

        ignorePlayers =
                new ArrayList<>(
                        ProfitFinder.loadIgnore(
                                ProfitFinder.IgnoreKind.PLAYERS
                        )
                );

        ignoreWarps =
                new ArrayList<>(
                        ProfitFinder.loadIgnore(
                                ProfitFinder.IgnoreKind.WARPS
                        )
                );

        scrollOffset = 0;
        itemsScrollOffset = 0;
        playersScrollOffset = 0;
        warpsScrollOffset = 0;
    }

    private void reloadUpdatePriorities() {

        updatePriorities =
                ProfitFinder.findUpdatePrioritiesByWarp(
                        hideRecentlyScanned
                );

        scrollOffset = 0;
    }

    // =========================================================================
    // SHOP SEARCH - LOAD CSV
    // =========================================================================

    private void loadShopData() {

        shopDataList.clear();

        try {

            List<String[]> rows =
                    ProfitFinder.readAllShopDataRows();

            for (String[] row : rows) {

                if (row.length < 7) {
                    continue;
                }

                String location =
                        row[0].trim();

                String owner =
                        row[1].trim();

                String item =
                        ProfitFinder.strip(row[2]);

                if (item.isBlank()) {
                    continue;
                }

                double price;

                try {

                    price = Double.parseDouble(
                            row[4]
                                    .replace(",", "")
                                    .replace("$", "")
                                    .trim()
                    );

                } catch (NumberFormatException e) {
                    continue;
                }

                String action =
                        row[5].trim();

                String status =
                        row[6].trim();

                String warp =
                        row.length > 8
                                ? row[8].trim()
                                : "";

                boolean isBuying =
                        "BUYING".equalsIgnoreCase(action);

                boolean isSelling =
                        "SELLING".equalsIgnoreCase(action);

                boolean isActive =
                        "Active".equalsIgnoreCase(status);

                /*
                 * Ignore rows which aren't BUYING or SELLING.
                 */
                if (!isBuying && !isSelling) {
                    continue;
                }

                shopDataList.add(
                        new ShopData(
                                item,
                                owner,
                                warp,
                                location,
                                price,
                                isBuying,
                                isSelling,
                                isActive
                        )
                );
            }

        } catch (Exception e) {

            System.err.println(
                    "[ProfitScreen] Failed to load shop_data.csv: "
                            + e.getMessage()
            );
        }
    }

    // =========================================================================
    // SHOP SEARCH - FILTER + SORT
    // =========================================================================

    private void updateFilteredShopData() {

        String search =
                shopSearchTerm == null
                        ? ""
                        : shopSearchTerm
                        .trim()
                        .toLowerCase(Locale.ROOT);

        filteredShopData =
                shopDataList.stream()

                        /*
                         * Search the ITEM name.
                         *
                         * "ancient" will match:
                         * Ancient Debris
                         * Ancient ...
                         *
                         * "spawner" will match:
                         * Spawner
                         * Skeleton Spawner
                         * Zombie Spawner
                         */
                        .filter(shop ->
                                search.isEmpty()
                                        || shop.name
                                        .toLowerCase(Locale.ROOT)
                                        .contains(search)
                        )

                        /*
                         * BUYING / SELLING
                         */
                        .filter(shop ->
                                shopSearchIsBuying
                                        ? shop.isBuying
                                        : shop.isSelling
                        )

                        /*
                         * Active / Other
                         */
                        .filter(shop ->
                                shopSearchIsActive
                                        ? shop.isActive
                                        : !shop.isActive
                        )

                        /*
                         * BUYING:
                         * cheapest first.
                         *
                         * SELLING:
                         * highest price first.
                         */
                        .sorted(
                                getShopSearchComparator()
                        )

                        .toList();

        scrollOffset = 0;

        int visibleRows =
                Math.max(
                        1,
                        (height - HEADER_H - FOOTER_H)
                                / ROW_HEIGHT
                );

        maxScroll =
                Math.max(
                        0,
                        filteredShopData.size()
                                - visibleRows
                );
    }

    private Comparator<ShopData> getShopSearchComparator() {

        if (shopSearchIsBuying) {

            return Comparator.comparingDouble(
                    shop -> shop.price
            );

        }

        return Comparator.comparingDouble(
                (ShopData shop) -> shop.price
        ).reversed();
    }

    // =========================================================================
    // MODE SWITCHING
    // =========================================================================

    private void switchMode(Mode newMode) {

        mode = newMode;

        scrollOffset = 0;

        if (newMode == Mode.IGNORE) {

            reloadIgnoreEntries();

            if (client != null) {
                client.setScreen(
                        new ProfitScreen(result)
                );
            }

        } else if (newMode == Mode.UPDATE) {

            reloadUpdatePriorities();

            if (client != null) {
                client.setScreen(
                        new ProfitScreen(result)
                );
            }

        } else if (newMode == Mode.SHOP_SEARCH) {

            loadShopData();
            updateFilteredShopData();

            if (client != null) {
                client.setScreen(
                        new ProfitScreen(result)
                );
            }

        } else {

            if (client != null) {
                client.setScreen(
                        new ProfitScreen(runFind())
                );
            }
        }
    }

    // =========================================================================
    // INIT
    // =========================================================================

    @Override
    protected void init() {

        /*
         * Top navigation tabs.
         */
        int bx = PAD;
        int bw = 72;

        for (Mode m : Mode.values()) {

            Mode captured = m;

            boolean on =
                    mode == m;

            ButtonWidget btn =
                    ButtonWidget.builder(
                                    Text.literal(
                                            on
                                                    ? "[" + m.label + "]"
                                                    : m.label
                                    ),
                                    b -> switchMode(captured)
                            )
                            .dimensions(
                                    bx,
                                    6,
                                    bw,
                                    18
                            )
                            .build();

            addDrawableChild(btn);

            bx += bw + 4;
        }

        // =====================================================================
        // IGNORE TAB
        // =====================================================================

        if (mode == Mode.IGNORE) {

            int buttonY =
                    this.height - FOOTER_H + 4;

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            ignoreKind
                                                    == ProfitFinder.IgnoreKind.ITEMS
                                                    ? "[Items]"
                                                    : "Items"
                                    ),
                                    b -> {
                                        ignoreKind =
                                                ProfitFinder.IgnoreKind.ITEMS;
                                    }
                            )
                            .dimensions(
                                    PAD,
                                    buttonY,
                                    70,
                                    16
                            )
                            .build()
            );

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            ignoreKind
                                                    == ProfitFinder.IgnoreKind.PLAYERS
                                                    ? "[Players]"
                                                    : "Players"
                                    ),
                                    b -> {
                                        ignoreKind =
                                                ProfitFinder.IgnoreKind.PLAYERS;
                                    }
                            )
                            .dimensions(
                                    PAD + 80,
                                    buttonY,
                                    70,
                                    16
                            )
                            .build()
            );

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            ignoreKind
                                                    == ProfitFinder.IgnoreKind.WARPS
                                                    ? "[Warps]"
                                                    : "Warps"
                                    ),
                                    b -> {
                                        ignoreKind =
                                                ProfitFinder.IgnoreKind.WARPS;
                                    }
                            )
                            .dimensions(
                                    PAD + 160,
                                    buttonY,
                                    70,
                                    16
                            )
                            .build()
            );

            addField =
                    new TextFieldWidget(
                            textRenderer,
                            PAD + 240,
                            this.height - FOOTER_H + 4,
                            160,
                            16,
                            Text.literal("Add entry")
                    );

            addField.setMaxLength(64);

            addField.setPlaceholder(
                    Text.literal(
                            "Type name, Enter to add"
                    )
            );

            addDrawableChild(addField);

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal("Add"),
                                    b -> tryAddIgnore()
                            )
                            .dimensions(
                                    PAD + 406,
                                    this.height - FOOTER_H + 4,
                                    40,
                                    16
                            )
                            .build()
            );

            // =====================================================================
            // UPDATE TAB
            // =====================================================================

        } else if (mode == Mode.UPDATE) {

            String label =
                    hideRecentlyScanned
                            ? "Hide <15m: On"
                            : "Hide <15m: Off";

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(label),
                                    b -> {

                                        hideRecentlyScanned =
                                                !hideRecentlyScanned;

                                        reloadUpdatePriorities();

                                        if (client != null) {
                                            client.setScreen(
                                                    new ProfitScreen(result)
                                            );
                                        }
                                    }
                            )
                            .dimensions(
                                    this.width - PAD - 170,
                                    6,
                                    85,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Skip shops you already "
                                                            + "rescanned in the "
                                                            + "last 15 minutes."
                                            )
                                    )
                            )
                            .build()
            );

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal("Refresh"),
                                    b -> {

                                        reloadUpdatePriorities();

                                        if (client != null) {
                                            client.setScreen(
                                                    new ProfitScreen(result)
                                            );
                                        }
                                    }
                            )
                            .dimensions(
                                    this.width - PAD - 80,
                                    6,
                                    70,
                                    18
                            )
                            .build()
            );

            // =====================================================================
            // SHOP SEARCH TAB
            // =====================================================================

        } else if (mode == Mode.SHOP_SEARCH) {

            /*
             * Search box.
             */
            shopSearchField =
                    new TextFieldWidget(
                            textRenderer,
                            PAD,
                            30,
                            200,
                            20,
                            Text.literal("Search")
                    );

            shopSearchField.setMaxLength(64);

            shopSearchField.setPlaceholder(
                    Text.literal(
                            "Item name..."
                    )
            );

            shopSearchField.setText(
                    shopSearchTerm
            );

            /*
             * Update results immediately as the user types.
             */
            shopSearchField.setChangedListener(
                    text -> {

                        shopSearchTerm =
                                text == null
                                        ? ""
                                        : text;

                        updateFilteredShopData();
                    }
            );

            addDrawableChild(
                    shopSearchField
            );

            /*
             * BUYING
             */
            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            shopSearchIsBuying
                                                    ? "[BUYING]"
                                                    : "BUYING"
                                    ),
                                    b -> {

                                        shopSearchIsBuying =
                                                true;

                                        updateFilteredShopData();
                                    }
                            )
                            .dimensions(
                                    PAD + 220,
                                    30,
                                    70,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Show shops that BUY this item"
                                            )
                                    )
                            )
                            .build()
            );

            /*
             * SELLING
             */
            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            !shopSearchIsBuying
                                                    ? "[SELLING]"
                                                    : "SELLING"
                                    ),
                                    b -> {

                                        shopSearchIsBuying =
                                                false;

                                        updateFilteredShopData();
                                    }
                            )
                            .dimensions(
                                    PAD + 295,
                                    30,
                                    75,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Show shops that SELL this item"
                                            )
                                    )
                            )
                            .build()
            );

            /*
             * ACTIVE
             */
            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            shopSearchIsActive
                                                    ? "[Active]"
                                                    : "Active"
                                    ),
                                    b -> {

                                        shopSearchIsActive =
                                                true;

                                        updateFilteredShopData();
                                    }
                            )
                            .dimensions(
                                    PAD + 375,
                                    30,
                                    70,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Only Active shops"
                                            )
                                    )
                            )
                            .build()
            );

            /*
             * OTHER
             */
            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(
                                            !shopSearchIsActive
                                                    ? "[Other]"
                                                    : "Other"
                                    ),
                                    b -> {

                                        shopSearchIsActive =
                                                false;

                                        updateFilteredShopData();
                                    }
                            )
                            .dimensions(
                                    PAD + 450,
                                    30,
                                    70,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Only inactive / other shops"
                                            )
                                    )
                            )
                            .build()
            );

            // =====================================================================
            // NORMAL FLIPS / SELF TAB
            // =====================================================================

        } else {

            String ageLabel =
                    ageFilterEnabled
                            ? String.format(
                            Locale.US,
                            "Age ≤%.0fh",
                            ageFilterHours
                    )
                            : "Age: off";

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal(ageLabel),
                                    b -> {

                                        ageFilterEnabled =
                                                !ageFilterEnabled;

                                        if (client != null) {
                                            client.setScreen(
                                                    new ProfitScreen(
                                                            runFind()
                                                    )
                                            );
                                        }
                                    }
                            )
                            .dimensions(
                                    this.width - PAD - 170,
                                    6,
                                    85,
                                    18
                            )
                            .tooltip(
                                    Tooltip.of(
                                            Text.literal(
                                                    "Drop listings older than "
                                                            + (int) ageFilterHours
                                                            + "h vs newest timestamp. "
                                                            + "Default off."
                                            )
                                    )
                            )
                            .build()
            );

            addDrawableChild(
                    ButtonWidget.builder(
                                    Text.literal("Refresh"),
                                    b -> {

                                        if (client != null) {
                                            client.setScreen(
                                                    new ProfitScreen(
                                                            runFind()
                                                    )
                                            );
                                        }
                                    }
                            )
                            .dimensions(
                                    this.width - PAD - 80,
                                    6,
                                    70,
                                    18
                            )
                            .build()
            );
        }

        /*
         * Close button.
         */
        addDrawableChild(
                ButtonWidget.builder(
                                Text.literal("Close"),
                                b -> close()
                        )
                        .dimensions(
                                this.width - PAD - 80,
                                this.height - 24,
                                70,
                                18
                        )
                        .build()
        );

        /*
         * Calculate scrolling.
         */
        int listBottom =
                this.height - FOOTER_H;

        int listTop =
                HEADER_H;

        int visibleRows =
                Math.max(
                        1,
                        (listBottom - listTop)
                                / ROW_HEIGHT
                );

        int size =
                switch (mode) {

                    case IGNORE ->
                            Math.max(
                                    ignoreItems.size(),
                                    Math.max(
                                            ignorePlayers.size(),
                                            ignoreWarps.size()
                                    )
                            );

                    case UPDATE ->
                            updatePriorities.size();

                    case SHOP_SEARCH ->
                            filteredShopData.size();

                    default ->
                            result.trades.size();
                };

        maxScroll =
                Math.max(
                        0,
                        size - visibleRows
                );

        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
    }

    // =========================================================================
    // IGNORE LIST ADD
    // =========================================================================

    private void tryAddIgnore() {

        if (addField == null) {
            return;
        }

        String value =
                addField.getText();

        if (value == null || value.isBlank()) {
            return;
        }

        if (ProfitFinder.addIgnore(
                ignoreKind,
                value.trim()
        )) {

            addField.setText("");

            reloadIgnoreEntries();

            if (client != null) {
                client.setScreen(
                        new ProfitScreen(result)
                );
            }
        }
    }

    // =========================================================================
    // KEY INPUT
    // =========================================================================

    @Override
    public boolean keyPressed(KeyInput input) {

        /*
         * Ignore-list Enter.
         */
        if (
                mode == Mode.IGNORE
                        && addField != null
                        && addField.isFocused()
                        && (
                        input.key() == 257
                                || input.key() == 335
                )
        ) {

            tryAddIgnore();

            return true;
        }

        /*
         * Shop Search Enter.
         *
         * Search already updates as the user types,
         * but Enter is still accepted.
         */
        if (
                mode == Mode.SHOP_SEARCH
                        && shopSearchField != null
                        && shopSearchField.isFocused()
                        && (
                        input.key() == 257
                                || input.key() == 335
                )
        ) {

            shopSearchTerm =
                    shopSearchField.getText();

            updateFilteredShopData();

            return true;
        }

        return super.keyPressed(input);
    }

    // =========================================================================
    // SCROLLING
    // =========================================================================

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontal,
            double vertical
    ) {

        /*
         * Ignore tab has three independent scroll columns.
         */
        if (mode == Mode.IGNORE) {

            int listTop =
                    HEADER_H + 8;

            int listBottom =
                    this.height - FOOTER_H - 4;

            int visibleRows =
                    Math.max(
                            1,
                            (listBottom - listTop)
                                    / ROW_HEIGHT
                    );

            /*
             * Items
             */
            if (
                    mouseX >= IGNORE_COL_1_X
                            && mouseX <
                            IGNORE_COL_1_X
                                    + IGNORE_COL_WIDTH
            ) {

                if (vertical > 0) {

                    itemsScrollOffset =
                            Math.max(
                                    0,
                                    itemsScrollOffset - 1
                            );

                } else if (vertical < 0) {

                    itemsScrollOffset =
                            Math.min(
                                    Math.max(
                                            0,
                                            ignoreItems.size()
                                                    - visibleRows
                                    ),
                                    itemsScrollOffset + 1
                            );
                }

                return true;
            }

            /*
             * Players
             */
            if (
                    mouseX >= IGNORE_COL_2_X
                            && mouseX <
                            IGNORE_COL_2_X
                                    + IGNORE_COL_WIDTH
            ) {

                if (vertical > 0) {

                    playersScrollOffset =
                            Math.max(
                                    0,
                                    playersScrollOffset - 1
                            );

                } else if (vertical < 0) {

                    playersScrollOffset =
                            Math.min(
                                    Math.max(
                                            0,
                                            ignorePlayers.size()
                                                    - visibleRows
                                    ),
                                    playersScrollOffset + 1
                            );
                }

                return true;
            }

            /*
             * Warps
             */
            if (
                    mouseX >= IGNORE_COL_3_X
                            && mouseX <
                            IGNORE_COL_3_X
                                    + IGNORE_COL_WIDTH
            ) {

                if (vertical > 0) {

                    warpsScrollOffset =
                            Math.max(
                                    0,
                                    warpsScrollOffset - 1
                            );

                } else if (vertical < 0) {

                    warpsScrollOffset =
                            Math.min(
                                    Math.max(
                                            0,
                                            ignoreWarps.size()
                                                    - visibleRows
                                    ),
                                    warpsScrollOffset + 1
                            );
                }

                return true;
            }
        }

        /*
         * Normal scrolling.
         */
        if (vertical > 0) {

            scrollOffset =
                    Math.max(
                            0,
                            scrollOffset - 1
                    );

        } else if (vertical < 0) {

            scrollOffset =
                    Math.min(
                            maxScroll,
                            scrollOffset + 1
                    );
        }

        return true;
    }

    // =========================================================================
    // MOUSE CLICKING
    // =========================================================================

    @Override
    public boolean mouseClicked(
            Click click,
            boolean doubled
    ) {

        double mouseX =
                click.x();

        double mouseY =
                click.y();

        // =====================================================================
        // IGNORE
        // =====================================================================

        if (
                mode == Mode.IGNORE
                        && click.button() == 0
        ) {

            int listTop =
                    HEADER_H + 8;

            int listBottom =
                    this.height - FOOTER_H - 4;

            int visibleRows =
                    Math.max(
                            1,
                            (listBottom - listTop)
                                    / ROW_HEIGHT
                    );

            /*
             * Items.
             */
            for (int i = 0; i < visibleRows; i++) {

                int idx =
                        itemsScrollOffset + i;

                if (idx >= ignoreItems.size()) {
                    break;
                }

                int rowY =
                        listTop + i * ROW_HEIGHT;

                if (
                        mouseY >= rowY
                                && mouseY < rowY + ROW_HEIGHT
                                && mouseX >= IGNORE_COL_1_X
                                && mouseX <
                                IGNORE_COL_1_X
                                        + IGNORE_COL_WIDTH
                ) {

                    if (
                            mouseX >=
                                    IGNORE_COL_1_X
                                            + IGNORE_COL_WIDTH
                                            - 50
                    ) {

                        String entry =
                                ignoreItems.get(idx);

                        ProfitFinder.removeIgnore(
                                ProfitFinder.IgnoreKind.ITEMS,
                                entry
                        );

                        reloadIgnoreEntries();

                        if (client != null) {
                            client.setScreen(
                                    new ProfitScreen(result)
                            );
                        }
                    }

                    return true;
                }
            }

            /*
             * Players.
             */
            for (int i = 0; i < visibleRows; i++) {

                int idx =
                        playersScrollOffset + i;

                if (idx >= ignorePlayers.size()) {
                    break;
                }

                int rowY =
                        listTop + i * ROW_HEIGHT;

                if (
                        mouseY >= rowY
                                && mouseY < rowY + ROW_HEIGHT
                                && mouseX >= IGNORE_COL_2_X
                                && mouseX <
                                IGNORE_COL_2_X
                                        + IGNORE_COL_WIDTH
                ) {

                    if (
                            mouseX >=
                                    IGNORE_COL_2_X
                                            + IGNORE_COL_WIDTH
                                            - 50
                    ) {

                        String entry =
                                ignorePlayers.get(idx);

                        ProfitFinder.removeIgnore(
                                ProfitFinder.IgnoreKind.PLAYERS,
                                entry
                        );

                        reloadIgnoreEntries();

                        if (client != null) {
                            client.setScreen(
                                    new ProfitScreen(result)
                            );
                        }
                    }

                    return true;
                }
            }

            /*
             * Warps.
             */
            for (int i = 0; i < visibleRows; i++) {

                int idx =
                        warpsScrollOffset + i;

                if (idx >= ignoreWarps.size()) {
                    break;
                }

                int rowY =
                        listTop + i * ROW_HEIGHT;

                if (
                        mouseY >= rowY
                                && mouseY < rowY + ROW_HEIGHT
                                && mouseX >= IGNORE_COL_3_X
                                && mouseX <
                                IGNORE_COL_3_X
                                        + IGNORE_COL_WIDTH
                ) {

                    if (
                            mouseX >=
                                    IGNORE_COL_3_X
                                            + IGNORE_COL_WIDTH
                                            - 50
                    ) {

                        String entry =
                                ignoreWarps.get(idx);

                        ProfitFinder.removeIgnore(
                                ProfitFinder.IgnoreKind.WARPS,
                                entry
                        );

                        reloadIgnoreEntries();

                        if (client != null) {
                            client.setScreen(
                                    new ProfitScreen(result)
                            );
                        }
                    }

                    return true;
                }
            }
        }

        // =====================================================================
        // UPDATE
        // =====================================================================

        if (
                mode == Mode.UPDATE
                        && click.button() == 0
        ) {

            int listTop =
                    HEADER_H + 4;

            int listBottom =
                    this.height - FOOTER_H;

            int visibleRows =
                    Math.max(
                            1,
                            (listBottom - listTop)
                                    / ROW_HEIGHT
                    );

            int rowStart =
                    listTop + 12;

            for (int i = 0; i < visibleRows; i++) {

                int idx =
                        scrollOffset + i;

                if (idx >= updatePriorities.size()) {
                    break;
                }

                int rowY =
                        rowStart + i * ROW_HEIGHT;

                if (
                        mouseY >= rowY
                                && mouseY < rowY + ROW_HEIGHT
                                && mouseX >= PAD
                                && mouseX < this.width - PAD
                ) {

                    runWarpCommandAndHighlight(
                            updatePriorities
                                    .get(idx)
                                    .warp
                    );

                    return true;
                }
            }
        }

        /*
         * NOTE:
         *
         * Shop Search warp buttons are real ButtonWidgets.
         * Therefore we do NOT make the whole row clickable here.
         *
         * This prevents clicking somewhere else in a row from
         * accidentally warping.
         */

        return super.mouseClicked(
                click,
                doubled
        );
    }

    // =========================================================================
    // WARP
    // =========================================================================

    private void runWarpCommand(String warp) {

        if (
                warp == null
                        || warp.isBlank()
                        || client == null
                        || client.player == null
        ) {
            return;
        }

        String command;

        if (warp.startsWith("/")) {
            command =
                    warp.substring(1);
        } else {
            /*
             * shop_data normally stores things like:
             *
             * /warp shopname
             *
             * but if it only contains:
             *
             * shopname
             *
             * treat it as a warp name.
             */
            if (
                    warp.contains(" ")
                            && (
                            warp.startsWith("warp ")
                                    || warp.startsWith("home ")
                    )
            ) {
                command = warp;
            } else {
                command =
                        "warp " + warp;
            }
        }

        client.player.networkHandler.sendChatCommand(
                command
        );
    }

    /**
     * Warp to a shop, run findsign, activate the existing
     * ShopHighlighter and close the ProfitScreen.
     *
     * The existing Update tab already used ShopHighlighter here;
     * Shop Search now uses the same integration.
     */
    private void runWarpCommandAndHighlight(
            String warp
    ) {

        if (
                warp == null
                        || warp.isBlank()
                        || client == null
                        || client.player == null
        ) {
            return;
        }

        System.out.println(
                "[ProfitScreen] Running warp command: "
                        + warp
        );

        /*
         * 1. Warp.
         */
        runWarpCommand(warp);

        /*
         * 2. Run findsign immediately after the warp command.
         *
         * This is the actual Minecraft command:
         *
         * /findsign
         */
        try {

            client.player.networkHandler.sendChatCommand(
                    "findsign"
            );

        } catch (Exception e) {

            System.err.println(
                    "[ProfitScreen] Failed to run findsign: "
                            + e.getMessage()
            );
        }

        /*
         * 3. Strip /warp or /home so ShopHighlighter receives
         * only the warp name.
         */
        String currentWarp =
                warp.trim();

        if (
                currentWarp.startsWith("/warp ")
        ) {

            currentWarp =
                    currentWarp.substring(6)
                            .trim();

        } else if (
                currentWarp.startsWith("/home ")
        ) {

            currentWarp =
                    currentWarp.substring(6)
                            .trim();

        } else if (
                currentWarp.startsWith("warp ")
        ) {

            currentWarp =
                    currentWarp.substring(5)
                            .trim();

        } else if (
                currentWarp.startsWith("home ")
        ) {

            currentWarp =
                    currentWarp.substring(5)
                            .trim();
        }

        System.out.println(
                "[ProfitScreen] Stripped warp name: "
                        + currentWarp
        );

        /*
         * 4. Activate the existing highlighter.
         */
        ShopHighlighter.activateForCurrentShopData(
                currentWarp
        );

        /*
         * 5. Close the GUI.
         */
        close();
    }

    // =========================================================================
    // RENDER
    // =========================================================================

    @Override
    public void render(
            DrawContext ctx,
            int mouseX,
            int mouseY,
            float delta
    ) {

        ctx.fill(
                0,
                0,
                this.width,
                this.height,
                0xCC000000
        );

        if (mode == Mode.IGNORE) {

            renderIgnore(
                    ctx,
                    mouseX,
                    mouseY
            );

        } else if (mode == Mode.UPDATE) {

            renderUpdatePriorities(
                    ctx,
                    mouseX,
                    mouseY
            );

        } else if (mode == Mode.SHOP_SEARCH) {

            renderShopSearch(
                    ctx,
                    mouseX,
                    mouseY,
                    delta
            );

        } else {

            renderTrades(
                    ctx,
                    mouseX,
                    mouseY
            );
        }

        super.render(
                ctx,
                mouseX,
                mouseY,
                delta
        );
    }

    // =========================================================================
    // SHOP SEARCH RENDER
    // =========================================================================

    private void renderShopSearch(
            DrawContext ctx,
            int mouseX,
            int mouseY,
            float delta
    ) {

        ctx.drawText(
                textRenderer,
                "Shop Search — find shops by item name",
                PAD,
                12,
                0xFFCCCCCC,
                false
        );

        ctx.drawText(
                textRenderer,
                String.format(
                        Locale.US,
                        "%d result%s · %s · %s",
                        filteredShopData.size(),
                        filteredShopData.size() == 1
                                ? ""
                                : "s",
                        shopSearchIsBuying
                                ? "BUYING · cheapest first"
                                : "SELLING · highest price first",
                        shopSearchIsActive
                                ? "Active"
                                : "Other"
                ),
                PAD,
                23,
                0xFF888888,
                false
        );

        /*
         * Search field.
         *
         * The actual widget is rendered by super.render(),
         * so we intentionally do not call shopSearchField.render()
         * here.
         */
        if (shopSearchField != null) {
            /*
             * Keep it in the correct position.
             */
            shopSearchField.setX(PAD);
            shopSearchField.setY(30);
        }

        int listTop =
                HEADER_H + 4;

        int listBottom =
                this.height - FOOTER_H;

        int visibleRows =
                Math.max(
                        1,
                        (listBottom - listTop)
                                / ROW_HEIGHT
                );

        /*
         * Column headers.
         */
        ctx.drawText(
                textRenderer,
                "Item",
                PAD,
                listTop - 12,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                "Owner",
                PAD + SHOP_SEARCH_ITEM_COL_WIDTH + 10,
                listTop - 12,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                shopSearchIsBuying
                        ? "Buy price"
                        : "Sell price",
                PAD
                        + SHOP_SEARCH_ITEM_COL_WIDTH
                        + SHOP_SEARCH_OWNER_COL_WIDTH
                        + 20,
                listTop - 12,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                "Warp",
                this.width
                        - PAD
                        - SHOP_SEARCH_WARP_COL_WIDTH,
                listTop - 12,
                0xFF666666,
                false
        );

        int rowStart =
                listTop + 12;

        /*
         * Empty state.
         */
        if (filteredShopData.isEmpty()) {

            String message;

            if (shopDataList.isEmpty()) {

                message =
                        "No shop_data.csv rows could be loaded.";

            } else if (
                    shopSearchTerm == null
                            || shopSearchTerm.isBlank()
            ) {

                message =
                        "No shops found for the selected filters.";

            } else {

                message =
                        "No shops found for \""
                                + shopSearchTerm
                                + "\".";
            }

            ctx.drawText(
                    textRenderer,
                    message,
                    PAD,
                    rowStart + 8,
                    0xFF888888,
                    false
            );
        }

        /*
         * Clamp scrolling.
         */
        maxScroll =
                Math.max(
                        0,
                        filteredShopData.size()
                                - visibleRows
                );

        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        ShopData hovered =
                null;

        /*
         * Draw rows.
         */
        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    scrollOffset + i;

            if (idx >= filteredShopData.size()) {
                break;
            }

            ShopData shop =
                    filteredShopData.get(idx);

            int rowY =
                    rowStart
                            + i * ROW_HEIGHT;

            boolean hover =
                    mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= PAD
                            && mouseX <
                            this.width - PAD;

            if (hover) {

                ctx.fill(
                        PAD - 2,
                        rowY - 1,
                        this.width - PAD + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );

                hovered = shop;
            }

            /*
             * Item.
             */
            String itemName =
                    truncate(
                            shop.name,
                            SHOP_SEARCH_MAX_CHARS
                    );

            ctx.drawText(
                    textRenderer,
                    itemName,
                    PAD,
                    rowY + 2,
                    0xFFFFFFAA,
                    false
            );

            /*
             * Owner.
             */
            ctx.drawText(
                    textRenderer,
                    truncate(shop.owner, 20),
                    PAD
                            + SHOP_SEARCH_ITEM_COL_WIDTH
                            + 10,
                    rowY + 2,
                    0xFFCCCCCC,
                    false
            );

            /*
             * Price.
             */
            ctx.drawText(
                    textRenderer,
                    String.format(
                            Locale.US,
                            "$%.2f",
                            shop.price
                    ),
                    PAD
                            + SHOP_SEARCH_ITEM_COL_WIDTH
                            + SHOP_SEARCH_OWNER_COL_WIDTH
                            + 20,
                    rowY + 2,
                    shopSearchIsBuying
                            ? 0xFF55FF55
                            : 0xFFFF5555,
                    false
            );

            /*
             * Active / Other indicator.
             */
            String status =
                    shop.isActive
                            ? "Active"
                            : "Other";

            ctx.drawText(
                    textRenderer,
                    status,
                    PAD
                            + SHOP_SEARCH_ITEM_COL_WIDTH
                            + SHOP_SEARCH_OWNER_COL_WIDTH
                            + SHOP_SEARCH_PRICE_COL_WIDTH
                            + 35,
                    rowY + 2,
                    shop.isActive
                            ? 0xFF55FF55
                            : 0xFFFFAA55,
                    false
            );

            /*
             * Warp button.
             */
            if (!shop.warp.isBlank()) {

                String warpLabel =
                        shortWarp(
                                shop.warp,
                                12
                        );

                ButtonWidget warpBtn =
                        ButtonWidget.builder(
                                        Text.literal(
                                                warpLabel
                                        ),
                                        b ->
                                                runWarpCommandAndHighlight(
                                                        shop.warp
                                                )
                                )
                                .dimensions(
                                        this.width
                                                - PAD
                                                - SHOP_SEARCH_WARP_BTN_W,
                                        rowY,
                                        SHOP_SEARCH_WARP_BTN_W,
                                        16
                                )
                                .tooltip(
                                        Tooltip.of(
                                                Text.literal(
                                                        "Warp to: "
                                                                + shop.warp
                                                                + "\n"
                                                                + "Then run /findsign"
                                                )
                                        )
                                )
                                .build();

                addDrawableChild(
                        warpBtn
                );
            }
        }

        /*
         * Hover tooltip.
         */
        if (hovered != null) {

            List<Text> tip =
                    new ArrayList<>();

            tip.add(
                    Text.literal(
                            hovered.name
                    )
            );

            tip.add(
                    Text.literal(
                            "Owner: "
                                    + hovered.owner
                    )
            );

            tip.add(
                    Text.literal(
                            String.format(
                                    Locale.US,
                                    "Price: $%.2f",
                                    hovered.price
                            )
                    )
            );

            tip.add(
                    Text.literal(
                            "Action: "
                                    + (
                                    hovered.isBuying
                                            ? "BUYING"
                                            : "SELLING"
                            )
                    )
            );

            tip.add(
                    Text.literal(
                            "Status: "
                                    + (
                                    hovered.isActive
                                            ? "Active"
                                            : "Other"
                            )
                    )
            );

            tip.add(
                    Text.literal(
                            "Warp: "
                                    + (
                                    hovered.warp.isEmpty()
                                            ? "(none)"
                                            : hovered.warp
                            )
                    )
            );

            ctx.drawTooltip(
                    textRenderer,
                    tip,
                    mouseX,
                    mouseY
            );
        }

        ctx.drawText(
                textRenderer,
                "F8 · scroll",
                PAD,
                this.height - 18,
                0xFF666666,
                false
        );
    }

    // =========================================================================
    // IGNORE RENDER
    // =========================================================================

    private void renderIgnore(
            DrawContext ctx,
            int mouseX,
            int mouseY
    ) {

        ctx.drawText(
                textRenderer,
                "Ignore lists — excluded from Flips & Self-flip",
                PAD,
                28,
                0xFFAAAAAA,
                false
        );

        ctx.drawText(
                textRenderer,
                "Files in config/sunnyMod/",
                PAD,
                40,
                0xFF888888,
                false
        );

        int listTop =
                HEADER_H + 8;

        int listBottom =
                this.height - FOOTER_H - 4;

        int visibleRows =
                Math.max(
                        1,
                        (listBottom - listTop)
                                / ROW_HEIGHT
                );

        /*
         * Column headers.
         */
        ctx.drawText(
                textRenderer,
                "Items",
                IGNORE_COL_1_X,
                listTop - IGNORE_TEXT_VERTICAL_SPACING,
                0xFFAAAAAA,
                false
        );

        ctx.drawText(
                textRenderer,
                "Players",
                IGNORE_COL_2_X,
                listTop - IGNORE_TEXT_VERTICAL_SPACING,
                0xFFAAAAAA,
                false
        );

        ctx.drawText(
                textRenderer,
                "Warps",
                IGNORE_COL_3_X,
                listTop - IGNORE_TEXT_VERTICAL_SPACING,
                0xFFAAAAAA,
                false
        );

        /*
         * Highlight selected column.
         */
        int headerY =
                listTop
                        - IGNORE_TEXT_VERTICAL_SPACING
                        - 2;

        if (
                ignoreKind
                        == ProfitFinder.IgnoreKind.ITEMS
        ) {

            ctx.fill(
                    IGNORE_COL_1_X - 2,
                    headerY,
                    IGNORE_COL_1_X
                            + IGNORE_COL_WIDTH
                            + 2,
                    headerY + 12,
                    0x33FFFFFF
            );

        } else if (
                ignoreKind
                        == ProfitFinder.IgnoreKind.PLAYERS
        ) {

            ctx.fill(
                    IGNORE_COL_2_X - 2,
                    headerY,
                    IGNORE_COL_2_X
                            + IGNORE_COL_WIDTH
                            + 2,
                    headerY + 12,
                    0x33FFFFFF
            );

        } else {

            ctx.fill(
                    IGNORE_COL_3_X - 2,
                    headerY,
                    IGNORE_COL_3_X
                            + IGNORE_COL_WIDTH
                            + 2,
                    headerY + 12,
                    0x33FFFFFF
            );
        }

        /*
         * Items.
         */
        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    itemsScrollOffset + i;

            if (idx >= ignoreItems.size()) {
                break;
            }

            int rowY =
                    listTop + i * ROW_HEIGHT;

            String entry =
                    ignoreItems.get(idx);

            String displayEntry =
                    truncate(entry, 20);

            boolean hover =
                    mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= IGNORE_COL_1_X
                            && mouseX <
                            IGNORE_COL_1_X
                                    + IGNORE_COL_WIDTH;

            if (hover) {

                ctx.fill(
                        IGNORE_COL_1_X - 2,
                        rowY - 1,
                        IGNORE_COL_1_X
                                + IGNORE_COL_WIDTH
                                + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );
            }

            ctx.drawText(
                    textRenderer,
                    displayEntry,
                    IGNORE_COL_1_X,
                    rowY + 6,
                    0xFFFFFFFF,
                    false
            );

            int remColor =
                    hover
                            && mouseX >=
                            IGNORE_COL_1_X
                                    + IGNORE_COL_WIDTH
                                    - 50
                            ? 0xFFFF5555
                            : 0xFFAA6666;

            ctx.drawText(
                    textRenderer,
                    "Remove",
                    IGNORE_COL_1_X
                            + IGNORE_COL_WIDTH
                            - 45,
                    rowY + 6,
                    remColor,
                    false
            );
        }

        /*
         * Players.
         */
        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    playersScrollOffset + i;

            if (idx >= ignorePlayers.size()) {
                break;
            }

            int rowY =
                    listTop + i * ROW_HEIGHT;

            String entry =
                    ignorePlayers.get(idx);

            String displayEntry =
                    truncate(entry, 20);

            boolean hover =
                    mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= IGNORE_COL_2_X
                            && mouseX <
                            IGNORE_COL_2_X
                                    + IGNORE_COL_WIDTH;

            if (hover) {

                ctx.fill(
                        IGNORE_COL_2_X - 2,
                        rowY - 1,
                        IGNORE_COL_2_X
                                + IGNORE_COL_WIDTH
                                + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );
            }

            ctx.drawText(
                    textRenderer,
                    displayEntry,
                    IGNORE_COL_2_X,
                    rowY + 6,
                    0xFFFFFFFF,
                    false
            );

            int remColor =
                    hover
                            && mouseX >=
                            IGNORE_COL_2_X
                                    + IGNORE_COL_WIDTH
                                    - 50
                            ? 0xFFFF5555
                            : 0xFFAA6666;

            ctx.drawText(
                    textRenderer,
                    "Remove",
                    IGNORE_COL_2_X
                            + IGNORE_COL_WIDTH
                            - 45,
                    rowY + 6,
                    remColor,
                    false
            );
        }

        /*
         * Warps.
         */
        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    warpsScrollOffset + i;

            if (idx >= ignoreWarps.size()) {
                break;
            }

            int rowY =
                    listTop + i * ROW_HEIGHT;

            String entry =
                    ignoreWarps.get(idx);

            String displayEntry =
                    truncate(entry, 20);

            boolean hover =
                    mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= IGNORE_COL_3_X
                            && mouseX <
                            IGNORE_COL_3_X
                                    + IGNORE_COL_WIDTH;

            if (hover) {

                ctx.fill(
                        IGNORE_COL_3_X - 2,
                        rowY - 1,
                        IGNORE_COL_3_X
                                + IGNORE_COL_WIDTH
                                + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );
            }

            ctx.drawText(
                    textRenderer,
                    displayEntry,
                    IGNORE_COL_3_X,
                    rowY + 6,
                    0xFFFFFFFF,
                    false
            );

            int remColor =
                    hover
                            && mouseX >=
                            IGNORE_COL_3_X
                                    + IGNORE_COL_WIDTH
                                    - 50
                            ? 0xFFFF5555
                            : 0xFFAA6666;

            ctx.drawText(
                    textRenderer,
                    "Remove",
                    IGNORE_COL_3_X
                            + IGNORE_COL_WIDTH
                            - 45,
                    rowY + 6,
                    remColor,
                    false
            );
        }
    }

    // =========================================================================
    // UPDATE RENDER
    // =========================================================================

    private void renderUpdatePriorities(
            DrawContext ctx,
            int mouseX,
            int mouseY
    ) {

        ctx.drawText(
                textRenderer,
                "Warps to re-check — out of stock/space, ranked by payoff",
                PAD,
                28,
                0xFFCCCCCC,
                false
        );

        ctx.drawText(
                textRenderer,
                updatePriorities.size()
                        + " warps · click a row to warp there",
                PAD,
                40,
                0xFF888888,
                false
        );

        int listTop =
                HEADER_H + 4;

        int listBottom =
                this.height - FOOTER_H;

        int visibleRows =
                Math.max(
                        1,
                        (listBottom - listTop)
                                / ROW_HEIGHT
                );

        maxScroll =
                Math.max(
                        0,
                        updatePriorities.size()
                                - visibleRows
                );

        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        int hy =
                listTop;

        ctx.drawText(
                textRenderer,
                "Warp",
                PAD,
                hy,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                "Items",
                PAD + UPDATE_ITEMS_COL_WIDTH,
                hy,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                "Total edge",
                PAD
                        + UPDATE_ITEMS_COL_WIDTH
                        + UPDATE_EDGE_COL_WIDTH,
                hy,
                0xFF666666,
                false
        );

        ctx.drawText(
                textRenderer,
                "Age",
                PAD
                        + UPDATE_ITEMS_COL_WIDTH
                        + UPDATE_EDGE_COL_WIDTH
                        + UPDATE_AGE_COL_WIDTH,
                hy,
                0xFF666666,
                false
        );

        int rowStart =
                listTop + 12;

        if (updatePriorities.isEmpty()) {

            ctx.drawText(
                    textRenderer,
                    "Nothing stale right now — all tracked shops are Active.",
                    PAD,
                    rowStart + 8,
                    0xFF888888,
                    false
            );
        }

        /*
         * Do not call clearChildren()/init() here.
         *
         * The original implementation did that from render(),
         * which can rebuild widgets every frame. The widgets are
         * created once in init().
         */

        ProfitFinder.WarpSummary hovered =
                null;

        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    scrollOffset + i;

            if (idx >= updatePriorities.size()) {
                break;
            }

            ProfitFinder.WarpSummary w =
                    updatePriorities.get(idx);

            int rowY =
                    rowStart + i * ROW_HEIGHT;

            boolean hasWarp =
                    !w.warp.isBlank();

            boolean hover =
                    hasWarp
                            && mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= PAD
                            && mouseX <
                            this.width - PAD;

            if (hover) {

                ctx.fill(
                        PAD - 2,
                        rowY - 1,
                        this.width - PAD + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );

                hovered = w;
            }

            String warpName =
                    hasWarp
                            ? shortWarp(
                            w.warp,
                            UPDATE_WARP_NAME_MAX_CHARS
                    )
                            : "(no warp saved)";

            int nameColor =
                    hasWarp
                            ? 0xFFFFFFAA
                            : 0xFF777766;

            ctx.drawText(
                    textRenderer,
                    warpName,
                    PAD,
                    rowY + 2,
                    nameColor,
                    false
            );

            ctx.drawText(
                    textRenderer,
                    String.valueOf(
                            w.items.size()
                    ),
                    PAD + UPDATE_ITEMS_COL_WIDTH,
                    rowY + 2,
                    0xFFCCCCCC,
                    false
            );

            ctx.drawText(
                    textRenderer,
                    w.totalPotentialValue > 0
                            ? String.format(
                            Locale.US,
                            "+$%.2f",
                            w.totalPotentialValue
                    )
                            : "—",
                    PAD
                            + UPDATE_ITEMS_COL_WIDTH
                            + UPDATE_EDGE_COL_WIDTH,
                    rowY + 2,
                    w.totalPotentialValue > 0
                            ? 0xFF55FF55
                            : 0xFF666666,
                    false
            );

            ctx.drawText(
                    textRenderer,
                    String.format(
                            Locale.US,
                            "%.0fh",
                            w.maxAgeHours
                    ),
                    PAD
                            + UPDATE_ITEMS_COL_WIDTH
                            + UPDATE_EDGE_COL_WIDTH
                            + UPDATE_AGE_COL_WIDTH,
                    rowY + 2,
                    0xFFAAAAAA,
                    false
            );

            /*
             * Warp button.
             */
            if (hasWarp) {

                int warpBtnX =
                        this.width
                                - PAD
                                - UPDATE_WARP_BTN_W;

                ButtonWidget warpBtn =
                        ButtonWidget.builder(
                                        Text.literal(
                                                shortWarp(
                                                        w.warp,
                                                        UPDATE_WARP_NAME_MAX_CHARS
                                                )
                                        ),
                                        b ->
                                                runWarpCommandAndHighlight(
                                                        w.warp
                                                )
                                )
                                .dimensions(
                                        warpBtnX,
                                        rowY,
                                        UPDATE_WARP_BTN_W,
                                        16
                                )
                                .tooltip(
                                        Tooltip.of(
                                                Text.literal(
                                                        "Warp to: "
                                                                + w.warp
                                                                + "\n"
                                                                + "Then run /findsign"
                                                )
                                        )
                                )
                                .build();

                addDrawableChild(
                        warpBtn
                );
            }
        }

        if (hovered != null) {

            List<Text> tip =
                    new ArrayList<>();

            tip.add(
                    Text.literal(
                            hovered.warp.isEmpty()
                                    ? "(no warp saved)"
                                    : hovered.warp
                    )
            );

            tip.add(
                    Text.literal(
                            String.format(
                                    Locale.US,
                                    "%d stale items · total edge +$%.2f",
                                    hovered.items.size(),
                                    hovered.totalPotentialValue
                            )
                    )
            );

            tip.add(
                    Text.literal(
                            String.format(
                                    Locale.US,
                                    "Oldest scan %.0fh ago · worst streak %d unchanged rescans",
                                    hovered.maxAgeHours,
                                    hovered.maxNoChangeStreak
                            )
                    )
            );

            ctx.drawTooltip(
                    textRenderer,
                    tip,
                    mouseX,
                    mouseY
            );
        }

        ctx.drawText(
                textRenderer,
                "F8 · scroll",
                PAD,
                this.height - 18,
                0xFF666666,
                false
        );
    }

    // =========================================================================
    // TRADES RENDER
    // =========================================================================

    private void renderTrades(
            DrawContext ctx,
            int mouseX,
            int mouseY
    ) {

        String title =
                mode == Mode.SELF
                        ? "Self-flip (same owner buy > sell)"
                        : "Shop → Shop flips";

        ctx.drawText(
                textRenderer,
                title,
                PAD,
                28,
                0xFFCCCCCC,
                false
        );

        ctx.drawText(
                textRenderer,
                result.loadSummary,
                PAD,
                40,
                0xFF888888,
                false
        );

        String money;

        if (mode == Mode.SELF) {

            money =
                    String.format(
                            Locale.US,
                            "%d owner mistakes · best margin $%.2f/ea",
                            result.trades.size(),
                            result.trades.isEmpty()
                                    ? 0
                                    : result.trades
                                    .getFirst()
                                      .profitPerItem
                    );

        } else {

            money =
                    String.format(
                            Locale.US,
                            "Σ profit $%,.0f · capital $%,.0f · %d shown",
                            result.totalProfit,
                            result.totalCapital,
                            result.trades.size()
                    );
        }

        int listTop =
                HEADER_H + 4;

        int listBottom =
                this.height - FOOTER_H;

        int visibleRows =
                Math.max(
                        1,
                        (listBottom - listTop)
                                / ROW_HEIGHT
                );

        maxScroll =
                Math.max(
                        0,
                        result.trades.size()
                                - visibleRows
                );

        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        ctx.drawText(
                textRenderer,
                money,
                PAD,
                listTop - 12,
                0xFF88FF88,
                false
        );

        int hy =
                listTop;

        int itemColWidth =
                mode == Mode.SELF
                        ? SELF_ITEM_COL_WIDTH
                        : FLIPS_ITEM_COL_WIDTH;

        int ownerColWidth =
                mode == Mode.SELF
                        ? SELF_OWNER_COL_WIDTH
                        : 0;

        int profitColWidth =
                mode == Mode.SELF
                        ? SELF_PROFIT_COL_WIDTH
                        : FLIPS_PROFIT_COL_WIDTH;

        int warpsColWidth =
                mode == Mode.SELF
                        ? 0
                        : FLIPS_WARPS_COL_WIDTH;

        int warpBtnW =
                mode == Mode.SELF
                        ? SELF_WARP_BTN_W
                        : FLIPS_WARP_BTN_W;

        int itemNameMaxChars =
                mode == Mode.SELF
                        ? SELF_ITEM_NAME_MAX_CHARS
                        : FLIPS_ITEM_NAME_MAX_CHARS;

        int warpNameMaxChars =
                mode == Mode.SELF
                        ? SELF_WARP_NAME_MAX_CHARS
                        : FLIPS_WARP_NAME_MAX_CHARS;

        ctx.drawText(
                textRenderer,
                "Item",
                PAD,
                hy,
                0xFF666666,
                false
        );

        if (mode == Mode.SELF) {

            ctx.drawText(
                    textRenderer,
                    "Owner",
                    PAD + itemColWidth + 10,
                    hy,
                    0xFF666666,
                    false
            );

            ctx.drawText(
                    textRenderer,
                    "Margin",
                    this.width
                            - PAD
                            - profitColWidth,
                    hy,
                    0xFF666666,
                    false
            );

        } else {

            ctx.drawText(
                    textRenderer,
                    "Profit",
                    this.width
                            - PAD
                            - profitColWidth
                            - warpsColWidth
                            - 20,
                    hy,
                    0xFF666666,
                    false
            );

            ctx.drawText(
                    textRenderer,
                    "Warps",
                    this.width
                            - PAD
                            - warpsColWidth,
                    hy,
                    0xFF666666,
                    false
            );
        }

        int rowStart =
                listTop + 12;

        if (result.trades.isEmpty()) {

            String empty =
                    mode == Mode.SELF
                            ? "No same-owner buy>sell mistakes found."
                            : "No profitable flips (check shop_data / ignore lists).";

            ctx.drawText(
                    textRenderer,
                    empty,
                    PAD,
                    rowStart + 8,
                    0xFF888888,
                    false
            );
        }

        ProfitFinder.Trade hovered =
                null;

        for (int i = 0; i < visibleRows; i++) {

            int idx =
                    scrollOffset + i;

            if (idx >= result.trades.size()) {
                break;
            }

            ProfitFinder.Trade t =
                    result.trades.get(idx);

            int rowY =
                    rowStart + i * ROW_HEIGHT;

            boolean hover =
                    mouseY >= rowY
                            && mouseY < rowY + ROW_HEIGHT
                            && mouseX >= PAD
                            && mouseX <
                            this.width - PAD;

            if (hover) {

                ctx.fill(
                        PAD - 2,
                        rowY - 1,
                        this.width - PAD + 2,
                        rowY + ROW_HEIGHT - 2,
                        0x33FFFFFF
                );

                hovered = t;
            }

            String name =
                    truncate(
                            t.item,
                            itemNameMaxChars
                    );

            ctx.drawText(
                    textRenderer,
                    name,
                    PAD,
                    rowY + 2,
                    0xFFFFFFAA,
                    false
            );

            if (mode == Mode.SELF) {

                String owner =
                        truncate(
                                t.seller,
                                SELF_OWNER_NAME_MAX_CHARS
                        );

                ctx.drawText(
                        textRenderer,
                        owner,
                        PAD
                                + itemColWidth
                                + 10,
                        rowY + 2,
                        0xFFCCCCCC,
                        false
                );

                ctx.drawText(
                        textRenderer,
                        String.format(
                                Locale.US,
                                "$%.2f/ea",
                                t.profitPerItem
                        ),
                        this.width
                                - PAD
                                - profitColWidth,
                        rowY + 2,
                        0xFF55FF55,
                        false
                );

                int warpBtnX =
                        this.width
                                - PAD
                                - warpBtnW;

                if (!t.sellerWarp.isBlank()) {

                    ButtonWidget sellerWarpBtn =
                            ButtonWidget.builder(
                                            Text.literal(
                                                    shortWarp(
                                                            t.sellerWarp,
                                                            warpNameMaxChars
                                                    )
                                            ),
                                            b ->
                                                    runWarpCommand(
                                                            t.sellerWarp
                                                    )
                                    )
                                    .dimensions(
                                            warpBtnX,
                                            rowY,
                                            warpBtnW,
                                            16
                                    )
                                    .tooltip(
                                            Tooltip.of(
                                                    Text.literal(
                                                            "Warp to: "
                                                                    + t.sellerWarp
                                                    )
                                            )
                                    )
                                    .build();

                    addDrawableChild(
                            sellerWarpBtn
                    );
                }

            } else {

                ctx.drawText(
                        textRenderer,
                        String.format(
                                Locale.US,
                                "$%,.0f",
                                t.totalProfit
                        ),
                        this.width
                                - PAD
                                - profitColWidth
                                - warpsColWidth
                                - 20,
                        rowY + 2,
                        0xFF55FF55,
                        false
                );

                ctx.drawText(
                        textRenderer,
                        t.seller
                                + " → "
                                + t.buyer,
                        PAD,
                        rowY + 11,
                        0xFF666666,
                        false
                );

                int warpBtnX =
                        this.width
                                - PAD
                                - warpBtnW
                                - 10;

                if (!t.sellerWarp.isBlank()) {

                    ButtonWidget sellerWarpBtn =
                            ButtonWidget.builder(
                                            Text.literal(
                                                    shortWarp(
                                                            t.sellerWarp,
                                                            warpNameMaxChars
                                                    )
                                            ),
                                            b ->
                                                    runWarpCommand(
                                                            t.sellerWarp
                                                    )
                                    )
                                    .dimensions(
                                            warpBtnX
                                                    - warpBtnW
                                                    - 5,
                                            rowY,
                                            warpBtnW,
                                            16
                                    )
                                    .tooltip(
                                            Tooltip.of(
                                                    Text.literal(
                                                            "Warp to seller: "
                                                                    + t.sellerWarp
                                                    )
                                            )
                                    )
                                    .build();

                    addDrawableChild(
                            sellerWarpBtn
                    );
                }

                if (!t.buyerWarp.isBlank()) {

                    ButtonWidget buyerWarpBtn =
                            ButtonWidget.builder(
                                            Text.literal(
                                                    shortWarp(
                                                            t.buyerWarp,
                                                            warpNameMaxChars
                                                    )
                                            ),
                                            b ->
                                                    runWarpCommand(
                                                            t.buyerWarp
                                                    )
                                    )
                                    .dimensions(
                                            warpBtnX,
                                            rowY,
                                            warpBtnW,
                                            16
                                    )
                                    .tooltip(
                                            Tooltip.of(
                                                    Text.literal(
                                                            "Warp to buyer: "
                                                                    + t.buyerWarp
                                                    )
                                            )
                                    )
                                    .build();

                    addDrawableChild(
                            buyerWarpBtn
                    );
                }
            }
        }

        if (hovered != null) {

            List<Text> tip =
                    new ArrayList<>();

            tip.add(
                    Text.literal(
                            hovered.item
                    )
            );

            if (hovered.selfFlip) {

                tip.add(
                        Text.literal(
                                hovered.seller
                                        + " sells @ $"
                                        + String.format(
                                        Locale.US,
                                        "%.2f",
                                        hovered.sellPrice
                                )
                                        + " and buys @ $"
                                        + String.format(
                                        Locale.US,
                                        "%.2f",
                                        hovered.buyPrice
                                )
                        )
                );

                tip.add(
                        Text.literal(
                                String.format(
                                        Locale.US,
                                        "Margin $%.2f / item",
                                        hovered.profitPerItem
                                )
                        )
                );

                tip.add(
                        Text.literal(
                                "Warp: "
                                        + (
                                        hovered.sellerWarp.isEmpty()
                                                ? "(none)"
                                                : hovered.sellerWarp
                                )
                        )
                );

            } else {

                tip.add(
                        Text.literal(
                                String.format(
                                        Locale.US,
                                        "Qty: %d",
                                        hovered.quantity
                                )
                        ).formatted(
                                Formatting.GOLD
                        )
                );

                tip.add(
                        Text.literal(
                                String.format(
                                        Locale.US,
                                        "Buy @ $%.2f from %s",
                                        hovered.sellPrice,
                                        hovered.seller
                                )
                        ).formatted(
                                Formatting.GREEN
                        )
                );

                tip.add(
                        Text.literal(
                                String.format(
                                        Locale.US,
                                        "Sell @ $%.2f to %s",
                                        hovered.buyPrice,
                                        hovered.buyer
                                )
                        ).formatted(
                                Formatting.RED
                        )
                );

                tip.add(
                        Text.literal(
                                String.format(
                                        Locale.US,
                                        "Edge $%.2f/ea · total $%,.2f · capital $%,.2f",
                                        hovered.profitPerItem,
                                        hovered.totalProfit,
                                        hovered.capital
                                )
                        )
                );

                tip.add(
                        Text.literal(
                                "Warp buy: "
                                        + (
                                        hovered.sellerWarp.isEmpty()
                                                ? "(none)"
                                                : hovered.sellerWarp
                                )
                        )
                );

                tip.add(
                        Text.literal(
                                "Warp sell: "
                                        + (
                                        hovered.buyerWarp.isEmpty()
                                                ? "(none)"
                                                : hovered.buyerWarp
                                )
                        )
                );
            }

            ctx.drawTooltip(
                    textRenderer,
                    tip,
                    mouseX,
                    mouseY
            );
        }

        ctx.drawText(
                textRenderer,
                "F8 · scroll",
                PAD,
                this.height - 18,
                0xFF666666,
                false
        );
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static String truncate(
            String value,
            int maxChars
    ) {

        if (value == null) {
            return "";
        }

        if (maxChars <= 1) {
            return value.isEmpty()
                    ? ""
                    : "…";
        }

        return value.length() > maxChars
                ? value.substring(
                0,
                maxChars - 1
        ) + "…"
                : value;
    }

    private static String shortWarp(
            String warp,
            int maxChars
    ) {

        if (warp == null || warp.isBlank()) {
            return "—";
        }

        String s =
                warp.trim();

        if (s.startsWith("/warp ")) {
            s = s.substring(6).trim();

        } else if (s.startsWith("/home ")) {
            s = s.substring(6).trim();

        } else if (s.startsWith("warp ")) {
            s = s.substring(5).trim();

        } else if (s.startsWith("home ")) {
            s = s.substring(5).trim();
        }

        return truncate(
                s,
                maxChars
        );
    }

    // =========================================================================
    // SCREEN BEHAVIOUR
    // =========================================================================

    @Override
    public boolean shouldPause() {
        return false;
    }
}