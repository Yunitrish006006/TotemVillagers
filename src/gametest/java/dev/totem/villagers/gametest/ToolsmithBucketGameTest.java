package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.trade.ToolsmithBucketTrades;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
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
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

/** Proves that the Fisherman's empty-bucket source is a stock-backed Toolsmith job. */
public final class ToolsmithBucketGameTest {
    @GameTest(maxTicks = 100)
    public void toolsmithTurnsThreeIronIngotsIntoOneSellableEmptyBucket(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.SMITHING_TABLE);
        Villager toolsmith = spawnToolsmith(helper, relativeJobSite.above());
        try {
            toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(helper.getLevel().dimension(), jobSite));
            VillagerNutrition.setFoodLevel(toolsmith, 20);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(toolsmith.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_INGOT, 3)),
                    "Could not supply the Toolsmith's three iron ingots");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            MerchantOffers offers = toolsmith.getOffers();
            VillagerTradeStockAuthority.refreshOffers(toolsmith, offers);
            MerchantOffer bucket = offers.stream().filter(ToolsmithBucketTrades::isManagedOffer).findFirst().orElse(null);
            require(helper, bucket == null, "Complete mode displayed an unmade empty bucket");

            WorkOrder order = WorkOrderDefinitions.catalog().require("totem:toolsmith_bucket");
            for (int tick = 0; tick <= order.workTicks(); tick++) {
                VillagerWorkshopRuntime.tickForGameTest(server);
            }

            int stock = inventory.countMatchingItem(new ItemStack(Items.BUCKET));
            bucket = offers.stream().filter(ToolsmithBucketTrades::isManagedOffer).findFirst().orElse(null);
            require(helper, stock == 1 && bucket != null && !bucket.isOutOfStock(),
                    "Toolsmith work did not unlock exactly one empty-bucket sale");
            require(helper, bucket.getBaseCostA().is(Items.EMERALD)
                            && bucket.getBaseCostA().getCount() == ToolsmithBucketTrades.EMERALD_PRICE,
                    "Toolsmith empty-bucket price is not the fixed two-emerald price");
            require(helper, inventory.snapshot().stream().noneMatch(stack -> stack.is(Items.IRON_INGOT)),
                    "Toolsmith made an empty bucket without consuming the three iron ingots");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(toolsmith.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(toolsmith.getUUID());
            toolsmith.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnToolsmith(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "toolsmith"));
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:toolsmith profession");
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
