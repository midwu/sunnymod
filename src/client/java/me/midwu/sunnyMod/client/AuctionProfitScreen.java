package me.midwu.sunnyMod.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * F7 Auction House breakdown — opportunities vs shop_data, plus page summary.
 * Same layout language as ContainerWorthScreen (header / scroll rows / close).
 */
public class AuctionProfitScreen extends Screen {

  private static final int ROW_HEIGHT = 20;
  private static final int HEADER_H   = 52;
  private static final int FOOTER_H   = 28;
  private static final int PAD        = 12;

  public static final class Opp {
    public final String kind;       // "AH→Shop" | "AH cheaper"
    public final String displayName;
    public final String vanillaName;
    public final String seller;
    public final String listingType;
    public final double ahPrice;
    public final double shopPrice;
    public final double edge;       // profit or savings
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
      this.listingType = listingType;
      this.ahPrice = ahPrice;
      this.shopPrice = shopPrice;
      this.edge = edge;
      this.shopOwner = shopOwner != null ? shopOwner : "";
      this.shopWarp = shopWarp != null ? shopWarp : "";
      this.count = count;
    }
  }

  private final List<Opp> opps;
  private final int listingCount;
  private final int newCount;
  private final int bidChangeCount;
  private int scrollOffset = 0;
  private int maxScroll = 0;

  public AuctionProfitScreen(List<Opp> opps, int listingCount, int newCount, int bidChangeCount) {
    super(Text.literal("Auction House"));
    this.opps = new ArrayList<>(opps != null ? opps : List.of());
    this.opps.sort(Comparator.comparingDouble((Opp o) -> o.edge).reversed());
    this.listingCount = listingCount;
    this.newCount = newCount;
    this.bidChangeCount = bidChangeCount;
  }

  @Override
  protected void init() {
    clearChildren();
    scrollOffset = 0;
    int listTop = HEADER_H;
    int listBottom = this.height - FOOTER_H;
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
    maxScroll = Math.max(0, opps.size() - visibleRows);

    addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
            .dimensions(this.width - PAD - 80, this.height - FOOTER_H + 4, 80, 20)
            .build());
  }

  @Override
  public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
    // Dim background
    ctx.fill(0, 0, this.width, this.height, 0xCC000000);

    String title = "Auction House vs shop_data";
    ctx.drawText(textRenderer, title, PAD, 10, 0xFFFFFFFF, false);

    String sub = String.format(Locale.US,
            "%d listings on page · %d new · %d bid/price changes · %d opportunities",
            listingCount, newCount, bidChangeCount, opps.size());
    ctx.drawText(textRenderer, sub, PAD, 24, 0xFFAAAAAA, false);

    if (opps.isEmpty()) {
      ctx.drawText(textRenderer,
              "No shop_data edge on this page (common for custom/OP items).",
              PAD, HEADER_H + 8, 0xFF888888, false);
    }

    int listTop = HEADER_H;
    int listBottom = this.height - FOOTER_H;
    int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

    // Column headers
    int y = listTop - 12;
    ctx.drawText(textRenderer, "Item", PAD, y, 0xFF888888, false);
    ctx.drawText(textRenderer, "AH", this.width / 2 - 40, y, 0xFF888888, false);
    ctx.drawText(textRenderer, "Shop", this.width / 2 + 40, y, 0xFF888888, false);
    ctx.drawText(textRenderer, "Edge", this.width - PAD - 90, y, 0xFF888888, false);

    for (int i = 0; i < visibleRows; i++) {
      int idx = scrollOffset + i;
      if (idx >= opps.size()) break;
      Opp o = opps.get(idx);
      int rowY = listTop + i * ROW_HEIGHT;

      boolean hover = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
              && mouseX >= PAD && mouseX < this.width - PAD;
      if (hover) {
        ctx.fill(PAD - 2, rowY - 1, this.width - PAD + 2, rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
      }

      int nameColor = o.kind.startsWith("AH→") ? 0xFF55FF55 : 0xFF55FFFF;
      String name = o.displayName;
      if (textRenderer.getWidth(name) > this.width / 2 - 50) {
        while (textRenderer.getWidth(name + "…") > this.width / 2 - 50 && name.length() > 3) {
          name = name.substring(0, name.length() - 1);
        }
        name = name + "…";
      }
      ctx.drawText(textRenderer, name, PAD, rowY + 4, nameColor, false);

      String ah = String.format(Locale.US, "$%,.0f", o.ahPrice);
      String shop = String.format(Locale.US, "$%,.0f", o.shopPrice);
      String edge = String.format(Locale.US, "+$%,.0f", o.edge);
      ctx.drawText(textRenderer, ah, this.width / 2 - 40, rowY + 4, 0xFFFFFFFF, false);
      ctx.drawText(textRenderer, shop, this.width / 2 + 40, rowY + 4, 0xFFFFFFFF, false);
      ctx.drawText(textRenderer, edge, this.width - PAD - 90, rowY + 4, 0xFF55FF55, false);

      if (hover) {
        List<Text> tip = new ArrayList<>();
        tip.add(Text.literal(o.kind + " · " + o.listingType));
        tip.add(Text.literal("Seller: " + o.seller));
        tip.add(Text.literal(String.format(Locale.US, "AH $%,.2f  vs shop $%,.2f", o.ahPrice, o.shopPrice)));
        if (!o.shopOwner.isEmpty()) {
          tip.add(Text.literal("Shop: " + o.shopOwner
                  + (o.shopWarp.isEmpty() ? "" : "  /" + o.shopWarp.replaceFirst("^/+", ""))));
        }
        tip.add(Text.literal("Vanilla: " + o.vanillaName));
        ctx.drawTooltip(textRenderer, tip, mouseX, mouseY);
      }
    }

    if (maxScroll > 0) {
      String scrollHint = String.format("scroll %d/%d", scrollOffset + 1,
              Math.min(scrollOffset + visibleRows, opps.size()));
      ctx.drawText(textRenderer, scrollHint, PAD, this.height - FOOTER_H + 8, 0xFF666666, false);
    }

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