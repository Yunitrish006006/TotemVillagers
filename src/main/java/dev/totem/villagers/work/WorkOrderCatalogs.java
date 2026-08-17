package dev.totem.villagers.work;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Objects;

/**
 * Builds the effective order catalogue for one villager's current offers.
 *
 * <p>The base catalogue is data-pack owned, while a few vanilla rows carry
 * components that are only known after Minecraft generates the offer. Keeping
 * those extensions in one place makes the trade gate, scheduler and snapshot
 * describe exactly the same set of sellable outputs.</p>
 */
public final class WorkOrderCatalogs {
    private WorkOrderCatalogs() {
    }

    public static WorkOrderCatalog effectiveFor(
            WorkOrderCatalog baseCatalog, String professionId, MerchantOffers offers, ServerLevel level
    ) {
        Objects.requireNonNull(baseCatalog, "baseCatalog");
        Objects.requireNonNull(professionId, "professionId");
        Objects.requireNonNull(level, "level");
        return switch (professionId) {
            case "minecraft:leatherworker" -> LeatherworkerDyedArmorOrders.extend(
                    baseCatalog, offers, level.registryAccess());
            case "minecraft:farmer" -> FarmerSuspiciousStewOrders.extend(baseCatalog, offers, level);
            case "minecraft:fletcher" -> FletcherTippedArrowOrders.extend(baseCatalog, offers, level);
            case "minecraft:toolsmith" -> RemnantBackpackOrders.extend(baseCatalog, level);
            default -> baseCatalog;
        };
    }
}
