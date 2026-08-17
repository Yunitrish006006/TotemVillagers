package dev.totem.villagers.trade;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeStockPolicyTest {
    private static final WorkOrder BREAD = new WorkOrder("totem:farmer_bread", "minecraft:farmer",
            new ItemAmount("minecraft:bread", 3), List.of(new ItemAmount("minecraft:wheat", 9)),
            Set.of(WorkSource.WORKSHOP), "", 20, 24);

    @Test
    void enforcedModeRejectsUnmappedAndEmptyOffersButAllowsProducedStock() {
        TradeStockPolicy policy = new TradeStockPolicy();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000601");
        VillagerWorkState empty = VillagerWorkState.empty(villager);
        VillagerWorkState stocked = new VillagerWorkState(1, villager, Map.of("minecraft:bread", 3), Map.of(),
                Optional.empty(), Optional.empty());

        assertEquals(OfferStockDecision.UNMAPPED, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, empty, "minecraft:bookshelf", 1));
        assertEquals(OfferStockDecision.INSUFFICIENT_STOCK, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, empty, "minecraft:bread", 1));
        assertEquals(OfferStockDecision.AVAILABLE, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, stocked, "minecraft:bread", 3));
        assertEquals(OfferStockDecision.VANILLA, policy.decide(WorkBackedTradingMode.DISABLED, catalog, empty, "minecraft:bookshelf", 1));
    }
}
