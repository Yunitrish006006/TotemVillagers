package dev.totem.villagers.work;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.trade.OfferStockDecision;
import dev.totem.villagers.trade.TradeStockPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentAwareMerchantStockTest {
    @Test
    void componentBearingOutputsUseAnExactKeyForStockAndTrade() {
        StockVariantKey red = redHelmetKey();
        StockVariantKey blue = blueHelmetKey();

        assertFalse(red.isBaseItem());
        assertNotEquals(red, blue);

        MerchantStock stock = new MerchantStock(Map.of(), Map.of(red, 1));
        assertEquals(1, stock.available(red));
        assertEquals(0, stock.available(blue));
        assertTrue(stock.debitForTrade(red, 1));
        assertEquals(0, stock.available(red));
    }

    @Test
    void componentBearingOfferMustMatchTheOrderDescriptorExactly() {
        StockVariantKey red = redHelmetKey();
        StockVariantKey blue = blueHelmetKey();
        WorkOrder redHelmetOrder = new WorkOrder(
                "totem:leatherworker_red_helmet",
                "minecraft:leatherworker",
                new ItemAmount("minecraft:leather_helmet", 1),
                List.of(new ItemAmount("minecraft:leather", 5)),
                Set.of(WorkSource.WORKSHOP),
                "", "", "", red.componentPatch(), 20, 4
        );
        VillagerWorkState state = new VillagerWorkState(
                VillagerWorkState.CURRENT_SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                Map.of(), Map.of(red, 1), Optional.empty(), Optional.empty()
        );
        TradeStockPolicy policy = new TradeStockPolicy();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(redHelmetOrder));

        assertEquals(OfferStockDecision.AVAILABLE,
                policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, red, 1));
        assertEquals(OfferStockDecision.UNMAPPED,
                policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, blue, 1));
    }

    @Test
    void oversizedComponentDescriptorIsRejectedBeforeAnOrderCanLoad() {
        assertThrows(IllegalArgumentException.class, () -> new StockVariantKey("minecraft:leather_helmet", "x".repeat(32_769)));
    }

    private static StockVariantKey redHelmetKey() {
        return new StockVariantKey("minecraft:leather_helmet", "{minecraft:dyed_color:11546150}");
    }

    private static StockVariantKey blueHelmetKey() {
        return new StockVariantKey("minecraft:leather_helmet", "{minecraft:dyed_color:3949738}");
    }
}
