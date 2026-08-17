package dev.totem.villagers.trade;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.StockVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Pure policy for matching a vanilla offer result to a data-driven work order and produced stock. */
public final class TradeStockPolicy {
    public OfferStockDecision decide(
            WorkBackedTradingMode mode,
            WorkOrderCatalog catalog,
            VillagerWorkState state,
            String resultItemId,
            int resultCount
    ) {
        return decide(mode, catalog, state, StockVariantKey.base(resultItemId), resultCount);
    }

    /** Uses the exact server-side ItemStack components rather than trusting an item id alone. */
    public OfferStockDecision decide(
            WorkBackedTradingMode mode,
            WorkOrderCatalog catalog,
            VillagerWorkState state,
            ItemStack result,
            HolderLookup.Provider registries
    ) {
        if (result.isEmpty()) {
            return OfferStockDecision.UNMAPPED;
        }
        return decide(mode, catalog, state, StockVariantKey.fromStack(result, registries), result.getCount());
    }

    public OfferStockDecision decide(
            WorkBackedTradingMode mode,
            WorkOrderCatalog catalog,
            VillagerWorkState state,
            StockVariantKey resultKey,
            int resultCount
    ) {
        if (!mode.enforcesWorkBackedTrading()) {
            return OfferStockDecision.VANILLA;
        }
        if (resultCount < 1) {
            return OfferStockDecision.UNMAPPED;
        }
        boolean covered = false;
        for (WorkOrder order : catalog.snapshot().values()) {
            if (order.outputKey().equals(resultKey)) {
                covered = true;
                int available = resultKey.isBaseItem()
                        ? state.merchantStock().getOrDefault(resultKey.itemId(), 0)
                        : state.variantMerchantStock().getOrDefault(resultKey, 0);
                if (available >= resultCount) {
                    return OfferStockDecision.AVAILABLE;
                }
            }
        }
        return covered ? OfferStockDecision.INSUFFICIENT_STOCK : OfferStockDecision.UNMAPPED;
    }
}
