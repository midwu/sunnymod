package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * F7 Auction House breakdown vs shop_data.
 *
 * Two modes (toggle button):
 *   CHEAP — AH price &lt; shop SELLING price (AH is a cheaper place to buy)
 *   FLIP  — AH price &lt; shop BUYING price (buy on AH, sell to a player shop)
 *
 * BUY_NOW rows are tinted differently from BID (BUY_NOW preferred for FLIP).
 */
public class AuctionProfitScreen extends Screen {

  private static final int ROW_HEIGHT = 20;
  private static final int HEADER_H   = 56;
  private static final int FOOTER_H   = 28;
  private static final int PAD        = 12;

  /** Which opportunity kind is shown. */
  public enum Mode {
    CHEAP("AH cheaper", "AH vs shop sell price"),
    FLIP("AH→Shop flip", "Buy AH, sell to shop BUYING");

    final String label;
    final String subtitle;
    Mode(String label, String subtitle) {
      this.label = label;
      this.subtitle = subtitle;
    }
  }

  public static final class Opp {
    /** "AH→Shop" (flip) or "AH cheaper" */
    public final String kind;
    public final String displayName;
    public final String vanillaName;
    public final String seller;
    public final String listingType; // BUY_NOW | BID
    public final double ahPrice;
    public final double shopPrice;
    public final double edge;
    public final String shopOwner;
    public final String shopWarp;
    public final int count;

    public Opp(String kind, String displayName, String vanillaName, String seller,
               String listingType, double ahPrice, double shopPrice, double edge,
               String shopOwner, String shopWarp, int count) {
      this.kind = kind;
      this.displayName = displayName;
      this.vanillaName = vanillaName;
      this.seller = seller;
      this.listingType = listingType != null ? listingType : "";
      this.ahPrice = ahPrice;
      this.shopPrice = shopPrice;
      this.edge = edge;
      this.shopOwner = shopOwner != null ? shopOwner : "";
      this.shopWarp = shopWarp != null ? shopWarp : "";
      this.count = count;
    }

    boolean isFlip() {
      return "AH→Shop".equals(kind);
    }

    boolean isBuyNow() {
      return "BUY_NOW".equalsIgnoreCase(listingType);
    }
  }

  private final List<Opp> allOpps;
  private final int listingCount;
  private final int newCount;
  private final int bidChangeCount;

  private Mode mode = Mode.CHEAP;
  private List<Opp> visible = List.of();
  private int scrollOffset = 0;
  private int maxScroll = 0;
  private ButtonWidget modeButton;

  public AuctionProfitScreen(List<Opp> opps, int listingCount, int newCount, int bidChangeCount) {
    super(Text.literal("Auction House"));
    this.allOpps = new ArrayList<>(opps != null ? opps : List.of());
    this.listingCount = listingCount;
    this.newCount = newCount;
    this.bidChangeCount = bidChangeCount;
    rebuildVisible();
  }

  private void rebuildVisible() {
    visible = allOpps.stream()
            .filter(o -> mode == Mode.FLIP ? o.isFlip() : !o.isFlip())
            .sorted(Comparator
                    // BUY_NOW first within mode (instant money / instant buy)
                    .comparing((Opp o) -> o.isBuyNow() ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble((Opp o) -> o.edge).reversed()))
            .collect(Collectors.toList());
    scrollOffset = 0;
    int listTop = HEADER_H;
    int listBottom = Math.max(listTop + ROW_HEIGHT, this.height - FOOTER_H);
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    maxScroll = Math.max(0, visible.size() - visibleRows);
  }

  @Override
  protected void init() {
    clearChildren();
    rebuildVisible();

    modeButton = ButtonWidget.builder(
                    Text.literal("Mode: " + mode.label),
                    b -> {
                      mode = (mode == Mode.CHEAP) ? Mode.FLIP : Mode.CHEAP;
                      b.setMessage(Text.literal("Mode: " + mode.label));
                      rebuildVisible();
                    })
            .dimensions(PAD, this.height - FOOTER_H + 4, 160, 20)
            .build();
    addDrawableChild(modeButton);

    addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
            .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
            .build());
  }

  @Override
  public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
    ctx.fill(0, 0, this.width, this.height, 0xCC000000);

    ctx.drawText(textRenderer, "Auction House vs shop_data", PAD, 8, 0xFFFFFFFF, false);
    ctx.drawText(textRenderer, mode.subtitle, PAD, 20, 0xFFAAAAAA, false);

    long flipN = allOpps.stream().filter(Opp::isFlip).count();
    long cheapN = allOpps.size() - flipN;
    String sub = String.format(Locale.US,
            "%d listings · showing %d/%d · %d flip · %d cheaper · %d new · %d bidΔ",
            listingCount, visible.size(),
            mode == Mode.FLIP ? flipN : cheapN,
            flipN, cheapN, newCount, bidChangeCount);
    ctx.drawText(textRenderer, sub, PAD, 34, 0xFF888888, false);

    int listTop = HEADER_H;
    int listBottom = this.height - FOOTER_H;
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

    if (visible.isEmpty()) {
      String empty = mode == Mode.FLIP
              ? "No AH→Shop flips on this page (shop BUYING ≤ AH price, or no match)."
              : "No cheaper-than-shop deals on this page.";
      ctx.drawText(textRenderer, empty, PAD, listTop + 8, 0xFF888888, false);
    }

    // Column headers
    int hy = listTop - 12;
    ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "Type", this.width / 2 - 70, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "AH", this.width / 2 - 10, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "Shop", this.width / 2 + 50, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "Edge", this.width - PAD - 90, hy, 0xFF666666, false);

    for (int i = 0; i < visibleRows; i++) {
      int idx = scrollOffset + i;
      if (idx >= visible.size()) break;
      Opp o = visible.get(idx);
      int rowY = listTop + i * ROW_HEIGHT;

      boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
              && mouseX >= PAD && mouseX < this.width - PAD;
      if (hover) {
        ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
      }

      // Name colour by listing type: BUY_NOW = bright green, BID = gold
      int nameColor = o.isBuyNow() ? 0xFF55FF55 : 0xFFFFCC55;
      String name = o.displayName;
      int maxNameW = this.width / 2 - 90;
      if (textRenderer.getWidth(name) > maxNameW) {
        while (textRenderer.getWidth(name + "…") > maxNameW && name.length() > 3) {
          name = name.substring(0, name.length() - 1);
        }
        name = name + "…";
      }
      ctx.drawText(textRenderer, name, PAD, rowY + 4, nameColor, false);

      // Type badge
      String typeLabel = o.isBuyNow() ? "BUY" : "BID";
      int typeColor = o.isBuyNow() ? 0xFF55FF55 : 0xFFFFAA00;
      ctx.drawText(textRenderer, typeLabel, this.width / 2 - 70, rowY + 4, typeColor, false);

      ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", o.ahPrice),
              this.width / 2 - 10, rowY + 4, 0xFFFFFFFF, false);
      ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", o.shopPrice),
              this.width / 2 + 50, rowY + 4, 0xFFFFFFFF, false);
      ctx.drawText(textRenderer, String.format(Locale.US, "+$%,.0f", o.edge),
              this.width - PAD - 90, rowY + 4, 0xFF55FF55, false);

      if (hover) {
        List<Text> tip = new ArrayList<>();
        tip.add(Text.literal(o.kind + " · " + o.listingType));
        tip.add(Text.literal("Seller: " + o.seller));
        tip.add(Text.literal(String.format(Locale.US,
                "AH $%,.2f  vs shop $%,.2f  (×%d)", o.ahPrice, o.shopPrice, o.count)));
        if (!o.shopOwner.isEmpty()) {
          String warp = o.shopWarp.isEmpty() ? "" : "  /" + o.shopWarp.replaceFirst("^/+", "");
          tip.add(Text.literal("Shop: " + o.shopOwner + warp));
        }
        tip.add(Text.literal("Vanilla: " + o.vanillaName));
        if (o.isFlip() && !o.isBuyNow()) {
          tip.add(Text.literal("§eBID — not instant; may be outbid"));
        }
        ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
      }
    }

    if (maxScroll > 0) {
      ctx.drawText(textRenderer, String.format("scroll %d/%d",
                      scrollOffset + 1, Math.min(scrollOffset + visibleRows, visible.size())),
              PAD + 170, this.height - FOOTER_H + 8, 0xFF666666, false);
    }

    // Legend
    ctx.drawText(textRenderer, "§aBUY_NOW §7instant  §eBID §7auction",
            this.width / 2 - 60, this.height - FOOTER_H + 8, 0xFFAAAAAA, false);

    super.render(ctx, mouseX, mouseY, delta);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
    if (vertical > 0 && scrollOffset > 0) {
      scrollOffset--;
      return true;
    }
    if (vertical < 0 && scrollOffset < maxScroll) {
      scrollOffset++;
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
  }

  @Override
  public boolean shouldPause() {
    return false;
  }
}