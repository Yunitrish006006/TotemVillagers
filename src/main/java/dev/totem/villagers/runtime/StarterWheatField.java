package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Household wheat fields grow from actual seeds. Existing farmland is never adopted. */
public final class StarterWheatField {
    private static final AttachmentType<GlobalPos> PLOT = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:starter_wheat"), GlobalPos.CODEC);
    private static final FarmerWorldWorkAction HARVEST = new FarmerWorldWorkAction();

    private StarterWheatField() {}

    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager worker) migratePlot(level, worker);
        });
    }

    public static boolean owns(ServerLevel level, Villager worker, BlockPos crop) {
        return canTend(level, worker) && ProductionParcel.get(level.getServer()).claim(worker.getUUID())
                .filter(c -> c.role().equals("starter") && c.station().dimension().equals(level.dimension())
                        && c.home().equals(worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME))
                        && c.cells().contains(crop.below())).isPresent();
    }

    public static boolean canTend(ServerLevel level, Villager worker) {
        return worker.isAlive() && VillagerResourceWorkforceRuntime.isEligibleCandidate(worker,
                WorkerAssignmentSavedData.forServer(level.getServer()));
    }

    private static void migratePlot(ServerLevel level, Villager worker) {
        var owner = (AttachmentTarget) worker;
        var legacy = owner.getAttached(PLOT);
        if (legacy == null) return;
        var parcels = ProductionParcel.get(level.getServer());
        var home = worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
        if (parcels.claim(worker.getUUID()).isPresent() || !canTend(level, worker)) {
            owner.removeAttached(PLOT);
        } else if (home.isPresent() && home.get().dimension().equals(level.dimension())) {
            boolean moved = !legacy.dimension().equals(level.dimension()) || legacy.pos().distSqr(home.get().pos()) > 49;
            if (moved || level.isLoaded(legacy.pos()) && (!level.getBlockState(legacy.pos()).is(Blocks.WHEAT)
                    || parcels.recordStarter(level, worker, home.get(), legacy.pos().below()))) owner.removeAttached(PLOT);
        }
    }

    static boolean work(ServerLevel level, Villager worker, BlockPos home, VillagerWorkInventorySavedData inventories,
                        VillagerWorkInventory inventory) {
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:farmer_wheat");
        if (order == null || !HARVEST.supports(order)) return false;
        var parcels = ProductionParcel.get(level.getServer());
        migratePlot(level, worker);
        if (((AttachmentTarget) worker).hasAttached(PLOT)) return false;
        parcels.reconcile(level, worker);
        var claim = parcels.claim(worker.getUUID());
        if (claim.isPresent() && !claim.get().role().equals("starter")) return false;
        var soils = claim.map(ProductionParcel.Claim::cells).orElse(List.of());
        boolean hungry = dev.totem.villagers.needs.VillagerNutrition.isHungry(worker);
        boolean bread = inventory.countMatchingItem(new ItemStack(Items.WHEAT)) >= 3;
        BlockPos crop = soils.stream().filter(level::isLoaded).map(BlockPos::above)
                .filter(pos -> HARVEST.isMatureCrop(level, pos, order) && WorldWorkPermissions.mayWork(level, worker, pos)
                        && WorldWorkPermissions.mayWork(level, worker, pos.below()))
                .filter(pos -> !WorkerAssignmentSavedData.forServer(level.getServer()).containsZoneCell(level.dimension().identifier().toString(), pos.below()))
                .sorted(java.util.Comparator.comparingDouble(worker.blockPosition()::distSqr))
                .filter(pos -> {
                    if (worker.distanceToSqr(Vec3.atCenterOf(pos)) <= 9) return true;
                    var path = worker.getNavigation().createPath(pos, 0);
                    return path != null && path.canReach();
                }).findFirst().orElse(null);
        boolean planting = crop == null;
        if (!bread && planting) {
            crop = soils.stream().filter(level::isLoaded).map(BlockPos::above)
                    .filter(pos -> level.getBlockState(pos).isAir()
                            && WorldWorkPermissions.mayUseHouseholdLand(level, worker, pos)
                            && WorldWorkPermissions.mayUseHouseholdLand(level, worker, pos.below()))
                    .min(java.util.Comparator.comparingDouble(worker.blockPosition()::distSqr)).orElse(null);
            if (crop == null && (soils.size() >= ProductionParcel.HOUSEHOLD_FIELD_SIZE
                    || soils.isEmpty() && (inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0
                            || FishingRodUse.nextForWork(inventory.snapshot()).isPresent()
                            || FarmerWorldWorkAction.bestHoe(inventory).isEmpty() && (inventory.countMatchingItem(new ItemStack(Items.STRING)) > 0
                            || inventory.snapshot().stream().anyMatch(s -> s.is(Items.SHORT_GRASS) || s.is(Items.TALL_GRASS))))
                            && HouseholdSurvivalRuntime.hasFishingBank(level, worker, home))) return false;
            if (crop == null) crop = plantingSite(level, worker, home, soils);
            if (crop == null) return false;
            if (inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) < 1) {
                // On irrigated inland ground, grass can supply the first seed as well as later ones.
                return (FishingRodUse.nextForWork(inventory.snapshot()).isEmpty() || !HouseholdSurvivalRuntime.hasFishingBank(level, worker, home))
                        && HouseholdSurvivalRuntime.gatherGrass(level, worker, home, inventory);
            }
        }
        var hoe = FarmerWorldWorkAction.bestHoe(inventory);
        if (bread || hoe.isEmpty()) {
            var table = VillageWorktable.near(level, worker, home);
            if (table.isEmpty()) {
                VillageWorktable.prepareTable(level, worker, home, inventory, inventories);
            } else if ((bread || !VillageWorktable.seekMissingWood(level, worker, inventory, inventories, 2))
                    && HouseholdSurvivalRuntime.atTable(worker, table.get())) {
                if (bread) VillageWoodCrafting.craftBread(level, inventory);
                else VillageWoodCrafting.prepareHoe(level, inventory);
            }
            return true;
        }
        if (!WorldWorkPermissions.mayWork(level, worker, crop) || !WorldWorkPermissions.mayWork(level, worker, crop.below())) return true;
        if (worker.distanceToSqr(Vec3.atCenterOf(crop)) > 9.0) {
            HouseholdSurvivalRuntime.walk(worker, crop);
        } else if (planting && !level.getBlockState(crop).isAir()) {
            return HouseholdSurvivalRuntime.clearFieldGrass(level, worker, crop, inventory);
        } else if (planting) {
            plant(level, worker, crop, hoe.get(), inventory);
        } else if (HARVEST.complete(level, worker, crop, order, inventory) && !hungry) {
            VillagerResourceWorkforceRuntime.settleFoodProducer(level, worker, crop.below(), true);
        }
        return true;
    }

    private static BlockPos plantingSite(ServerLevel level, Villager worker, BlockPos home, List<BlockPos> soils) {
        int paths = 0;
        BlockPos fallback = null;
        // A straight row avoids vanilla wheat's growth penalty for diagonal neighbours.
        boolean sameX = soils.size() >= 2 && soils.stream().allMatch(p -> p.getX() == soils.getFirst().getX());
        boolean sameZ = soils.size() >= 2 && soils.stream().allMatch(p -> p.getZ() == soils.getFirst().getZ());
        for (BlockPos candidate : BlockPos.withinManhattan(home, 5)) {
            BlockPos soil = candidate.below();
            if (!level.isLoaded(candidate) || !level.getBlockState(candidate).isAir()
                    && !HouseholdSurvivalRuntime.canGatherGrass(level, worker, candidate)
                    || !(level.getBlockState(soil).is(Blocks.DIRT) || level.getBlockState(soil).is(Blocks.GRASS_BLOCK))
                    || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, candidate)
                    || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, soil)) continue;
            boolean watered = false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = soil.relative(side);
                if (level.isLoaded(beside) && level.getBlockState(beside).is(Blocks.WATER)) {
                    watered = true;
                    break;
                }
            }
            if (!soils.isEmpty()) {
                if (soils.stream().noneMatch(existing -> existing.getY() == soil.getY() && existing.distManhattan(soil) == 1)) continue;
                // A neighbouring hydrated patch can share its irrigation source.
                if (!watered) watered = BlockPos.betweenClosedStream(soil.offset(-4, 0, -4), soil.offset(4, 0, 4))
                        .anyMatch(pos -> level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.WATER));
            }
            if (!watered || candidate.equals(worker.blockPosition())) continue;
            boolean row = !sameX && !sameZ || sameX && soil.getX() == soils.getFirst().getX()
                    || sameZ && soil.getZ() == soils.getFirst().getZ();
            if (!row && fallback != null) continue;
            if (worker.distanceToSqr(Vec3.atCenterOf(candidate)) > 9.0) {
                if (paths++ >= 3) continue;
                var path = worker.getNavigation().createPath(candidate, 0);
                if (path == null || !path.canReach()) continue;
            }
            if (row) return candidate.immutable();
            fallback = candidate.immutable();
        }
        return fallback;
    }

    private static void plant(ServerLevel level, Villager worker, BlockPos crop, ItemStack hoe,
                              VillagerWorkInventory inventory) {
        BlockPos soil = crop.below();
        var original = level.getBlockState(soil);
        var reserved = inventory.reserveExactMatching(List.of(hoe, new ItemStack(Items.WHEAT_SEEDS))).orElse(null);
        if (reserved == null) return;
        ItemStack worn = FarmerWorldWorkAction.wearOnce(hoe);
        if (!original.is(Blocks.FARMLAND) && !level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState())) {
            reserved.rollback();
            return;
        }
        if (!level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState())) {
            level.setBlockAndUpdate(soil, original);
            reserved.rollback();
            return;
        }
        var parcels = ProductionParcel.get(level.getServer());
        var home = worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
        if (home.isEmpty() || !parcels.recordStarter(level, worker, home.get(), soil) || !reserved.commitWithReturn(worn)) {
            level.setBlockAndUpdate(crop, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(soil, original);
            parcels.reconcile(level, worker);
            reserved.rollback();
            return;
        }
        worker.playWorkSound();
    }
}
