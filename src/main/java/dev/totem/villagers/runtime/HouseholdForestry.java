package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.NaturalOakTimber;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.Optional;

/** A household asking to buy logs can support a renewable, personally tended woodland plot. */
final class HouseholdForestry {
    private static final LumberjackWorldWorkAction HARVEST = new LumberjackWorldWorkAction();

    static void clear(Villager buyer) { HouseholdResourceRequests.clearWood(buyer); }

    static boolean request(ServerLevel level, Villager buyer, VillagerWorkInventorySavedData inventories, long now) {
        var home = buyer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || !home.dimension().equals(level.dimension()) || !level.isLoaded(home.pos())
                || !level.getBlockState(home.pos()).is(BlockTags.BEDS) || buyer.isBaby()
                || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || inventories.inventory(buyer.getUUID()).countMatchingItem(new ItemStack(Items.EMERALD)) < ToolsmithVillageEconomyRuntime.WOOD_PRICE) {
            clear(buyer);
            return false;
        }
        if (home.pos().distSqr(buyer.blockPosition()) > 32 * 32 || !VillageWorkshopApprenticeship.available(level, buyer)) return false;
        if (!HouseholdResourceRequests.wood(buyer, home, now)) return false;
        var residents = VillageWorkshopApprenticeship.households(level, buyer, home);
        if (!VillageFoodForecast.coversNextMeal(level, residents, inventories, buyer, ToolsmithVillageEconomyRuntime.WOOD_PRICE, false)) return false;
        var jobs = WorkerAssignmentSavedData.forServer(level.getServer());
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:lumberjack_oak_logs");
        if (order == null || !order.allowedSources().contains(WorkSource.WORLD) || !order.worldReplantBlockId().equals("minecraft:oak_sapling")
                || !order.output().itemId().equals("minecraft:oak_log") || order.worldTargetTag().isBlank()) return false;
        var tag = TagKey.create(Registries.BLOCK, Identifier.parse(order.worldTargetTag()));
        for (var worker : residents.stream().sorted(Comparator.comparingDouble(buyer::distanceToSqr)).toList()) {
            boolean returning = HouseholdResourceRequests.returning(worker, jobs, level, "totem:lumberjack");
            if (worker == buyer || !returning && !VillageWorkshopApprenticeship.mayStartForestry(level, worker, jobs)
                    || !returning && !VillagerCareerHistory.mayChange(worker) || !VillagerWorkNeeds.canWork(worker)
                    || !VillageWorkshopApprenticeship.available(level, worker)
                    || worker.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)) continue;
            var stock = inventories.inventory(worker.getUUID());
            if (!ToolsmithVillageEconomyRuntime.ReplacementTool.AXE.hasUsableTool(stock)
                    || stock.countMatchingItem(new ItemStack(Items.OAK_SAPLING)) == 0) continue;
            var bed = worker.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
            var tree = NaturalOakTimber.firstForestPlot(level, worker, bed.pos(), p ->
                    Blocks.OAK_SAPLING.defaultBlockState().canSurvive(level, p)
                    && BlockPos.betweenClosedStream(p.below(), p.above(7)).allMatch(cell -> WorldWorkPermissions.mayUseHouseholdLand(level, worker, cell))
                    && LumberjackWorldWorkAction.isEligibleBase(level, worker, plot(level, worker, p), p, tag, order)).orElse(null);
            if (tree == null) continue;
            var zone = plot(level, worker, tree);
            if (!WorldWorkNavigation.isWithinReach(worker, tree)) {
                if (WorldWorkNavigation.moveToReach(level, worker, tree, .5)) return false;
                continue;
            }
            if (buyer.distanceToSqr(worker) > 16 || !buyer.hasLineOfSight(worker)) {
                var path = buyer.getNavigation().createPath(worker, 1);
                if (path != null && path.canReach() && buyer.getNavigation().moveTo(path, .5)) return true;
                continue;
            }
            var wallet = inventories.inventory(buyer.getUUID());
            var price = new ItemStack(Items.EMERALD, ToolsmithVillageEconomyRuntime.WOOD_PRICE);
            var log = new ItemStack(Items.OAK_LOG);
            var payment = wallet.reserveExactMatchingItem(price).orElse(null);
            if (payment == null) return false;
            if (!wallet.canInsertExact(log) || !stock.insertExact(price)) {
                payment.rollback();
                continue;
            }
            if (!HARVEST.complete(level, worker, zone, tree, tag, order, stock)) {
                stock.takeExactMatchingItem(price).orElseThrow();
                payment.rollback();
                continue;
            }
            payment.commitWithReturn(stock.takeExactMatchingItem(log).orElseThrow());
            var record = jobs.createZone("totem:lumberjack", zone);
            jobs.putAssignment(new WorkerAssignment(worker.getUUID(), "totem:lumberjack", Optional.of(record.id()), Optional.empty(), true, Optional.of(bed)));
            VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
            worker.setVillagerData(worker.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(TotemVillagerProfessions.LUMBERJACK_ID))));
            worker.setVillagerDataFinalized(true);
            worker.refreshBrain(level);
            dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).reconcile(level, worker);
            if (!returning) VillagerCareerHistory.appointed(worker);
            clear(buyer);
            return true;
        }
        return false;
    }

    private static WorkZone plot(ServerLevel level, Villager worker, BlockPos tree) {
        return new WorkZone(worker.getUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(tree.getX(), tree.getY(), tree.getZ()),
                new BlockCoordinate(tree.getX(), tree.getY() + 7, tree.getZ()), true);
    }

    private HouseholdForestry() {}
}
