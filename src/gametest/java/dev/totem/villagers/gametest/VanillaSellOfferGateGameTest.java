package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.trade.VillagerOfferSides;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.trade.InventoryDrivenProfessionTrades;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises the current Minecraft trade data rather than a hand-maintained list
 * of outputs. In enforced mode a legacy villager begins with no physical stock,
 * so it exposes no available sell row, while
 * vanilla villager purchase orders remain available. Some high-tier selections
 * now contain only generated enchanted equipment, which is deliberately moved
 * to Librarian table work and therefore leaves no retained row on that worker.
 */
public final class VanillaSellOfferGateGameTest {
    private static final List<String> VANILLA_PROFESSIONS = List.of(
            "farmer", "fisherman", "shepherd", "fletcher", "librarian", "cartographer", "cleric",
            "armorer", "weaponsmith", "toolsmith", "butcher", "leatherworker", "mason"
    );

    /**
     * Complete mode must work even when Minecraft rolled no sell rows at all:
     * each profession's deterministic lawful stock creates its own visible row,
     * while an unrelated inventory item never becomes merchandise.
     */
    @GameTest(maxTicks = 80)
    public void everyProfessionPublishesPhysicalLawfulStockWithoutARandomSellRoll(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        WorkBackedTradingSettingsSavedData settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        List<Villager> villagers = new ArrayList<>();
        try {
            for (int index = 0; index < VANILLA_PROFESSIONS.size(); index++) {
                String professionId = VANILLA_PROFESSIONS.get(index);
                Villager villager = spawnVillager(helper,
                        new BlockPos(1 + (index % 4) * 2, 2, 1 + (index / 4) * 2));
                villagers.add(villager);
                setMaximumLevelProfession(villager, professionId);

                MerchantOffer purchase = new MerchantOffer(new ItemCost(Items.WHEAT, 18),
                        new ItemStack(Items.EMERALD), 16, 1, 0.05F);
                MerchantOffers offers = new MerchantOffers();
                offers.add(purchase);
                ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);

                var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
                require(helper, inventory.insertExact(new ItemStack(Items.BREAD, 4)),
                        "Could not seed the survival reserve for " + professionId);
                require(helper, inventory.insertExact(new ItemStack(Items.DIRT, 1)),
                        "Could not seed unrelated inventory for " + professionId);
                require(helper, inventory.insertExact(new ItemStack(Items.EMERALD, 8)),
                        "Could not fund the purchase order for " + professionId);
                ItemStack merchandise = InventoryDrivenProfessionTrades.starterStock(villager, helper.getLevel())
                        .orElse(null);
                require(helper, merchandise != null && !merchandise.isEmpty(),
                        "No deterministic lawful stock exists for " + professionId);
                require(helper, inventory.insertExact(merchandise),
                        "Could not seed lawful stock for " + professionId);

                VillagerTradeStockAuthority.refreshOffers(villager, offers);
                require(helper, offers.contains(purchase) && !purchase.isOutOfStock(),
                        "Complete mode removed a player-to-villager purchase for " + professionId);
                require(helper, offers.stream().filter(VillagerOfferSides::isVillagerSellOffer)
                                .anyMatch(offer -> ItemStack.isSameItemSameComponents(offer.getResult(), merchandise)
                                        && !offer.isOutOfStock()),
                        "Physical lawful stock did not create a sell row for " + professionId
                                + ": " + merchandise.getItem());
                require(helper, offers.stream().noneMatch(offer -> offer.getResult().is(Items.DIRT)),
                        "Unlawful dirt inventory became merchandise for " + professionId);
            }
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : villagers) {
                VillagerWorkInventorySavedData.forServer(server).drain(villager.getUUID());
                villager.discard();
            }
        }
    }

    @GameTest(maxTicks = 40)
    public void everyCurrentVanillaProfessionExposesNoZeroStockSellOffers(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        WorkBackedTradingSettingsSavedData settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        List<Villager> villagers = new ArrayList<>();
        try {
            for (int index = 0; index < VANILLA_PROFESSIONS.size(); index++) {
                String professionId = VANILLA_PROFESSIONS.get(index);
                Villager villager = spawnVillager(helper, new BlockPos(1 + (index % 4) * 2, 2, 1 + (index / 4) * 2));
                villagers.add(villager);
                setMaximumLevelProfession(villager, professionId);

                MerchantOffers offers = villager.getOffers();
                require(helper, VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID())
                        .insertExact(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 64)),
                        "Could not fund " + professionId + " purchase offers");
                dev.totem.villagers.trade.VillagerTradeStockAuthority.refreshOffers(villager, offers);
                if (offers.isEmpty()) {
                    require(helper, ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers() == offers,
                            "Trade gate did not operate on the emptied " + professionId + " offer list");
                    continue;
                }
                require(helper, offers.stream().filter(VillagerOfferSides::isVillagerSellOffer).allMatch(offer -> offer.isOutOfStock()),
                        "Zero-stock " + professionId + " sell offer remained available");
                require(helper, offers.stream().filter(offer -> !VillagerOfferSides.isVillagerSellOffer(offer))
                                .noneMatch(offer -> offer.isOutOfStock()),
                        "Work-stock gate locked a vanilla " + professionId + " purchase order");
                require(helper, ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers() == offers,
                        "Trade gate did not operate on the live " + professionId + " offer list");
            }
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            villagers.forEach(Villager::discard);
        }
    }

    /**
     * A legacy offer without a current data-driven or offer-bound order must
     * remain locked even if a corrupted or migrated stock ledger contains that
     * item's ID. This is deliberately exercised against Minecraft's live offer
     * generation for every vanilla profession, not a hand-maintained output
     * list.
     */
    @GameTest(maxTicks = 80)
    public void everyCurrentVanillaProfessionRejectsUnmappedSellOffersWithInjectedStock(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        WorkBackedTradingSettingsSavedData settings = WorkBackedTradingSettingsSavedData.forServer(server);
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        List<Villager> villagers = new ArrayList<>();
        try {
            for (int index = 0; index < VANILLA_PROFESSIONS.size(); index++) {
                String professionId = VANILLA_PROFESSIONS.get(index);
                Villager villager = spawnVillager(helper, new BlockPos(1 + (index % 4) * 2, 2, 1 + (index / 4) * 2));
                villagers.add(villager);
                setMaximumLevelProfession(villager, professionId);
                MerchantOffers offers = villager.getOffers();
                seedEverySellResult(states, villager, offers, helper);
                VillagerTradeStockAuthority.refreshOffers(villager, offers);

                WorkOrderCatalog catalog = WorkOrderCatalogs.effectiveFor(
                        WorkOrderDefinitions.catalog(), "minecraft:" + professionId, offers, helper.getLevel());
                List<String> unlockedUnmapped = offers.stream()
                        .filter(VillagerOfferSides::isVillagerSellOffer)
                        .filter(offer -> !hasMappedOrder(catalog, offer, helper))
                        .filter(offer -> !offer.isOutOfStock())
                        .map(offer -> StockVariantKey.fromStack(offer.getResult(), helper.getLevel().registryAccess()).persistentString())
                        .distinct()
                        .sorted()
                        .toList();
                require(helper, unlockedUnmapped.isEmpty(), "Injected stock unlocked unmapped " + professionId
                        + " sell offers: " + String.join(", ", unlockedUnmapped));
            }
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : villagers) {
                states.remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    private static void seedEverySellResult(
            VillagerWorkSavedData states, Villager villager, MerchantOffers offers, GameTestHelper helper
    ) {
        Map<String, Integer> baseStock = new LinkedHashMap<>();
        Map<StockVariantKey, Integer> variantStock = new LinkedHashMap<>();
        for (MerchantOffer offer : offers) {
            if (!VillagerOfferSides.isVillagerSellOffer(offer)) {
                continue;
            }
            StockVariantKey key = StockVariantKey.fromStack(offer.getResult(), helper.getLevel().registryAccess());
            if (key.isBaseItem()) {
                baseStock.merge(key.itemId(), 64, Math::max);
            } else {
                variantStock.merge(key, 64, Math::max);
            }
        }
        VillagerWorkState current = states.getOrCreate(villager.getUUID());
        states.put(current.withStock(baseStock, variantStock, current.diagnostic()));
    }

    private static boolean hasMappedOrder(WorkOrderCatalog catalog, MerchantOffer offer, GameTestHelper helper) {
        StockVariantKey result = StockVariantKey.fromStack(offer.getResult(), helper.getLevel().registryAccess());
        return catalog.snapshot().values().stream().anyMatch(order -> order.outputKey().equals(result));
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setMaximumLevelProfession(Villager villager, String professionId) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", professionId)
        );
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
