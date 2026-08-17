package dev.totem.villagers.gametest;

import dev.totem.villagers.TotemVillagers;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerNutritionSavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.runtime.ToolsmithVillageEconomyRuntime;
import dev.totem.villagers.runtime.VillagerFoodEconomyRuntime;
import dev.totem.villagers.runtime.VillageProductionStockPolicy;
import dev.totem.villagers.runtime.FishermanCampfireFuelSavedData;
import dev.totem.villagers.runtime.FishermanFuelEconomyRuntime;
import dev.totem.villagers.runtime.MinerCharcoalEconomyRuntime;
import dev.totem.villagers.runtime.MinerFurnaceMaintenanceSavedData;
import dev.totem.villagers.runtime.VillagerStarterSupplyRuntime;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.FishermanWorldWorkAction;
import dev.totem.villagers.world.FishermanWorkstation;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.world.ore.MinerOreSafetySavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Long-running physical economy for the smallest fishing-led village. */
public final class FishermanVillageLongevityGameTest {
    private static final int SIMULATED_DAYS = simulatedDays();
    private static final int NATURAL_MINE_Y = naturalMineY();
    private static final long ORE_ROLL_SEED = oreRollSeed();
    /**
     * A cooked catch takes 100 work ticks, so 48 successful cycles consume at
     * most 4,800 ticks of a normal work day. This is only a throughput guard:
     * the shared stock policy still stops fishing as soon as the bounded
     * 148-nutrition reserve for this four-adult village is full.
     */
    private static final int MAX_FISH_OUTPUT_PER_MARKET_PERIOD = 48;
    private static final int MAX_DAILY_STONE = 16;
    private static final int DIGESTS_PER_DAY = 24_000 / VillagerFoodEconomyRuntime.DIGEST_INTERVAL_TICKS;
    private static final int MAX_CATCH_ROLLS_PER_FISH = 128;
    private static final int MAX_TOOLSMITH_PASSES_PER_REPLACEMENT = 32;
    private static final int DAILY_TOOLSMITH_PASSES = 4;
    private static final TagKey<Block> MINER_TARGETS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_targets"));
    private static final TagKey<Block> LUMBERJACK_LOGS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "lumberjack_oak_logs"));

    @GameTest(maxTicks = 80)
    public void fishermanToolsmithLumberjackAndMinerCompletePhysicalCycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        int verticalOffset = NATURAL_MINE_Y - helper.absolutePos(new BlockPos(7, 2, 3)).getY();

        BlockPos barrel = helper.absolutePos(offsetY(new BlockPos(2, 2, 3), verticalOffset));
        BlockPos campfire = helper.absolutePos(offsetY(new BlockPos(1, 2, 3), verticalOffset));
        BlockPos water = helper.absolutePos(offsetY(new BlockPos(2, 2, 5), verticalOffset));
        BlockPos smithingTable = helper.absolutePos(offsetY(new BlockPos(4, 2, 3), verticalOffset));
        BlockPos furnace = helper.absolutePos(offsetY(new BlockPos(6, 2, 3), verticalOffset));
        BlockPos mineFace = helper.absolutePos(offsetY(new BlockPos(7, 2, 3), verticalOffset));
        BlockPos treeBase = helper.absolutePos(offsetY(new BlockPos(6, 2, 6), verticalOffset));
        BlockPos fibreTrellis = helper.absolutePos(offsetY(new BlockPos(4, 2, 5), verticalOffset));
        BlockPos lowerVine = fibreTrellis.west();
        BlockPos motherVine = lowerVine.above();

        preparePlatform(helper, verticalOffset);
        Villager fisherman = spawn(helper, offsetY(new BlockPos(2, 3, 3), verticalOffset), "minecraft:fisherman");
        Villager toolsmith = spawn(helper, offsetY(new BlockPos(4, 3, 3), verticalOffset), "minecraft:toolsmith");
        Villager miner = spawn(helper, offsetY(new BlockPos(6, 3, 3), verticalOffset), "totem:miner");
        Villager lumberjack = spawn(helper, offsetY(new BlockPos(4, 3, 6), verticalOffset), "totem:lumberjack");
        double minerHomeX = miner.getX();
        double minerHomeY = miner.getY();
        double minerHomeZ = miner.getZ();
        List<Villager> village = List.of(fisherman, toolsmith, miner, lumberjack);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        GeneratedVillageSavedData generatedVillages = GeneratedVillageSavedData.forServer(server);
        String simulatedVillageId = "game-test-renewable-economy-" + UUID.randomUUID();
        WorkZone renewableMine = new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(Math.min(furnace.getX(), mineFace.getX()) - 1, mineFace.getY() - 1,
                        Math.min(furnace.getZ(), mineFace.getZ()) - 1),
                new BlockCoordinate(Math.max(furnace.getX(), mineFace.getX()) + 1, mineFace.getY() + 2,
                        Math.max(furnace.getZ(), mineFace.getZ()) + 1));
        WorkZone renewableLumberyard = new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(treeBase.getX() - 2, treeBase.getY() - 1, treeBase.getZ() - 2),
                new BlockCoordinate(treeBase.getX() + 2, treeBase.getY() + 6, treeBase.getZ() + 2));
        WorkZoneRecord renewableMineRecord = assignments.createZone("totem:miner", renewableMine);
        WorkZoneRecord renewableLumberyardRecord = assignments.createZone("totem:lumberjack", renewableLumberyard);
        assignments.putAssignment(new WorkerAssignment(miner.getUUID(), "totem:miner",
                Optional.of(renewableMineRecord.id()), Optional.empty()));
        assignments.putAssignment(new WorkerAssignment(lumberjack.getUUID(), "totem:lumberjack",
                Optional.of(renewableLumberyardRecord.id()), Optional.empty()));
        generatedVillages.discover(new GeneratedVillageState(simulatedVillageId,
                level.dimension().identifier().toString(), renewableMine.minimum(), renewableMine.maximum(), false,
                Optional.of(renewableLumberyardRecord.id()), Optional.empty(), Optional.of(renewableMineRecord.id())));

        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            level.setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState(), 3);
            level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
            level.setBlock(smithingTable, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
            level.setBlock(mineFace, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(treeBase.below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(treeBase, Blocks.OAK_SAPLING.defaultBlockState(), 3);
            level.setBlock(fibreTrellis, Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
            level.setBlock(fibreTrellis.above(), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
            level.setBlock(fibreTrellis.above(2), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
            var attachedVine = Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
            level.setBlock(lowerVine, attachedVine, 3);
            level.setBlock(motherVine, attachedVine, 3);
            fisherman.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), barrel));
            toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), smithingTable));
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), furnace));
            require(helper, FishermanWorkstation.campfireForJobSite(level, barrel).isPresent(),
                    "Fisherman longevity fixture did not resolve its vanilla Barrel and a nearby Campfire: barrel="
                            + level.getBlockState(barrel) + ", campfire=" + level.getBlockState(campfire));

            village.forEach(VillagerStarterSupplyRuntime::grantGeneratedVillageBase);
            VillagerStarterSupplyRuntime.tickForGameTest(server);
            VillagerWorkInventory fishermanInventory = inventories.inventory(fisherman.getUUID());
            VillagerWorkInventory toolsmithInventory = inventories.inventory(toolsmith.getUUID());
            VillagerWorkInventory minerInventory = inventories.inventory(miner.getUUID());
            VillagerWorkInventory lumberjackInventory = inventories.inventory(lumberjack.getUUID());
            require(helper, FishingRodUse.bestAvailable(fishermanInventory.snapshot()).isPresent(),
                    "Fisherman did not receive its finite founding fishing rod");
            require(helper, count(toolsmithInventory, Items.STRING) == VillagerStarterSupplyRuntime.TOOLSMITH_STARTING_STRING,
                    "Toolsmith did not receive the finite founding string supply");
            require(helper, count(lumberjackInventory, Items.OAK_SAPLING) == 0,
                    "Generated rooted Lumberyard test unexpectedly began with a physical sapling");
            WorkOrder codOrder = WorkOrderDefinitions.catalog().require("totem:fisherman_cooked_cod");
            WorkOrder salmonOrder = WorkOrderDefinitions.catalog().require("totem:fisherman_cooked_salmon");
            WorkOrder stoneOrder = WorkOrderDefinitions.catalog().require("totem:miner_stone");
            WorkOrder logsOrder = WorkOrderDefinitions.catalog().require("totem:lumberjack_oak_logs");
            Random oreRolls = new Random(ORE_ROLL_SEED);
            MinerWorldWorkAction mining = new MinerWorldWorkAction(() -> oreRolls.nextInt(10_000));
            LumberjackWorldWorkAction logging = new LumberjackWorldWorkAction();
            Counters counters = new Counters();
            ActivitySnapshot tailStart = null;
            int tailStartDay = Math.max(1, SIMULATED_DAYS - 500);

            for (int day = 1; day <= SIMULATED_DAYS; day++) {
                if (day == tailStartDay) {
                    tailStart = counters.activity();
                }
                for (int mined = 0; mined < MAX_DAILY_STONE
                        && VillageProductionStockPolicy.needsWorldWork(
                        level, miner, minerInventory, stoneOrder); mined++) {
                    ensureTool(helper, server, minerInventory, toolsmithInventory, ToolFamily.PICKAXE, counters, day);
                    OreCounts before = oreCounts(minerInventory);
                    miner.setPos(mineFace.getX() + 0.5D, mineFace.getY(), mineFace.getZ() - 0.5D);
                    boolean minedFace = mining.complete(level, miner, mineFace, MINER_TARGETS, stoneOrder, minerInventory);
                    miner.setPos(minerHomeX, minerHomeY, minerHomeZ);
                    require(helper, minedFace,
                            "Miner could not extract its generated deep-seam stone on day " + day + "; "
                                    + snapshot(village, inventories));
                    require(helper, level.getBlockState(mineFace).is(Blocks.STONE),
                            "Generated deep-seam face was depleted on day " + day);
                    counters.credit(oreCounts(minerInventory).minus(before));
                    counters.stone++;
                }
                if (VillageProductionStockPolicy.needsWorldWork(
                        level, lumberjack, lumberjackInventory, logsOrder)) {
                    ensureTool(helper, server, lumberjackInventory, toolsmithInventory, ToolFamily.AXE, counters, day);
                    require(helper, level.getBlockState(treeBase).is(Blocks.OAK_SAPLING),
                            "Natural tree-growth compression found no replanted sapling on day " + day);
                    growTree(level, treeBase);
                    require(helper, logging.complete(level, lumberjack, renewableLumberyard, treeBase, LUMBERJACK_LOGS,
                                    logsOrder, lumberjackInventory),
                            "Lumberjack could not harvest the renewable tree on day " + day + "; "
                                    + snapshot(village, inventories));
                    counters.trees++;
                }
                requireEmeraldConservation(helper, village, inventories, day, "world work");

                int cobblestoneBeforeCharcoal = count(minerInventory, Items.COBBLESTONE);
                for (int charcoalPass = 0; charcoalPass < 3; charcoalPass++) {
                    counters.charcoalBatches += MinerCharcoalEconomyRuntime.tickForGameTest(server);
                }
                int maintenanceStone = Math.max(0,
                        cobblestoneBeforeCharcoal - count(minerInventory, Items.COBBLESTONE));
                counters.furnaceReplacements += maintenanceStone / 8;
                requireEmeraldConservation(helper, village, inventories, day, "charcoal maintenance");
                int minerEmeraldsBeforeReserveTrade = count(minerInventory, Items.EMERALD);
                int fishermanEmeraldsBeforeToolsmith = count(fishermanInventory, Items.EMERALD);
                long fishermanRodsBeforeToolsmith = FishingRodUse.usableCount(fishermanInventory.snapshot());
                for (int toolsmithPass = 0; toolsmithPass < DAILY_TOOLSMITH_PASSES; toolsmithPass++) {
                    tickToolsmithAndCountFibre(server, toolsmithInventory, counters);
                    requireEmeraldConservation(helper, village, inventories, day,
                            "toolsmith pass " + toolsmithPass);
                }
                if (FishingRodUse.usableCount(fishermanInventory.snapshot()) > fishermanRodsBeforeToolsmith
                        && count(fishermanInventory, Items.EMERALD) < fishermanEmeraldsBeforeToolsmith) {
                    counters.rodPurchases++;
                }
                counters.mineralReserveRevenue += Math.max(0,
                        count(minerInventory, Items.EMERALD) - minerEmeraldsBeforeReserveTrade);
                requireEmeraldConservation(helper, village, inventories, day, "toolsmith economy");
                int minerEmeraldsBeforeFuelTrade = count(minerInventory, Items.EMERALD);
                for (int fuelPass = 0; fuelPass < 2; fuelPass++) {
                    FishermanFuelEconomyRuntime.tickForGameTest(server);
                }
                counters.fuelTrades += Math.max(0,
                        count(minerInventory, Items.EMERALD) - minerEmeraldsBeforeFuelTrade);
                requireEmeraldConservation(helper, village, inventories, day, "fuel market");

                for (int digest = 0; digest < DIGESTS_PER_DAY; digest++) {
                    // Runtime fishing progresses continuously between the 6,000-tick nutrition pulses. Refill the
                    // same bounded reserve before each compressed market period instead of applying four digests
                    // after one artificial daily production burst.
                    produceDailyFish(helper, level, water, fisherman, toolsmith, village,
                            fishermanInventory, toolsmithInventory, minerInventory,
                            codOrder, salmonOrder, counters, day);
                    requireEmeraldConservation(helper, village, inventories, day,
                            "fishing work period " + digest);
                    for (Villager villager : village) {
                        VillagerNutrition.digest(villager);
                        VillagerWorkInventory own = inventories.inventory(villager.getUUID());
                        if (VillagerNutrition.isHungry(villager)) {
                            VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(villager, own);
                        }
                        if (villager != fisherman && VillagerFoodEconomyRuntime.needsFoodRestock(villager, own)) {
                            boolean purchased = VillagerFoodEconomyRuntime.tryPurchaseFromFoodProducer(
                                    level, villager, fisherman);
                            if (purchased) {
                                counters.foodTrades++;
                            }
                            requireEmeraldConservation(helper, village, inventories, day,
                                    "food market for " + professionId(villager));
                            require(helper, purchased || !VillagerNutrition.isHungry(villager),
                                    professionId(villager) + " could not buy the Fisherman's catch on day " + day
                                            + "; " + snapshot(village, inventories));
                        }
                    }
                }
                require(helper, village.stream().allMatch(VillagerWorkNeeds::canWork),
                        "Fishing-led village entered a hunger stoppage on day " + day + "; "
                                + snapshot(village, inventories));
                require(helper, level.getBlockState(lowerVine).is(Blocks.VINE)
                                && level.getBlockState(motherVine).is(Blocks.VINE),
                        "The generated fibre trellis was depleted on day " + day);
                requireEmeraldConservation(helper, village, inventories, day, "end of day");
                observeBounds(helper, level, village, inventories, fisherman, miner, lumberjack,
                        fishermanInventory, minerInventory, lumberjackInventory, stoneOrder, logsOrder, counters, day);
            }

            require(helper, counters.foodTrades > 0, "No villager ever bought the Fisherman's physical cooked fish");
            require(helper, SIMULATED_DAYS < 120 || counters.rodPurchases > 0,
                    "Fisherman's founding rod never entered the Toolsmith replacement loop");
            require(helper, counters.cookedFish > 0,
                    "Demand-driven Fisherman never produced a physical cooked catch");
            require(helper, counters.stone > 0 && counters.trees > 0,
                    "Demand-driven Miner or Lumberjack never established a reserve");
            require(helper, SIMULATED_DAYS < 120 || counters.fibreClippings > 0,
                    "Long village simulation never clipped its renewable vine-fibre trellis");
            require(helper, SIMULATED_DAYS < 120 || counters.charcoalBatches > 0,
                    "Long village simulation never converted Lumberjack logs into renewable charcoal");
            require(helper, SIMULATED_DAYS < 120 || counters.fuelTrades > 0,
                    "Long village simulation never paid the Miner for physical Campfire coal");
            require(helper, totalEmeralds(village, inventories) == village.size() * VillagerStarterSupplyRuntime.STARTING_EMERALDS,
                    "Internal trades created or destroyed physical emeralds");
            if (SIMULATED_DAYS >= 500) {
                ActivitySnapshot tail = tailStart == null ? new ActivitySnapshot(0, 0, 0, 0, 0, 0, 0, 0) : tailStart;
                require(helper, counters.cookedFish > tail.cookedFish()
                                && counters.foodTrades > tail.foodTrades()
                                && counters.stone > tail.stone()
                                && counters.trees > tail.trees()
                                && counters.charcoalBatches > tail.charcoalBatches()
                                && counters.furnaceReplacements > tail.furnaceReplacements()
                                && counters.fuelTrades > tail.fuelTrades()
                                && counters.toolCycles() > tail.toolCycles(),
                        "The final 500-day window was not a live steady-state cycle; start=" + tail
                                + ", end=" + counters.activity() + "; " + snapshot(village, inventories));
            }
            String finalSnapshot = snapshot(village, inventories);
            TotemVillagers.LOGGER.info("Four-role fishing village survived {} days at mineY={} with oreSeed={}: "
                            + "catches={}, bycatchRolls={}, retainedBycatch={}, ignoredBycatch={}, caughtString={}, "
                            + "caughtRods={}, foodTrades={}, "
                            + "rodPurchases={}, pickaxePurchases={iron:{},stone:{}}, "
                            + "copperPickaxes={}, axePurchases={iron:{},copper:{},stone:{}}, stone={}, trees={}, "
                            + "fibreClippings={}, charcoalBatches={}, furnaceReplacements={}, "
                            + "reserveMineralRevenue={}, fuelTrades={}, "
                            + "incidentalDrops={}, final={}",
                    SIMULATED_DAYS, mineFace.getY(), ORE_ROLL_SEED, counters.cookedFish, counters.bycatchRolls,
                    counters.retainedBycatch, counters.ignoredBycatch, counters.caughtString, counters.caughtRods,
                    counters.foodTrades, counters.rodPurchases, counters.ironPickaxes, counters.stonePickaxes,
                    counters.copperPickaxes, counters.ironAxes, counters.copperAxes, counters.stoneAxes, counters.stone,
                    counters.trees, counters.fibreClippings, counters.charcoalBatches, counters.furnaceReplacements,
                    counters.mineralReserveRevenue, counters.fuelTrades,
                    counters.oreSummary(), finalSnapshot);
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : village) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                FishermanCampfireFuelSavedData.forServer(server).remove(villager.getUUID());
                MinerFurnaceMaintenanceSavedData.forServer(server).remove(villager.getUUID());
                MinerOreSafetySavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
            assignments.removeAssignment(miner.getUUID());
            assignments.removeAssignment(lumberjack.getUUID());
            assignments.removeZone(renewableMineRecord.id());
            assignments.removeZone(renewableLumberyardRecord.id());
            generatedVillages.remove(simulatedVillageId);
        }
    }

    private static void produceDailyFish(GameTestHelper helper, ServerLevel level, BlockPos water,
                                         Villager fisherman, Villager toolsmith, List<Villager> village,
                                         VillagerWorkInventory inventory,
                                         VillagerWorkInventory toolsmithInventory,
                                         VillagerWorkInventory minerInventory,
                                         WorkOrder codOrder, WorkOrder salmonOrder, Counters counters, int day) {
        int produced = 0;
        int rolls = 0;
        FishermanWorldWorkAction action = new FishermanWorldWorkAction();
        while (produced < MAX_FISH_OUTPUT_PER_MARKET_PERIOD
                && rolls < MAX_FISH_OUTPUT_PER_MARKET_PERIOD * MAX_CATCH_ROLLS_PER_FISH) {
            WorkOrder order = List.of(codOrder, salmonOrder).stream()
                    .filter(candidate -> VillageProductionStockPolicy.needsWorldWork(
                            level, fisherman, inventory, candidate))
                    .min(java.util.Comparator.comparingInt(candidate -> count(inventory,
                            BuiltInRegistries.ITEM.getValue(Identifier.parse(candidate.output().itemId())))))
                    .orElse(null);
            if (order == null) {
                break;
            }
            if (FishingRodUse.bestAvailable(inventory.snapshot()).isEmpty()) {
                int emeraldsBefore = count(inventory, Items.EMERALD);
                for (int pass = 0; pass < MAX_TOOLSMITH_PASSES_PER_REPLACEMENT
                        && FishingRodUse.bestAvailable(inventory.snapshot()).isEmpty(); pass++) {
                    tickToolsmithAndCountFibre(level.getServer(), toolsmithInventory, counters);
                }
                if (FishingRodUse.bestAvailable(inventory.snapshot()).isPresent()
                        && count(inventory, Items.EMERALD) < emeraldsBefore) {
                    counters.rodPurchases++;
                }
            }
            ItemStack rod = FishingRodUse.nextForWork(inventory.snapshot()).orElse(null);
            require(helper, rod != null,
                    "Fisherman could not obtain a replacement rod on day " + day
                            + "; fishermanEmeralds=" + count(inventory, Items.EMERALD)
                            + ", fishermanString=" + count(inventory, Items.STRING)
                            + ", toolsmithString=" + count(VillagerWorkInventorySavedData.forServer(level.getServer())
                            .inventory(toolsmith.getUUID()), Items.STRING)
                            + ", toolsmithRods=" + count(VillagerWorkInventorySavedData.forServer(level.getServer())
                            .inventory(toolsmith.getUUID()), Items.FISHING_ROD)
                            + ", toolsmithVine=" + count(toolsmithInventory, Items.VINE)
                            + ", toolsmithShears=" + count(VillagerWorkInventorySavedData.forServer(level.getServer())
                            .inventory(toolsmith.getUUID()), Items.SHEARS)
                            + ", toolsmithSticks=" + count(VillagerWorkInventorySavedData.forServer(level.getServer())
                            .inventory(toolsmith.getUUID()), Items.STICK)
                            + ", caughtString=" + counters.caughtString + ", caughtRods=" + counters.caughtRods
                            + ", purchasedRods=" + counters.rodPurchases + ", bycatchRolls=" + counters.bycatchRolls
                            + ", usedSlots=" + inventory.snapshot().stream().filter(stack -> !stack.isEmpty()).count()
                            + "; village=" + snapshot(village,
                            VillagerWorkInventorySavedData.forServer(level.getServer())));
            rolls++;
            FishermanWorldWorkAction.FishingAttempt attempt = action.attempt(level, fisherman, water, order);
            if (!attempt.caughtAnything()) {
                continue;
            }
            List<ItemStack> returned = new ArrayList<>();
            attempt.orderOutput().ifPresent(returned::add);
            List<ItemStack> retained = attempt.bycatch().stream()
                    .filter(stack -> VillageProductionStockPolicy.mayRetainFishingBycatch(
                            level, fisherman, inventory, stack))
                    .map(ItemStack::copy).toList();
            returned.addAll(retained);
            int cooked = (int) returned.stream().filter(stack -> stack.is(Items.COOKED_COD)
                    || stack.is(Items.COOKED_SALMON)).mapToInt(ItemStack::getCount).sum();
            FishermanCampfireFuelSavedData fuel = FishermanCampfireFuelSavedData.forServer(level.getServer());
            boolean consumedNewFuel = cooked > 0 && fuel.remainingCookings(fisherman.getUUID()) < 1;
            Item fuelItem = consumedNewFuel ? carriedCampfireFuel(inventory) : null;
            if (consumedNewFuel && fuelItem == null) {
                int minerEmeraldsBefore = count(minerInventory, Items.EMERALD);
                FishermanFuelEconomyRuntime.tickForGameTest(level.getServer());
                counters.fuelTrades += Math.max(0,
                        count(minerInventory, Items.EMERALD) - minerEmeraldsBefore);
                fuelItem = carriedCampfireFuel(inventory);
                if (fuelItem == null) {
                    break;
                }
            }
            Identifier fuelId = fuelItem == null ? null : BuiltInRegistries.ITEM.getKey(fuelItem);
            List<ItemAmount> fuelInput = consumedNewFuel
                    ? List.of(new ItemAmount(fuelId.toString(), 1)) : List.of();
            var reservation = inventory.reserveExactMatching(rod, fuelInput).orElse(null);
            require(helper, reservation != null,
                    "Fishing rod or Campfire fuel changed during a same-tick catch on day " + day);
            produced += cooked;
            counters.cookedFish += cooked;
            if (attempt.orderOutput().isEmpty()) {
                counters.bycatchRolls++;
            }
            counters.retainedBycatch += retained.stream().mapToInt(ItemStack::getCount).sum();
            counters.ignoredBycatch += attempt.ignoredBycatch()
                    + attempt.bycatch().stream().mapToInt(ItemStack::getCount).sum()
                    - retained.stream().mapToInt(ItemStack::getCount).sum();
            counters.caughtString += retained.stream().filter(stack -> stack.is(Items.STRING))
                    .mapToInt(ItemStack::getCount).sum();
            counters.caughtRods += retained.stream().filter(stack -> stack.is(Items.FISHING_ROD))
                    .mapToInt(ItemStack::getCount).sum();
            ItemStack worn = FishingRodUse.wearOnce(rod);
            if (!worn.isEmpty()) {
                returned.add(worn);
            }
            if (!reservation.commitWithReturns(returned)) {
                reservation.rollback();
                require(helper, false, "Fisherman inventory filled while crediting a catch on day " + day);
            }
            if (cooked > 0 && !fuel.consumeCooking(fisherman.getUUID(), consumedNewFuel)) {
                require(helper, false, "Committed cooked fish without Campfire fuel on day " + day);
            }
        }
        require(helper, rolls < MAX_FISH_OUTPUT_PER_MARKET_PERIOD * MAX_CATCH_ROLLS_PER_FISH
                        || List.of(codOrder, salmonOrder).stream().noneMatch(order ->
                        VillageProductionStockPolicy.needsWorldWork(level, fisherman, inventory, order)),
                "Vanilla fishing table could not refill the bounded food reserve on day " + day);
    }

    private static void observeBounds(GameTestHelper helper, ServerLevel level, List<Villager> village,
                                      VillagerWorkInventorySavedData inventories, Villager fisherman,
                                      Villager miner, Villager lumberjack, VillagerWorkInventory fishermanInventory,
                                      VillagerWorkInventory minerInventory, VillagerWorkInventory lumberjackInventory,
                                      WorkOrder stoneOrder, WorkOrder logsOrder, Counters counters, int day) {
        int foodTarget = VillageProductionStockPolicy.foodTargetNutrition(village.size());
        int food = VillageProductionStockPolicy.storedNutrition(fishermanInventory);
        int stone = count(minerInventory, Items.COBBLESTONE);
        int charcoal = count(minerInventory, Items.CHARCOAL);
        int logs = count(lumberjackInventory, Items.OAK_LOG);
        int saplings = count(lumberjackInventory, Items.OAK_SAPLING);
        int usedSlots = village.stream().mapToInt(villager -> (int) inventories.inventory(villager.getUUID())
                .snapshot().stream().filter(stack -> !stack.isEmpty()).count()).max().orElse(0);
        counters.maxFoodNutrition = Math.max(counters.maxFoodNutrition, food);
        counters.maxStoneStock = Math.max(counters.maxStoneStock, stone);
        counters.maxCharcoalStock = Math.max(counters.maxCharcoalStock, charcoal);
        counters.maxLogStock = Math.max(counters.maxLogStock, logs);
        counters.maxUsedSlots = Math.max(counters.maxUsedSlots, usedSlots);
        require(helper, food <= foodTarget + 6,
                "Fisherman exceeded bounded food reserve on day " + day + ": " + food + "/" + foodTarget);
        require(helper, stone <= stoneOrder.stockCap() && logs <= logsOrder.stockCap()
                        && saplings <= VillageProductionStockPolicy.LUMBERJACK_SAPLING_RESERVE,
                "Resource reserve exceeded its cap on day " + day + "; stone=" + stone
                        + ", logs=" + logs + ", saplings=" + saplings);
        require(helper, List.of(Items.COAL, Items.RAW_COPPER, Items.RAW_IRON, Items.RAW_GOLD,
                        Items.LAPIS_LAZULI, Items.REDSTONE, Items.DIAMOND).stream()
                        .allMatch(item -> count(minerInventory, item)
                                <= VillageProductionStockPolicy.INCIDENTAL_ITEM_RESERVE),
                "Miner incidental reserve exceeded its cap on day " + day);
        require(helper, charcoal <= MinerCharcoalEconomyRuntime.CHARCOAL_TARGET
                        + MinerCharcoalEconomyRuntime.CHARCOAL_BATCH - 1,
                "Miner renewable charcoal reserve exceeded its cap on day " + day + ": " + charcoal);
        require(helper, usedSlots <= 25,
                "A villager no longer has two free work slots on day " + day + "; "
                        + snapshot(village, inventories));
    }

    private static void ensureTool(GameTestHelper helper, net.minecraft.server.MinecraftServer server,
                                   VillagerWorkInventory inventory, VillagerWorkInventory toolsmithInventory,
                                   ToolFamily family, Counters counters, int day) {
        boolean neededReplacement = !family.hasTool(inventory);
        for (int pass = 0; pass < 16 && !family.hasTool(inventory); pass++) {
            tickToolsmithAndCountFibre(server, toolsmithInventory, counters);
        }
        require(helper, family.hasTool(inventory), family + " replacement failed on day " + day
                + "; buyer={emeralds:" + count(inventory, Items.EMERALD)
                + ",stone:" + count(inventory, Items.COBBLESTONE) + "}, toolsmith={emeralds:"
                + count(toolsmithInventory, Items.EMERALD) + ",sticks:" + count(toolsmithInventory, Items.STICK)
                + ",string:" + count(toolsmithInventory, Items.STRING)
                + ",stone:" + count(toolsmithInventory, Items.COBBLESTONE)
                + ",copper:" + count(toolsmithInventory, Items.COPPER_INGOT)
                + ",ironPickaxes:" + count(toolsmithInventory, Items.IRON_PICKAXE)
                + ",copperPickaxes:" + count(toolsmithInventory, Items.COPPER_PICKAXE)
                + ",stonePickaxes:" + count(toolsmithInventory, Items.STONE_PICKAXE)
                + ",stoneAxes:" + count(toolsmithInventory, Items.STONE_AXE) + "}");
        if (neededReplacement) {
            counters.creditReplacement(family, inventory);
        }
    }

    private static void preparePlatform(GameTestHelper helper, int verticalOffset) {
        for (int x = 1; x <= 8; x++) {
            for (int z = 2; z <= 7; z++) {
                helper.setBlock(offsetY(new BlockPos(x, 1, z), verticalOffset), Blocks.STONE);
            }
        }
    }

    private static BlockPos offsetY(BlockPos position, int offset) {
        return position.offset(0, offset, 0);
    }

    private static void growTree(ServerLevel level, BlockPos base) {
        for (int height = 0; height < 4; height++) {
            level.setBlock(base.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        level.setBlock(base.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true), 3);
    }

    private static void tickToolsmithAndCountFibre(net.minecraft.server.MinecraftServer server,
                                                   VillagerWorkInventory toolsmithInventory,
                                                   Counters counters) {
        int vineBefore = count(toolsmithInventory, Items.VINE);
        ToolsmithVillageEconomyRuntime.tickForGameTest(server);
        int vineAfter = count(toolsmithInventory, Items.VINE);
        counters.fibreClippings += Math.max(0, vineAfter - vineBefore);
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        require(helper, profession != null, "Missing profession " + professionId);
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static int totalEmeralds(List<Villager> village, VillagerWorkInventorySavedData inventories) {
        return village.stream().mapToInt(villager -> count(inventories.inventory(villager.getUUID()), Items.EMERALD)).sum();
    }

    private static void requireEmeraldConservation(GameTestHelper helper, List<Villager> village,
                                                   VillagerWorkInventorySavedData inventories,
                                                   int day, String phase) {
        int actual = totalEmeralds(village, inventories);
        int expected = village.size() * VillagerStarterSupplyRuntime.STARTING_EMERALDS;
        if (actual != expected) {
            throw helper.assertionException(
                    "Internal trades changed the physical emerald total during " + phase + " on day " + day
                            + ": actual=" + actual + ", expected=" + expected
                            + "; " + snapshot(village, inventories));
        }
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static Item carriedCampfireFuel(VillagerWorkInventory inventory) {
        if (count(inventory, Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        return count(inventory, Items.COAL) > 0 ? Items.COAL : null;
    }

    private static String snapshot(List<Villager> village, VillagerWorkInventorySavedData inventories) {
        return village.stream().map(villager -> professionId(villager) + "={food:" + VillagerNutrition.foodLevel(villager)
                        + ",emeralds:" + count(inventories.inventory(villager.getUUID()), Items.EMERALD)
                        + ",bread:" + count(inventories.inventory(villager.getUUID()), Items.BREAD)
                        + ",cod:" + count(inventories.inventory(villager.getUUID()), Items.COOKED_COD)
                        + ",salmon:" + count(inventories.inventory(villager.getUUID()), Items.COOKED_SALMON)
                        + ",string:" + count(inventories.inventory(villager.getUUID()), Items.STRING)
                        + ",vine:" + count(inventories.inventory(villager.getUUID()), Items.VINE)
                        + ",shears:" + count(inventories.inventory(villager.getUUID()), Items.SHEARS)
                        + ",logs:" + count(inventories.inventory(villager.getUUID()), Items.OAK_LOG)
                        + ",stone:" + count(inventories.inventory(villager.getUUID()), Items.COBBLESTONE)
                        + ",coal:" + count(inventories.inventory(villager.getUUID()), Items.COAL)
                        + ",charcoal:" + count(inventories.inventory(villager.getUUID()), Items.CHARCOAL)
                        + ",rawCopper:" + count(inventories.inventory(villager.getUUID()), Items.RAW_COPPER)
                        + ",rawIron:" + count(inventories.inventory(villager.getUUID()), Items.RAW_IRON)
                        + ",copper:" + count(inventories.inventory(villager.getUUID()), Items.COPPER_INGOT)
                        + ",iron:" + count(inventories.inventory(villager.getUUID()), Items.IRON_INGOT)
                        + ",lapis:" + count(inventories.inventory(villager.getUUID()), Items.LAPIS_LAZULI)
                        + ",usedSlots:" + inventories.inventory(villager.getUUID()).snapshot().stream()
                        .filter(stack -> !stack.isEmpty()).count() + "}")
                .toList().toString();
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static int simulatedDays() {
        String configured = System.getenv("TOTEM_FISHERMAN_VILLAGE_DAYS");
        if (configured == null || configured.isBlank()) {
            return 30;
        }
        int days = Integer.parseInt(configured);
        if (days < 1 || days > 10_000) {
            throw new IllegalArgumentException("TOTEM_FISHERMAN_VILLAGE_DAYS must be between 1 and 10000");
        }
        return days;
    }

    private static int naturalMineY() {
        String configured = System.getenv("TOTEM_FISHERMAN_MINE_Y");
        if (configured == null || configured.isBlank()) {
            return 56;
        }
        int y = Integer.parseInt(configured);
        if (y < -60 || y > 300) {
            throw new IllegalArgumentException("TOTEM_FISHERMAN_MINE_Y must be between -60 and 300");
        }
        return y;
    }

    private static long oreRollSeed() {
        String configured = System.getenv("TOTEM_FISHERMAN_VILLAGE_SEED");
        return configured == null || configured.isBlank() ? 0x5EEDBEEFL : Long.decode(configured);
    }

    private static OreCounts oreCounts(VillagerWorkInventory inventory) {
        return new OreCounts(count(inventory, Items.COAL), count(inventory, Items.RAW_COPPER),
                count(inventory, Items.RAW_IRON), count(inventory, Items.RAW_GOLD),
                count(inventory, Items.LAPIS_LAZULI), count(inventory, Items.REDSTONE),
                count(inventory, Items.DIAMOND));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private enum ToolFamily {
        PICKAXE {
            @Override
            boolean matches(ItemStack stack) {
                return stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE)
                        || stack.is(Items.COPPER_PICKAXE) || stack.is(Items.IRON_PICKAXE)
                        || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE)
                        || stack.is(Items.NETHERITE_PICKAXE);
            }
        },
        AXE {
            @Override
            boolean matches(ItemStack stack) {
                return stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE) || stack.is(Items.COPPER_AXE)
                        || stack.is(Items.IRON_AXE) || stack.is(Items.GOLDEN_AXE)
                        || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
            }
        };

        boolean hasTool(VillagerWorkInventory inventory) {
            return inventory.snapshot().stream().anyMatch(this::matches);
        }

        abstract boolean matches(ItemStack stack);
    }

    private static final class Counters {
        private int cookedFish;
        private int bycatchRolls;
        private int retainedBycatch;
        private int ignoredBycatch;
        private int caughtString;
        private int caughtRods;
        private int foodTrades;
        private int rodPurchases;
        private int stone;
        private int trees;
        private int fibreClippings;
        private int charcoalBatches;
        private int furnaceReplacements;
        private int mineralReserveRevenue;
        private int fuelTrades;
        private int ironPickaxes;
        private int copperPickaxes;
        private int stonePickaxes;
        private int ironAxes;
        private int copperAxes;
        private int stoneAxes;
        private int maxFoodNutrition;
        private int maxStoneStock;
        private int maxCharcoalStock;
        private int maxLogStock;
        private int maxUsedSlots;
        private OreCounts incidental = OreCounts.EMPTY;

        private void credit(OreCounts drops) {
            incidental = incidental.plus(drops);
        }

        private void creditReplacement(ToolFamily family, VillagerWorkInventory inventory) {
            if (family == ToolFamily.PICKAXE) {
                if (count(inventory, Items.IRON_PICKAXE) > 0) {
                    ironPickaxes++;
                } else if (count(inventory, Items.COPPER_PICKAXE) > 0) {
                    copperPickaxes++;
                } else if (count(inventory, Items.STONE_PICKAXE) > 0) {
                    stonePickaxes++;
                }
            } else if (count(inventory, Items.IRON_AXE) > 0) {
                ironAxes++;
            } else if (count(inventory, Items.COPPER_AXE) > 0) {
                copperAxes++;
            } else if (count(inventory, Items.STONE_AXE) > 0) {
                stoneAxes++;
            }
        }

        private String oreSummary() {
            return incidental.toString();
        }

        private int toolCycles() {
            return rodPurchases + ironPickaxes + copperPickaxes + stonePickaxes
                    + ironAxes + copperAxes + stoneAxes;
        }

        private ActivitySnapshot activity() {
            return new ActivitySnapshot(cookedFish, foodTrades, stone, trees, charcoalBatches,
                    furnaceReplacements, fuelTrades, toolCycles());
        }
    }

    private record ActivitySnapshot(int cookedFish, int foodTrades, int stone, int trees, int charcoalBatches,
                                    int furnaceReplacements, int fuelTrades, int toolCycles) {
    }

    private record OreCounts(int coal, int rawCopper, int rawIron, int rawGold,
                             int lapis, int redstone, int diamond) {
        private static final OreCounts EMPTY = new OreCounts(0, 0, 0, 0, 0, 0, 0);

        private OreCounts minus(OreCounts other) {
            return new OreCounts(coal - other.coal, rawCopper - other.rawCopper, rawIron - other.rawIron,
                    rawGold - other.rawGold, lapis - other.lapis, redstone - other.redstone,
                    diamond - other.diamond);
        }

        private OreCounts plus(OreCounts other) {
            return new OreCounts(coal + other.coal, rawCopper + other.rawCopper, rawIron + other.rawIron,
                    rawGold + other.rawGold, lapis + other.lapis, redstone + other.redstone,
                    diamond + other.diamond);
        }
    }
}
