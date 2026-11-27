package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/** Shared table access and physical wood supply for household and farm work. */
public final class VillageWorktable {
    private record Miss(net.minecraft.core.GlobalPos anchor, long retryAt, VillagerHeadStatus.Kind reason) {}
    private static final java.util.Map<Villager, Miss> MISSES = new java.util.WeakHashMap<>();

    private VillageWorktable() {}

    public static boolean atTable(ServerLevel level, Villager worker) {
        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var pos = worker.blockPosition().relative(side);
            if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && WorldWorkPermissions.mayWork(level, worker, pos)) return true;
        }
        return false;
    }

    static boolean waitingForTable(ServerLevel level, Villager worker, BlockPos station) {
        var miss = MISSES.get(worker);
        return miss != null && miss.anchor.dimension().equals(level.dimension()) && miss.anchor.pos().equals(station)
                && level.getGameTime() < miss.retryAt && !atTable(level, worker);
    }

    static boolean approach(ServerLevel level, Villager worker, BlockPos station) {
        if (atTable(level, worker)) {
            MISSES.remove(worker);
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.TABLE, VillagerHeadStatus.Kind.UNREACHABLE);
            return true;
        }
        var anchor = net.minecraft.core.GlobalPos.of(level.dimension(), station);
        var miss = MISSES.get(worker);
        if (miss != null && miss.anchor.equals(anchor) && level.getGameTime() < miss.retryAt) {
            VillagerHeadStatus.report(worker, miss.reason);
            return false;
        }
        if (!worker.getNavigation().isDone()) return false;
        var tables = BlockPos.betweenClosedStream(station.offset(-6, -2, -6), station.offset(6, 2, 6))
                .filter(level::isLoaded).filter(p -> level.getBlockState(p).is(Blocks.CRAFTING_TABLE))
                .filter(p -> WorldWorkPermissions.mayWork(level, worker, p))
                .map(BlockPos::immutable).sorted(java.util.Comparator.comparingDouble(p -> p.distSqr(worker.blockPosition())))
                .limit(4).toList();
        for (var table : tables) {
            if (walkToSide(level, worker, table)) return false;
        }
        var reason = tables.isEmpty() ? VillagerHeadStatus.Kind.TABLE : VillagerHeadStatus.Kind.UNREACHABLE;
        MISSES.put(worker, new Miss(anchor, level.getGameTime() + 100, reason));
        VillagerHeadStatus.report(worker, reason);
        return false;
    }

    static boolean walkToSide(ServerLevel level, Villager worker, BlockPos target) {
        for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var stance = target.relative(side);
            if (!level.isLoaded(stance) || !level.getBlockState(stance).isPathfindable(
                    net.minecraft.world.level.pathfinder.PathComputationType.LAND)) continue;
            var path = worker.getNavigation().createPath(stance, 0);
            if (path != null && path.canReach() && worker.getNavigation().moveTo(path, .5)) return true;
        }
        return false;
    }

    static Optional<BlockPos> near(ServerLevel level, Villager worker, BlockPos anchor) {
        var table = BlockPos.betweenClosedStream(anchor.offset(-3, -1, -3), anchor.offset(3, 1, 3))
                .filter(level::isLoaded)
                .filter(pos -> level.getBlockState(pos).is(Blocks.CRAFTING_TABLE))
                .filter(pos -> WorldWorkPermissions.mayWork(level, worker, pos))
                .map(BlockPos::immutable)
                .min(java.util.Comparator.comparingDouble(pos -> worker.distanceToSqr(Vec3.atCenterOf(pos))));
        if (table.isPresent()) VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.TABLE);
        return table;
    }

    static void prepareTable(ServerLevel level, Villager worker, BlockPos anchor,
                                         VillagerWorkInventory inventory, VillagerWorkInventorySavedData inventories) {
        for (BlockPos candidate : BlockPos.withinManhattan(anchor, 3)) {
            if (candidate.getY() != anchor.getY() || candidate.equals(anchor)) continue;
            BlockPos site = candidate.immutable();
            if (!WorldWorkPermissions.mayUseHouseholdLand(level, worker, site)
                    || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, site.below()) || !level.getBlockState(site).isAir()
                    || !(level.getBlockState(site.below()).is(Blocks.DIRT)
                    || level.getBlockState(site.below()).is(Blocks.GRASS_BLOCK))
                    || !level.isUnobstructed(Blocks.CRAFTING_TABLE.defaultBlockState(), site,
                        net.minecraft.world.phys.shapes.CollisionContext.empty())) {
                continue;
            }
            if (inventory.countMatchingItem(new ItemStack(Items.CRAFTING_TABLE)) == 0
                    && seekMissingWood(level, worker, inventory, inventories, 4)) return;
            // A solid table against a trunk would make the remaining tree unsafe to fell.
            if (net.minecraft.core.Direction.Plane.HORIZONTAL.stream()
                    .anyMatch(side -> level.getBlockState(site.relative(side)).is(Blocks.OAK_LOG))) continue;
            if (worker.distanceToSqr(Vec3.atCenterOf(site)) > 16.0D) {
                HouseholdSurvivalRuntime.atTable(worker, site);
                return;
            }
            var table = inventory.reserveExactMatchingItem(new ItemStack(Items.CRAFTING_TABLE)).orElse(null);
            if (table == null) {
                VillageWoodCrafting.prepareCraftingTable(level, inventory);
            } else if (level.setBlockAndUpdate(site, Blocks.CRAFTING_TABLE.defaultBlockState())) {
                table.commit();
                VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.TABLE, VillagerHeadStatus.Kind.WOOD);
                worker.playWorkSound();
            } else {
                table.rollback();
                VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.TABLE);
            }
            return;
        }
        VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.TABLE);
    }

    static boolean seekMissingWood(ServerLevel level, Villager worker, VillagerWorkInventory inventory,
                                           VillagerWorkInventorySavedData inventories, int requiredPlanks) {
        var stock = inventory.snapshot();
        if (stock.stream().anyMatch(stack -> stack.is(ItemTags.LOGS))
                || stock.stream().filter(stack -> stack.is(ItemTags.PLANKS)).mapToInt(ItemStack::getCount).sum()
                    >= requiredPlanks) {
            HouseholdForestry.clear(worker);
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.WOOD);
            return false;
        }
        // Keep enough for a meal after paying the usual log price.
        if (inventory.countMatchingItem(new ItemStack(Items.EMERALD)) > ToolsmithVillageEconomyRuntime.WOOD_PRICE) {
            if (ToolsmithVillageEconomyRuntime.buyLogs(worker, LoadedVillagerCache.loaded(level), inventories)) {
                VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.WOOD);
                return true;
            }
        }
        var anchor = worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                .or(() -> worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME));
        boolean harvesting = anchor.filter(pos -> pos.dimension().equals(level.dimension()))
                .map(pos -> dev.totem.villagers.world.NaturalOakTimber.gatherNear(level, worker, pos.pos(), inventory)).orElse(false);
        if (harvesting) VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.WOOD);
        else VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.WOOD);
        return true;
    }

}
