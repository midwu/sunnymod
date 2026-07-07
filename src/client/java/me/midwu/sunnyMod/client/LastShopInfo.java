package me.midwu.sunnyMod.client;

public class LastShopInfo {
    public final String owner;
    public final String item;
    public final int    stockSpace;
    public final double price;
    public final String action;
    public final String status;
    public final long   timestamp;

    public LastShopInfo(String owner, String item, int stockSpace,
                        double price, String action, String status) {
        this.owner      = owner;
        this.item       = item;
        this.stockSpace = stockSpace;
        this.price      = price;
        this.action     = action;
        this.status     = status;
        this.timestamp  = System.currentTimeMillis();
    }

    public boolean isOutOfStock() {
        return stockSpace <= 0
                || "out of stock".equalsIgnoreCase(status)
                || "out of space".equalsIgnoreCase(status);
    }
}