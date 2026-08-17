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
 * Modes (cycle with button):
 *   CHEAP   — AH &lt; shop SELLING (AH is a better buy)
 *   FLIP    — AH &lt; shop BUYING (buy AH, sell to shop)
 *   OVERPAY — AH &gt; shop SELLING (player shop is cheaper; don't buy AH)
 */
public class AuctionProfitScreen extends Screen {

  private static final int ROW_HEIGHT = 20;
  private static final int HEADER_H   = 56;
  private static final int FOOTER_H   = 28;
  private static final int PAD        = 12;

  public enum Mode {
    CHEAP("AH cheaper", "AH is cheaper than shop sell price"),
    FLIP("AH→Shop flip", "Buy on AH, sell to shop BUYING"),
    OVERPAY("Shop cheaper", "Player shop undercuts AH — you'd overpay");

    final String label;
    final String subtitle;
    Mode(String label, String subtitle) {
      this.label = label;
      this.subtitle = subtitle;
    }

    Mode next() {
      return switch (this) {
        case CHEAP -> FLIP;
        case FLIP -> OVERPAY;
        case OVERPAY -> CHEAP;
      };
    }
  }

  public static final class Opp {
    /** "AH→Shop" | "AH cheaper" | "Shop cheaper" */
    public final String kind;
    public final String displayName;
    public final String vanillaName;
    public final String seller;
    public final String listingType;
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

    boolean isFlip() { return "AH→Shop".equals(kind); }
    boolean isCheap() { return "AH cheaper".equals(kind); }
    boolean isOverpay() { return "Shop cheaper".equals(kind); }
    boolean isBuyNow() { return "BUY_NOW".equalsIgnoreCase(listingType); }
  }

  private final List<Opp> allOpps;
  private final int listingCount;
  private final int newCount;
  private final int bidChangeCount;

  private Mode mode = Mode.CHEAP;
  private List<Opp> visible = List.of();
  private int scrollOffset = 0;
  private int maxScroll = 0;

  public AuctionProfitScreen(List<Opp> opps, int listingCount, int newCount, int bidChangeCount) {
    super(Text.literal("Auction House"));
    this.allOpps = new ArrayList<>(opps != null ? opps : List.of());
    this.listingCount = listingCount;
    this.newCount = newCount;
    this.bidChangeCount = bidChangeCount;
    rebuildVisible();
  }

  private boolean matchesMode(Opp o) {
    return switch (mode) {
      case FLIP -> o.isFlip();
      case CHEAP -> o.isCheap();
      case OVERPAY -> o.isOverpay();
    };
  }

  private void rebuildVisible() {
    visible = allOpps.stream()
            .filter(this::matchesMode)
            .sorted(Comparator
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

    addDrawableChild(ButtonWidget.builder(
                    Text.literal("Mode: " + mode.label),
                    b -> {
                      mode = mode.next();
                      b.setMessage(Text.literal("Mode: " + mode.label));
                      rebuildVisible();
                    })
            .dimensions(PAD, this.height - FOOTER_H + 4, 170, 20)
            .build());

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
    long cheapN = allOpps.stream().filter(Opp::isCheap).count();
    long overN = allOpps.stream().filter(Opp::isOverpay).count();
    String sub = String.format(Locale.US,
            "%d listings · showing %d · %d flip · %d cheaper · %d overpay · %d new · %d bidΔ",
            listingCount, visible.size(), flipN, cheapN, overN, newCount, bidChangeCount);
    ctx.drawText(textRenderer, sub, PAD, 34, 0xFF888888, false);

    int listTop = HEADER_H;
    int listBottom = this.height - FOOTER_H;
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

    if (visible.isEmpty()) {
      String empty = switch (mode) {
        case FLIP -> "No AH→Shop flips on this page.";
        case CHEAP -> "No AH-cheaper-than-shop deals on this page.";
        case OVERPAY -> "No overpays — nothing on AH is pricier than a matching shop.";
      };
      ctx.drawText(textRenderer, empty, PAD, listTop + 8, 0xFF888888, false);
    }

    int hy = listTop - 12;
    ctx.drawText(textRenderer, "Item", PAD, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "Type", this.width / 2 - 70, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "AH", this.width / 2 - 10, hy, 0xFF666666, false);
    ctx.drawText(textRenderer, "Shop", this.width / 2 + 50, hy, 0xFF666666, false);
    String edgeHdr = mode == Mode.OVERPAY ? "Overpay" : "Edge";
    ctx.drawText(textRenderer, edgeHdr, this.width - PAD - 90, hy, 0xFF666666, false);

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

      // Name: BUY_NOW green, BID gold; overpay mode forces reddish name tint
      int nameColor;
      if (mode == Mode.OVERPAY) {
        nameColor = o.isBuyNow() ? 0xFFFF5555 : 0xFFFFAA55;
      } else {
        nameColor = o.isBuyNow() ? 0xFF55FF55 : 0xFFFFCC55;
      }
      String name = o.displayName;
      int maxNameW = this.width / 2 - 90;
      if (textRenderer.getWidth(name) > maxNameW) {
        while (textRenderer.getWidth(name + "…") > maxNameW && name.length() > 3) {
          name = name.substring(0, name.length() - 1);
        }
        name = name + "…";
      }
      ctx.drawText(textRenderer, name, PAD, rowY + 4, nameColor, false);

      String typeLabel = o.isBuyNow() ? "BUY" : "BID";
      int typeColor = o.isBuyNow() ? 0xFF55FF55 : 0xFFFFAA00;
      ctx.drawText(textRenderer, typeLabel, this.width / 2 - 70, rowY + 4, typeColor, false);

      ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", o.ahPrice),
              this.width / 2 - 10, rowY + 4, 0xFFFFFFFF, false);
      ctx.drawText(textRenderer, String.format(Locale.US, "$%,.0f", o.shopPrice),
              this.width / 2 + 50, rowY + 4, 0xFFFFFFFF, false);

      int edgeColor = mode == Mode.OVERPAY ? 0xFFFF5555 : 0xFF55FF55;
      String edgeStr = mode == Mode.OVERPAY
              ? String.format(Locale.US, "-$%,.0f", o.edge)
              : String.format(Locale.US, "+$%,.0f", o.edge);
      ctx.drawText(textRenderer, edgeStr, this.width - PAD - 90, rowY + 4, edgeColor, false);

      if (hover) {
        List<Text> tip = new ArrayList<>();
        tip.add(Text.literal(o.kind + " · " + o.listingType));
        tip.add(Text.literal("AH seller: " + o.seller));
        tip.add(Text.literal(String.format(Locale.US,
                "AH $%,.2f  vs shop $%,.2f  (×%d)", o.ahPrice, o.shopPrice, o.count)));
        if (!o.shopOwner.isEmpty()) {
          String warp = o.shopWarp.isEmpty() ? "" : "  /" + o.shopWarp.replaceFirst("^/+", "");
          tip.add(Text.literal("Shop: " + o.shopOwner + warp));
        }
        if (o.isOverpay()) {
          tip.add(Text.literal("§cBuy from the player shop instead of AH"));
        }
        tip.add(Text.literal("Vanilla: " + o.vanillaName));
        ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
      }
    }

    if (maxScroll > 0) {
      ctx.drawText(textRenderer, String.format("scroll %d/%d",
                      scrollOffset + 1, Math.min(scrollOffset + visibleRows, visible.size())),
              PAD + 180, this.height - FOOTER_H + 8, 0xFF666666, false);
    }

    ctx.drawText(textRenderer, "§aBUY §7/ §eBID",
            this.width / 2 - 20, this.height - FOOTER_H + 8, 0xFFAAAAAA, false);

    super.render(ctx, mouseX, mouseY, delta);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
    if (vertical > 0 && scrollOffset > 0) { scrollOffset--; return true; }
    if (vertical < 0 && scrollOffset < maxScroll) { scrollOffset++; return true; }
    return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
  }

  @Override
  public boolean shouldPause() { return false; }
}