package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerNutritionSavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.runtime.ToolsmithVillageEconomyRuntime;
import dev.totem.villagers.runtime.VillagerFarmerCompostingRuntime;
import dev.totem.villagers.runtime.VillagerFoodEconomyRuntime;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import java.util.List;

/** Fast deterministic model for food, currency and replacement-tool circulation. */
public final class VillageLongevityGameTest {
    /** Defaults to release regression coverage; an environment override is used for long soak probes. */
    private static final int SIMULATED_DAYS = simulatedDays();
    private static final int DIGESTS_PER_DAY = 24_000 / VillagerFoodEconomyRuntime.DIGEST_INTERVAL_TICKS;

    @GameTest(maxTicks = 40)
    public void coreVillageRemainsFedAndReplacesFarmerHoesForThirtyRenewableDays(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        BlockPos composter = helper.absolutePos(new BlockPos(4, 2, 4));
        Villager farmer = spawn(helper, new BlockPos(4, 3, 4), "minecraft:farmer");
        Villager miner = spawn(helper, new BlockPos(5, 3, 4), "totem:miner");
        Villager lumberjack = spawn(helper, new BlockPos(3, 3, 4), "totem:lumberjack");
        Villager toolsmith = spawn(helper, new BlockPos(6, 3, 4), "minecraft:toolsmith");
        List<Villager> village = List.of(farmer, miner, lumberjack, toolsmith);
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), 3);
            BlockPos furnace = helper.absolutePos(new BlockPos(5, 2, 3));
            BlockPos smithingTable = helper.absolutePos(new BlockPos(6, 2, 4));
            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
            level.setBlock(smithingTable, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), composter));
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), furnace));
            toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), smithingTable));
            MerchantOffers nonFoodOffers = new MerchantOffers();
            nonFoodOffers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD),
                    16, 2, 0.05F));
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(nonFoodOffers);

            for (Villager villager : village) {
                VillagerWorkInventory inventory = inventories.inventory(villager.getUUID());
                require(helper, inventory.insertExact(new ItemStack(Items.EMERALD, 8))
                                && inventory.insertExact(new ItemStack(Items.BREAD, 6)),
                        "Could not seed the finite natural-village starter kit");
                VillagerNutrition.setFoodLevel(villager, VillagerNutrition.MAX_FOOD_LEVEL);
            }

            WorkOrder wheatOrder = WorkOrderDefinitions.catalog().require("totem:farmer_wheat");
            FarmerWorldWorkAction harvest = new FarmerWorldWorkAction();
            VillagerWorkInventory farmerInventory = inventories.inventory(farmer.getUUID());
            require(helper, farmerInventory.insertExact(new ItemStack(Items.IRON_HOE))
                            && inventories.inventory(miner.getUUID()).insertExact(new ItemStack(Items.IRON_PICKAXE))
                            && inventories.inventory(lumberjack.getUUID()).insertExact(new ItemStack(Items.IRON_AXE))
                            && inventories.inventory(toolsmith.getUUID()).insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not seed the natural profession equipment in the finite starter kit");
            require(helper, inventories.inventory(lumberjack.getUUID()).insertExact(new ItemStack(Items.OAK_LOG, 64))
                            && inventories.inventory(miner.getUUID()).insertExact(new ItemStack(Items.COBBLESTONE, 64)),
                    "Could not seed renewable specialist output for the long-soak market");
            BlockPos[] crops = prepareField(helper);
            int foodTrades = 0;

            for (int day = 1; day <= SIMULATED_DAYS; day++) {
                for (BlockPos crop : crops) {
                    if (!harvest.hasUsableHoe(farmerInventory)) {
                        for (int pass = 0; pass < 8; pass++) {
                            ToolsmithVillageEconomyRuntime.tickForGameTest(server);
                        }
                    }
                    CropBlock wheat = (CropBlock) Blocks.WHEAT;
                    level.setBlock(crop, wheat.getStateForAge(wheat.getMaxAge()), 3);
                    require(helper, harvest.complete(level, farmer, crop, wheatOrder, farmerInventory),
                            "Farmer could not obtain and use a replacement hoe on simulated day " + day
                                    + "; " + economySnapshot(inventories, farmer, miner, lumberjack, toolsmith));
                }
                for (int tick = 0; tick < 6 * 41; tick++) {
                    VillagerWorkshopRuntime.tickForGameTest(server);
                }
                for (int compostStep = 0; compostStep < 24; compostStep++) {
                    VillagerFarmerCompostingRuntime.tickForGameTest(server);
                }
                for (int digest = 0; digest < DIGESTS_PER_DAY; digest++) {
                    village.forEach(VillagerNutrition::digest);
                    for (Villager villager : village) {
                        VillagerWorkInventory own = inventories.inventory(villager.getUUID());
                        if (VillagerNutrition.isHungry(villager)) {
                            VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(villager, own);
                        }
                        if (VillagerFoodEconomyRuntime.needsFoodRestock(villager, own)) {
                            boolean purchased = VillagerFoodEconomyRuntime.tryPurchaseFromFarmer(level, villager, farmer);
                            if (purchased) {
                                foodTrades++;
                            }
                            require(helper, purchased || !VillagerNutrition.isHungry(villager),
                                    professionId(villager) + " was hungry without food or a purchasable ration on day "
                                            + day + "; " + economySnapshot(inventories, farmer, miner, lumberjack, toolsmith));
                        }
                    }
                }
                require(helper, village.stream().allMatch(VillagerWorkNeeds::canWork),
                        "Village entered a hunger work stoppage on simulated day " + day);
            }

            require(helper, foodTrades > 0,
                    "Thirty-day cycle never reached a villager-to-villager emerald food trade after consuming starter rations");
            require(helper, count(farmerInventory, Items.BREAD) >= 4,
                    "Farmer failed to preserve its physical emergency food reserve");
            require(helper, count(inventories.inventory(miner.getUUID()), Items.EMERALD) > 0
                            && count(inventories.inventory(lumberjack.getUUID()), Items.EMERALD) > 0
                            && count(inventories.inventory(toolsmith.getUUID()), Items.EMERALD) > 0,
                    "A core worker exhausted all founding currency during the thirty-day cycle");
            require(helper, count(inventories.inventory(miner.getUUID()), Items.COBBLESTONE) < 64
                            && count(inventories.inventory(lumberjack.getUUID()), Items.OAK_LOG) < 64,
                    "Thirty-day cycle never entered both physical Toolsmith supplier markets");
            require(helper, count(farmerInventory, Items.WHEAT_SEEDS) <= 80
                            && count(farmerInventory, Items.WHEAT) <= 256,
                    "Farmer did not bound crop surplus through bread work and physical composting");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : village) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    private static BlockPos[] prepareField(GameTestHelper helper) {
        BlockPos[] crops = new BlockPos[9];
        int index = 0;
        for (int x = 7; x <= 9; x++) {
            for (int z = 3; z <= 5; z++) {
                BlockPos relative = new BlockPos(x, 2, z);
                helper.setBlock(relative.below(), Blocks.FARMLAND);
                crops[index++] = helper.absolutePos(relative);
            }
        }
        return crops;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        Identifier id = Identifier.parse(professionId);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(id);
        require(helper, profession != null, "Missing profession " + professionId);
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static String professionId(Villager villager) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value()).toString();
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static String economySnapshot(VillagerWorkInventorySavedData inventories, Villager farmer,
                                          Villager miner, Villager lumberjack, Villager toolsmith) {
        return "farmer=" + inventorySnapshot(inventories.inventory(farmer.getUUID()), farmer)
                + ", miner=" + inventorySnapshot(inventories.inventory(miner.getUUID()), miner)
                + ", lumberjack=" + inventorySnapshot(inventories.inventory(lumberjack.getUUID()), lumberjack)
                + ", toolsmith=" + inventorySnapshot(inventories.inventory(toolsmith.getUUID()), toolsmith);
    }

    private static String inventorySnapshot(VillagerWorkInventory inventory, Villager villager) {
        return "foodLevel:" + VillagerNutrition.foodLevel(villager)
                + "/emeralds:" + count(inventory, Items.EMERALD)
                + "/bread:" + count(inventory, Items.BREAD)
                + "/logs:" + count(inventory, Items.OAK_LOG)
                + "/stone:" + count(inventory, Items.COBBLESTONE)
                + "/stoneHoes:" + count(inventory, Items.STONE_HOE)
                + "/wheat:" + count(inventory, Items.WHEAT)
                + "/seeds:" + count(inventory, Items.WHEAT_SEEDS)
                + "/usedSlots:" + inventory.snapshot().stream().filter(stack -> !stack.isEmpty()).count();
    }

    private static int simulatedDays() {
        String configured = System.getenv("TOTEM_VILLAGE_LONGEVITY_DAYS");
        if (configured == null || configured.isBlank()) {
            return 30;
        }
        int days = Integer.parseInt(configured);
        if (days < 1 || days > 10_000) {
            throw new IllegalArgumentException("TOTEM_VILLAGE_LONGEVITY_DAYS must be between 1 and 10000");
        }
        return days;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
