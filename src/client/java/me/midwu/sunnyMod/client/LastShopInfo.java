package me.midwu.sunnyMod.client;

public class LastShopInfo {
    public final String owner;
    public final String item;
    public final int stockSpace;
    public final double price;
    public final String action;
    public final String timestamp;   // Changed to String to match ShopLogger

    public LastShopInfo(String owner, String item, int stockSpace, double price,
                        String action, String timestamp) {
        this.owner = owner;
        this.item = item;
        this.stockSpace = stockSpace;
        this.price = price;
        this.action = action;
        this.timestamp = timestamp;
    }

    public boolean isOutOfStock() {
        return stockSpace <= 0;
    }
}