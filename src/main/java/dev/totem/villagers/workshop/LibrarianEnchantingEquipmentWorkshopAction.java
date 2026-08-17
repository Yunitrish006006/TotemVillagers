package dev.totem.villagers.workshop;

import dev.totem.villagers.trade.LibrarianEnchantedEquipmentTrades;
import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Revalidates and publishes one exact equipment result from a Librarian's enchanting table. */
public final class LibrarianEnchantingEquipmentWorkshopAction implements ValidatedWorkshopAction {
    private static final int MAX_OUTPUT_REROLLS = 16;
    private static final double WORK_REACH_SQUARED = 16.0D;

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos enchantingTable;
    private final WorkOrder order;
    private final LibrarianEnchantingEquipmentRules.EquipmentDefinition definition;
    private final MerchantOffers offers;
    private final MerchantStock stock;
    private ItemStack produced = ItemStack.EMPTY;

    public LibrarianEnchantingEquipmentWorkshopAction(
            ServerLevel level, Villager villager, BlockPos enchantingTable, WorkOrder order,
            LibrarianEnchantingEquipmentRules.EquipmentDefinition definition, MerchantOffers offers, MerchantStock stock
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.enchantingTable = Objects.requireNonNull(enchantingTable, "enchantingTable");
        this.order = Objects.requireNonNull(order, "order");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.offers = Objects.requireNonNull(offers, "offers");
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    @Override
    public boolean complete() {
        if (!supports()) {
            return false;
        }
        int villagerLevel = villager.getVillagerData().level();
        for (int attempt = 0; attempt < MAX_OUTPUT_REROLLS; attempt++) {
            ItemStack candidate = LibrarianEnchantingRules.enchantEquipment(villager.getRandom(), definition.baseStack(),
                    level.registryAccess(), villagerLevel);
            if (candidate.isEmpty()) {
                continue;
            }
            StockVariantKey key = StockVariantKey.fromStack(candidate, level.registryAccess());
            if (stock.available(key) >= order.stockCap()) {
                continue;
            }
            if (!LibrarianEnchantedEquipmentTrades.registerProducedOffer(offers, stock, candidate, level.registryAccess())) {
                return false;
            }
            produced = candidate;
            villager.playWorkSound();
            return true;
        }
        return false;
    }

    @Override
    public WorkOrder completedOrder(WorkOrder scheduledOrder) {
        if (produced.isEmpty()) {
            return scheduledOrder;
        }
        return scheduledOrder.withOutputComponentPatch(StockVariantKey.fromStack(produced, level.registryAccess()).componentPatch());
    }

    private boolean supports() {
        var profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return villager.isAlive()
                && profession != null
                && "minecraft:librarian".equals(profession.toString())
                && order.id().equals(definition.orderId())
                && order.allowedSources().contains(dev.totem.villagers.work.WorkSource.ENCHANTING)
                && villager.getVillagerData().level() >= definition.minimumLibrarianLevel()
                && level.getBlockState(enchantingTable).is(Blocks.ENCHANTING_TABLE)
                && level.isLoaded(enchantingTable)
                && WorldWorkPermissions.mayWork(level, villager, enchantingTable)
                && villager.distanceToSqr(Vec3.atCenterOf(enchantingTable)) <= WORK_REACH_SQUARED;
    }
}
