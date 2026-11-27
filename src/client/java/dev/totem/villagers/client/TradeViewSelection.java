package dev.totem.villagers.client;

public interface TradeViewSelection {
    int selectedTrade();
    int scrollOffset();
    boolean detailsOpen();
    void setTradeView(int selected, int scroll, boolean details);
}
