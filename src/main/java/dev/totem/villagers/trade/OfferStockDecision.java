package dev.totem.villagers.trade;

/** Server result for a vanilla sell offer; clients never choose this state. */
public enum OfferStockDecision {
    VANILLA,
    AVAILABLE,
    UNMAPPED,
    INSUFFICIENT_STOCK
}
