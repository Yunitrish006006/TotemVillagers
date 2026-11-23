package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** Emergency rations for a nearby farm household whose caretaker is too hungry to work. */
public final class EmergencyFieldWork {
    private static final FarmerWorldWorkAction HARVEST = new FarmerWorldWorkAction();
    private EmergencyFieldWork() {}

    public static boolean mayHelp(ServerLevel level, Villager helper, Villager owner) {
        if (helper == owner || helper.level() != level || owner.level() != level || !owner.isAlive() || owner.isBaby()
                || !helper.isAlive() || helper.isTrading() || helper.isSleeping() || owner.isTrading() || owner.isSleeping()
                || !VillagerWorkNeeds.canWork(helper) || !VillagerNutrition.isHungry(owner)
                || level.isRaided(helper.blockPosition()) || helper.distanceToSqr(owner) > 24 * 24
                || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || !VillagerResourceWorkforceRuntime.isEligibleCandidate(helper, WorkerAssignmentSavedData.forServer(level.getServer()))) return false;
        var brain = helper.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.JOB_SITE) || brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)
                || brain.hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY) || brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET) || brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET)) return false;
        var home = brain.getMemory(MemoryModuleType.HOME).orElse(null);
        var farmHome = owner.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        return home != null && farmHome != null && home.dimension().equals(level.dimension())
                && farmHome.dimension().equals(level.dimension()) && home.pos().distSqr(farmHome.pos()) <= 16 * 16
                && farmHome.pos().distSqr(owner.blockPosition()) <= 16 * 16
                && level.isLoaded(home.pos()) && level.getBlockState(home.pos()).is(BlockTags.BEDS)
                && level.isLoaded(farmHome.pos()) && level.getBlockState(farmHome.pos()).is(BlockTags.BEDS);
    }

    static boolean work(ServerLevel level, Villager helper, VillagerWorkInventory inventory) {
        var parcels = ProductionParcel.get(level.getServer());
        var villagers = level.getEntitiesOfClass(Villager.class, helper.getBoundingBox().inflate(24),
                villager -> villager != helper && villager.isAlive() && VillagerNutrition.isHungry(villager));
        for (var owner : villagers) {
            if (!mayHelp(level, helper, owner)) continue;
            parcels.reconcile(level, owner);
            var claim = parcels.claim(owner.getUUID()).filter(c -> c.role().equals("minecraft:farmer")).orElse(null);
            if (claim == null) continue;
            var pantry = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(owner.getUUID());
            if (pantry.snapshot().stream().anyMatch(stack -> VillagerNutrition.nutrition(stack) > 0)) continue;
            var food = inventory.snapshot().stream().filter(s -> VillagerNutrition.nutrition(s) > 0)
                    .max(Comparator.comparingInt(VillagerNutrition::nutrition)).map(s -> s.copyWithCount(1)).orElse(null);
            if (food != null) {
                if (helper.distanceToSqr(owner) > 9 || !helper.hasLineOfSight(owner)) {
                    if (approach(helper, owner.blockPosition())) return true;
                    continue;
                }
                if (!pantry.canInsertExact(food)) continue;
                var ration = inventory.reserveExactMatchingItem(food).orElse(null);
                if (ration == null) continue;
                if (!pantry.insertExact(food)) {
                    ration.rollback();
                    continue;
                }
                ration.commit();
                VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(owner, pantry);
                return true;
            }
            if (inventory.countMatchingItem(new ItemStack(Items.WHEAT)) >= 3) {
                var table = VillageWorktable.near(level, helper, helper.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow().pos());
                if (table.isPresent()) {
                    if (helper.distanceToSqr(Vec3.atCenterOf(table.get())) > 16) {
                        if (approach(helper, table.get())) return true;
                    } else if (VillageWoodCrafting.craftBread(level, inventory)) return true;
                }
            }
            if (FarmerWorldWorkAction.bestHoe(inventory).isEmpty()) continue;
            var orders = WorkOrderDefinitions.catalog().snapshot();
            int routes = 0;
            for (BlockPos soil : claim.cells()) {
                BlockPos crop = soil.above();
                var order = HARVEST.matureCropOrderId(level, crop).map(orders::get).orElse(null);
                if (order == null || !WorldWorkPermissions.mayWork(level, helper, crop)
                        || !WorldWorkPermissions.mayWork(level, helper, soil)) continue;
                if (helper.distanceToSqr(Vec3.atCenterOf(crop)) <= 9) {
                    if (HARVEST.helpHarvest(level, helper, owner, crop, order)) return true;
                } else if (routes++ < 3 && approach(helper, crop)) return true;
            }
        }
        return false;
    }

    private static boolean approach(Villager helper, BlockPos target) {
        var path = helper.getNavigation().createPath(target, 1);
        return path != null && path.canReach() && helper.getNavigation().moveTo(path, .5);
    }
}
