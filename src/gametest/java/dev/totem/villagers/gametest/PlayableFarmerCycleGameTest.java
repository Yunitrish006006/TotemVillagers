package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.trade.VillagerOfferSides;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

/**
 * The minimum live play loop: player-delivered recipe inputs are worked at a
 * real Composter and make that Farmer publish a matching physical-stock offer.
 */
public final class PlayableFarmerCycleGameTest {
    @GameTest(maxTicks = 80)
    public void playerMaterialsBecomeAnAvailableVanillaFarmerTrade(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        Villager farmer = spawnFarmer(helper, relativeJobSite.above());
        try {
            // Native AI normally supplies this memory when it claims the
            // Composter. The no-free-will test fixture supplies the same link.
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(helper.getLevel().dimension(), jobSite));
            VillagerNutrition.setFoodLevel(farmer, 20);
            MerchantOffers offers = farmer.getOffers();
            SupportedOffer production = supportedOffer(helper, offers, helper.getLevel());
            require(helper, ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$existingOffers() == offers,
                    "Farmer trade gate did not retain the generated vanilla offer list");
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.COOKED_BEEF, 3)),
                    "Could not seed a separate 20-point survival-food reserve");
            int requiredCycles = cyclesForOneTrade(production.offer(), production.order());
            seedPlayerMaterials(helper, inventory, production.order(), requiredCycles);

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerTradeStockAuthority.refreshOffers(farmer, offers);
            require(helper, production.offer().isOutOfStock(),
                    "Zero-stock " + production.order().output().itemId() + " was tradable before the Farmer completed work");

            // Work starts once and advances 40 ticks; use the production
            // runtime rather than a direct workshop commit so offer refresh,
            // scheduling and durable stock are all part of the proof.
            for (int tick = 0; tick < requiredCycles * (production.order().workTicks() + 1); tick++) {
                settings.setMode(WorkBackedTradingMode.ENFORCED);
                VillagerWorkshopRuntime.tickForGameTest(server);
            }

            int producedStock = count(inventory, item(production.order().output().itemId()));
            int expectedStock = production.order().output().count() * requiredCycles;
            require(helper, producedStock == expectedStock,
                    "Farmer did not credit the exact stock needed for one trade; stock=" + producedStock);
            for (ItemAmount input : production.order().requiredInputs()) {
                require(helper, count(inventory, item(input.itemId())) == 0,
                        "Farmer work did not consume the player-delivered input " + input.itemId());
            }
            MerchantOffer stockedOffer = offers.stream()
                    .filter(VillagerOfferSides::isVillagerSellOffer)
                    .filter(offer -> offer.getResult().getCount() == production.offer().getResult().getCount())
                    .filter(offer -> ItemStack.isSameItemSameComponents(
                            offer.getResult(), production.offer().getResult()))
                    .findFirst().orElse(null);
            require(helper, stockedOffer != null && !stockedOffer.isOutOfStock(),
                    "Completed stock did not publish the Farmer's matching sell row");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(farmer.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(farmer.getUUID());
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void villageBreadUsesTheLiveRecipeWithoutAPlayerFacingOffer(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        Villager farmer = spawnFarmer(helper, relativeJobSite.above());
        try {
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(helper.getLevel().dimension(), jobSite));
            VillagerNutrition.setFoodLevel(farmer, 20);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.GOLDEN_CARROT), 0, 1, 0.05F));
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not supply wheat to the Farmer's personal inventory");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            for (int tick = 0; tick < 41; tick++) {
                settings.setMode(WorkBackedTradingMode.ENFORCED);
                VillagerWorkshopRuntime.tickForGameTest(server);
            }

            require(helper, count(inventory, Items.BREAD) == 1,
                    "Farmer did not create physical village bread from the live player recipe");
            require(helper, count(inventory, Items.WHEAT) == 0,
                    "Farmer did not consume the exact three wheat required by the live bread recipe");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(farmer.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(farmer.getUUID());
            farmer.discard();
        }
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static SupportedOffer supportedOffer(GameTestHelper helper, MerchantOffers offers, net.minecraft.server.level.ServerLevel level) {
        for (MerchantOffer offer : offers) {
            if (!VillagerOfferSides.isVillagerSellOffer(offer) || offer.getResult().isEmpty()) {
                continue;
            }
            StockVariantKey result = StockVariantKey.fromStack(offer.getResult(), level.registryAccess());
            WorkOrder order = WorkOrderDefinitions.catalog().snapshot().values().stream()
                    .filter(candidate -> "minecraft:farmer".equals(candidate.professionId()))
                    .filter(candidate -> candidate.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP))
                    .filter(candidate -> candidate.outputKey().equals(result))
                    .findFirst().orElse(null);
            if (order != null) {
                return new SupportedOffer(offer, order);
            }
        }
        throw helper.assertionException("The generated Farmer offers had no recipe-backed sell output: "
                + offers.stream().filter(VillagerOfferSides::isVillagerSellOffer)
                .map(offer -> BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString())
                .distinct().sorted().toList());
    }

    private static int cyclesForOneTrade(MerchantOffer offer, WorkOrder order) {
        return (offer.getResult().getCount() + order.output().count() - 1) / order.output().count();
    }

    private static void seedPlayerMaterials(GameTestHelper helper, VillagerWorkInventory inventory, WorkOrder order, int cycles) {
        for (ItemAmount input : order.requiredInputs()) {
            require(helper, inventory.insertExact(new ItemStack(item(input.itemId()), input.count() * cycles)),
                    "Could not put player-delivered " + input.itemId() + " into the Farmer's personal inventory");
        }
    }

    private static Item item(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(
                itemId.substring(0, itemId.indexOf(':')), itemId.substring(itemId.indexOf(':') + 1)));
        if (item == null) {
            throw new IllegalStateException("Missing item " + itemId);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnFarmer(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession farmer = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        if (farmer == null) {
            throw new IllegalStateException("Missing minecraft:farmer profession");
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(farmer))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private record SupportedOffer(MerchantOffer offer, WorkOrder order) {
    }
}
