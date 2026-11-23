package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.FishermanWorldWorkAction;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.world.ToolsmithFiberWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;

/** Temporary food work for housed adults before a village can support permanent careers. */
public final class HouseholdSurvivalRuntime {
    private static final int WORK_INTERVAL = 100;
    private static final int SEARCH_RADIUS = 6;
    private static final int RATION = 20;
    private static final ToolsmithFiberWorldWorkAction GRASS = new ToolsmithFiberWorldWorkAction();

    private HouseholdSurvivalRuntime() {}

    public static void register() {
        StarterWheatField.register();
        VillagerCareerHistory.register();
        VillageFoodForecast.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) return;
            for (ServerLevel level : server.getAllLevels()) {
                for (Villager worker : LoadedVillagerCache.loaded(level)) {
                    if (VillagerRuntimeBudget.due(level.getGameTime(), worker.getUUID(), WORK_INTERVAL)) {
                        HouseholdResourceRequests.reconcile(level, worker);
                        VillagerCareerHistory.observe(worker, WORK_INTERVAL);
                        VillageFoodForecast.sample(level, worker, level.getGameTime());
                        work(level, worker);
                    }
                }
            }
        });
    }

    /** One production work pass; callers cannot bypass the world mode, career or permission checks. */
    public static void work(ServerLevel level, Villager worker) {
        if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || !VillagerResourceWorkforceRuntime.isEligibleCandidate(worker, WorkerAssignmentSavedData.forServer(level.getServer()))
                || !worker.isAlive() || worker.isSleeping() || worker.isTrading()
                || level.isRaided(worker.blockPosition())) return;
        var brain = worker.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE) || brain.hasMemoryValue(MemoryModuleType.JOB_SITE)
                || brain.hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY) || brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)
                || brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY) || brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) return;
        var home = brain.getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || !home.dimension().equals(level.dimension()) || !level.isLoaded(home.pos())
                || !level.getBlockState(home.pos()).is(BlockTags.BEDS) || home.pos().distSqr(worker.blockPosition()) > 32 * 32) return;
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var inventory = inventories.inventory(worker.getUUID());
        boolean hungry = !VillagerWorkNeeds.canWork(worker);
        if (!hungry && EmergencyFieldWork.work(level, worker, inventory)) return;
        if (home.pos().distSqr(worker.blockPosition()) > 16 * 16) return;
        if (hungry && VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(worker, inventory)) return;
        var stock = inventory.snapshot();
        if (stock.stream().mapToInt(s -> VillagerNutrition.nutrition(s) * s.getCount()).sum() >= RATION) return;

        if (StarterWheatField.work(level, worker, home.pos(), inventories, inventory)) return;
        Bank bank = findBank(level, worker, home.pos());
        if (bank == null) return;
        ItemStack rod = FishingRodUse.nextForWork(stock).orElse(null);
        if (rod != null) {
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.FIBRE, VillagerHeadStatus.Kind.ROD,
                    VillagerHeadStatus.Kind.WOOD, VillagerHeadStatus.Kind.TABLE, VillagerHeadStatus.Kind.UNREACHABLE);
            if (canCastFromShore(level, worker, bank.water())) catchFood(level, worker, bank.water(), inventory, rod);
            else walk(worker, bank.stand());
            return;
        }
        var table = VillageWorktable.near(level, worker, home.pos());
        if (table.isEmpty()) {
            VillageWorktable.prepareTable(level, worker, home.pos(), inventory, inventories);
            return;
        }
        if (inventory.countMatchingItem(new ItemStack(Items.STICK)) < 3) {
            if (!VillageWorktable.seekMissingWood(level, worker, inventory, inventories, 2)
                    && atTable(worker, table.get()) && !VillageWoodCrafting.craftSticks(level, inventory)) {
                VillageWoodCrafting.craftPlanks(level, inventory);
            }
            return;
        }
        if (inventory.countMatchingItem(new ItemStack(Items.STRING)) >= 2) {
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.FIBRE);
            if (atTable(worker, table.get()) && VillageWoodCrafting.craftRod(level, inventory)) worker.playWorkSound();
            return;
        }
        var fibres = VillageWoodCrafting.plantFibreInputs(inventory);
        if (fibres.size() == VillageWoodCrafting.FIBRES_PER_STRING) {
            if (atTable(worker, table.get()) && VillageWoodCrafting.craftPlantFibreString(level, inventory, fibres)) {
                VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.FIBRE);
                VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.MAKING_TOOL);
            }
            return;
        }
        if (!gatherGrass(level, worker, home.pos(), inventory)) VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.FIBRE);
    }

    static boolean gatherGrass(ServerLevel level, Villager worker, BlockPos home, VillagerWorkInventory inventory) {
        int pathsTried = 0;
        for (BlockPos grass : BlockPos.withinManhattan(home, SEARCH_RADIUS)) {
            if (!level.isLoaded(grass)) continue;
            if (!canGatherGrass(level, worker, grass)) continue;
            if (worker.distanceToSqr(Vec3.atCenterOf(grass)) <= 9.0) {
                if (GRASS.gatherGrass(level, worker, grass, VillageWoodCrafting.PLANT_FIBRES, inventory)) {
                    VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.FIBRE);
                    return true;
                }
            } else if (pathsTried < 4) {
                pathsTried++;
                if (walk(worker, grass)) {
                    VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.FIBRE);
                    return true;
                }
            }
        }
        return false;
    }

    static boolean canGatherGrass(ServerLevel level, Villager worker, BlockPos grass) {
        var state = level.getBlockState(grass);
        return (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, grass.above()))
                && new ItemStack(state.getBlock()).is(VillageWoodCrafting.PLANT_FIBRES)
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, grass)
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, grass.below());
    }

    static boolean clearFieldGrass(ServerLevel level, Villager worker, BlockPos grass, VillagerWorkInventory inventory) {
        return canGatherGrass(level, worker, grass)
                && GRASS.gatherGrass(level, worker, grass, VillageWoodCrafting.PLANT_FIBRES, inventory);
    }

    private record Bank(BlockPos water, BlockPos stand) {}

    static boolean hasFishingBank(ServerLevel level, Villager worker, BlockPos home) {
        return findBank(level, worker, home) != null;
    }

    private static Bank findBank(ServerLevel level, Villager worker, BlockPos home) {
        var candidates = new LinkedHashMap<BlockPos, Bank>();
        for (BlockPos water : BlockPos.withinManhattan(home, SEARCH_RADIUS)) {
            if (!level.isLoaded(water) || !level.getBlockState(water).is(Blocks.WATER)
                    || dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).heldByAnother(level, water, worker)
                    || WorkerAssignmentSavedData.forServer(level.getServer()).containsZoneCell(level.dimension().identifier().toString(), water)
                    || !level.getBlockState(water.above()).isAir() || !WorldWorkPermissions.mayWork(level, worker, water)) continue;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos edge = water.relative(direction);
                if (!level.isLoaded(edge)) continue;
                BlockPos feet = level.getBlockState(edge).isAir() ? edge : edge.above();
                if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()
                        || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
                if (worker.blockPosition().equals(feet) || canCastFromShore(level, worker, water)) return new Bank(water.immutable(), feet);
                candidates.putIfAbsent(feet, new Bank(water.immutable(), feet));
            }
        }
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(bank -> bank.stand().distSqr(worker.blockPosition())))
                .limit(4).filter(bank -> {
                    var path = worker.getNavigation().createPath(bank.stand(), 0);
                    return path != null && path.canReach();
                }).findFirst().orElse(null);
    }

    static boolean atTable(Villager worker, BlockPos table) {
        var center = Vec3.atCenterOf(table);
        if (worker.distanceToSqr(center) <= 16.0 && worker.level().clip(new net.minecraft.world.level.ClipContext(
                worker.getEyePosition(), center, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, worker)).getBlockPos().equals(table)) {
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.UNREACHABLE);
            return true;
        }
        var path = worker.getNavigation().createPath(table, 1);
        if (path == null || !path.canReach() || !worker.getNavigation().moveTo(path, .5))
            VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.UNREACHABLE);
        else VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.UNREACHABLE);
        return false;
    }

    private static boolean canCastFromShore(ServerLevel level, Villager worker, BlockPos water) {
        BlockPos feet = worker.blockPosition();
        if (worker.isInLiquid() || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                || worker.distanceToSqr(Vec3.atCenterOf(water)) > 16) return false;
        var cast = new net.minecraft.world.level.ClipContext(worker.getEyePosition(), Vec3.atBottomCenterOf(water.above()),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker);
        return level.clip(cast).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    static boolean walk(Villager worker, BlockPos target) {
        var path = worker.getNavigation().createPath(target, 0);
        if (path == null || !path.canReach() || !worker.getNavigation().moveTo(path, .5)) return false;
        worker.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new net.minecraft.world.entity.ai.memory.WalkTarget(target, .5F, 0));
        return true;
    }

    private static void catchFood(ServerLevel level, Villager worker, BlockPos water, VillagerWorkInventory inventory,
                                  ItemStack rod) {
        var reserved = inventory.reserveExactMatchingItem(rod).orElse(null);
        if (reserved == null) return;
        var catches = FishermanWorldWorkAction.catches(level, worker, water, rod);
        if (catches.isEmpty()) {
            reserved.rollback();
            return;
        }
        var returns = new ArrayList<ItemStack>();
        catches.stream().filter(stack -> stack.is(Items.COD) || stack.is(Items.SALMON))
                .map(ItemStack::copy).forEach(returns::add);
        boolean hasFood = !returns.isEmpty();
        ItemStack worn = FishingRodUse.wearOnce(rod);
        if (!worn.isEmpty()) returns.add(worn);
        if (reserved.commitWithReturns(returns)) {
            worker.playWorkSound();
            if (hasFood && VillagerWorkNeeds.canWork(worker)) VillagerResourceWorkforceRuntime.settleFoodProducer(level, worker, water, false);
        } else reserved.rollback();
    }
}
