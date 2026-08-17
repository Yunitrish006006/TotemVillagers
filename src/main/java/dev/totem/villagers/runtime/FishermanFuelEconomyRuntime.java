package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.world.FishingRodUse;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;

/** Physical recurring solid-fuel purchase that closes the Fisherman-to-Miner side of the village economy. */
public final class FishermanFuelEconomyRuntime {
    private static final int PROCESS_INTERVAL_TICKS = 20;
    private static final int FUEL_TARGET = 1;
    private static final int FUEL_PRICE = 1;
    private static final double SEARCH_RANGE_SQUARED = 32.0D * 32.0D;
    private static final double EXCHANGE_REACH_SQUARED = 4.0D * 4.0D;

    private FishermanFuelEconomyRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PROCESS_INTERVAL_TICKS == 0) {
                process(server);
            }
        });
    }

    public static void tickForGameTest(MinecraftServer server) {
        process(server);
    }

    private static void process(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            villagers.stream().filter(villager -> isProfession(villager, "minecraft:fisherman"))
                    .forEach(fisherman -> buyOneFuel(fisherman, villagers, inventories));
        }
    }

    private static void buyOneFuel(Villager fisherman, List<? extends Villager> villagers,
                                   VillagerWorkInventorySavedData inventories) {
        if (!VillagerWorkNeeds.canWork(fisherman)) {
            return;
        }
        VillagerWorkInventory buyer = inventories.inventory(fisherman.getUUID());
        if (FishingRodUse.bestAvailable(buyer.snapshot()).isEmpty()
                || fuelCount(buyer) >= FUEL_TARGET) {
            return;
        }
        Villager miner = villagers.stream().filter(candidate -> candidate != fisherman)
                .filter(candidate -> isProfession(candidate, "totem:miner"))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> fisherman.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> saleFuel(inventories.inventory(candidate.getUUID())) != null)
                .min(Comparator.comparingDouble(fisherman::distanceToSqr)).orElse(null);
        if (miner == null) {
            return;
        }
        if (fisherman.distanceToSqr(miner) > EXCHANGE_REACH_SQUARED) {
            fisherman.getNavigation().moveTo(miner, .5D);
            return;
        }
        VillagerWorkInventory seller = inventories.inventory(miner.getUUID());
        Item fuel = saleFuel(seller);
        if (fuel != null) {
            if (VillageProductionStockPolicy.countItem(buyer, Items.EMERALD) >= FUEL_PRICE) {
                exchange(buyer, seller, fuel);
            } else {
                sponsoredExchange(fisherman, miner, villagers, inventories, fuel);
            }
        }
    }

    private static boolean exchange(VillagerWorkInventory buyer, VillagerWorkInventory seller, Item fuel) {
        ItemStack payment = new ItemStack(Items.EMERALD, FUEL_PRICE);
        ItemStack merchandise = new ItemStack(fuel);
        if (!buyer.canInsertExact(merchandise) || !seller.canInsertExact(payment)
                || buyer.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = seller.takeExactMatchingItem(merchandise).orElse(null);
        if (delivered == null) {
            buyer.insertExact(payment);
            return false;
        }
        if (!buyer.insertExact(delivered)) {
            seller.insertExact(delivered);
            buyer.insertExact(payment);
            return false;
        }
        if (!seller.insertExact(payment)) {
            throw new IllegalStateException("Fisherman fuel payment capacity changed during commit");
        }
        return true;
    }

    private static boolean sponsoredExchange(Villager recipient, Villager sellerVillager,
                                             List<? extends Villager> villagers,
                                             VillagerWorkInventorySavedData inventories, Item fuel) {
        Villager sponsor = villagers.stream()
                .filter(candidate -> candidate != recipient && candidate != sellerVillager)
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby())
                .filter(candidate -> candidate.distanceToSqr(sellerVillager) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> VillageProductionStockPolicy.countItem(
                        inventories.inventory(candidate.getUUID()), Items.EMERALD) >= FUEL_PRICE)
                .max(Comparator.comparingInt(candidate -> VillageProductionStockPolicy.countItem(
                        inventories.inventory(candidate.getUUID()), Items.EMERALD)))
                .orElse(null);
        if (sponsor == null) {
            return false;
        }
        if (sponsor.distanceToSqr(sellerVillager) > EXCHANGE_REACH_SQUARED) {
            sponsor.getNavigation().moveTo(sellerVillager, .5D);
            return false;
        }
        VillagerWorkInventory payer = inventories.inventory(sponsor.getUUID());
        VillagerWorkInventory recipientInventory = inventories.inventory(recipient.getUUID());
        VillagerWorkInventory seller = inventories.inventory(sellerVillager.getUUID());
        ItemStack payment = new ItemStack(Items.EMERALD, FUEL_PRICE);
        ItemStack merchandise = new ItemStack(fuel);
        if (!recipientInventory.canInsertExact(merchandise) || !seller.canInsertExact(payment)
                || payer.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = seller.takeExactMatchingItem(merchandise).orElse(null);
        if (delivered == null) {
            payer.insertExact(payment);
            return false;
        }
        if (!recipientInventory.insertExact(delivered)) {
            seller.insertExact(delivered);
            payer.insertExact(payment);
            return false;
        }
        if (!seller.insertExact(payment)) {
            throw new IllegalStateException("Sponsored Fisherman fuel payment capacity changed during commit");
        }
        return true;
    }

    private static int fuelCount(VillagerWorkInventory inventory) {
        return VillageProductionStockPolicy.countItem(inventory, Items.CHARCOAL)
                + VillageProductionStockPolicy.countItem(inventory, Items.COAL);
    }

    /** Never sells the Miner's last non-renewable coal before charcoal has been established. */
    private static Item saleFuel(VillagerWorkInventory inventory) {
        if (VillageProductionStockPolicy.countItem(inventory, Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        return VillageProductionStockPolicy.countItem(inventory, Items.COAL)
                > VillagerStarterSupplyRuntime.MINER_STARTING_COAL ? Items.COAL : null;
    }

    private static boolean isProfession(Villager villager, String expected) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && expected.equals(id.toString());
    }
}
