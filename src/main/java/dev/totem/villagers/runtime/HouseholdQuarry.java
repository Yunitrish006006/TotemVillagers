package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.world.NaturalStoneFaces;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.Optional;

final class HouseholdQuarry {
    private static final MinerWorldWorkAction MINING = new MinerWorldWorkAction();

    static boolean request(ServerLevel level, Villager buyer, VillagerWorkInventorySavedData inventories, long now) {
        var home = buyer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        var job = buyer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        var wallet = inventories.inventory(buyer.getUUID());
        var price = new ItemStack(Items.EMERALD);
        var goods = new ItemStack(Items.COBBLESTONE);
        if (home == null || job == null || !home.dimension().equals(level.dimension()) || !job.dimension().equals(level.dimension())
                || !level.isLoaded(home.pos()) || !level.getBlockState(home.pos()).is(BlockTags.BEDS)
                || !level.isLoaded(job.pos()) || !level.getBlockState(job.pos()).is(Blocks.SMITHING_TABLE)
                || !HouseholdResourceRequests.role(buyer).equals("minecraft:toolsmith") || buyer.isBaby()
                || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || wallet.countMatchingItem(price) < 1) {
            HouseholdResourceRequests.clearStone(buyer);
            return false;
        }
        if (home.pos().distSqr(buyer.blockPosition()) > 1024 || !VillageWorkshopApprenticeship.available(level, buyer)
                || !HouseholdResourceRequests.stone(buyer, home, now)) return false;
        var residents = VillageWorkshopApprenticeship.households(level, buyer, home);
        if (!VillageFoodForecast.coversNextMeal(level, residents, inventories, buyer, 1, false)) return false;
        var jobs = WorkerAssignmentSavedData.forServer(level.getServer());
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:miner_stone");
        if (order == null || !order.professionId().equals("totem:miner") || !order.allowedSources().contains(WorkSource.WORLD)
                || !order.output().itemId().equals("minecraft:cobblestone") || order.worldTargetTag().isBlank()) return false;
        var tag = TagKey.create(Registries.BLOCK, Identifier.parse(order.worldTargetTag()));
        for (var worker : residents.stream().sorted(Comparator.comparingDouble(buyer::distanceToSqr)).toList()) {
            boolean returning = HouseholdResourceRequests.returning(worker, jobs, level, "totem:miner");
            if (worker == buyer || !VillageWorkshopApprenticeship.available(level, worker) || !VillagerWorkNeeds.canWork(worker)
                    || worker.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)
                    || !returning && (!VillagerCareerHistory.mayChange(worker) || !VillageWorkshopApprenticeship.canLeave(level, worker,
                        residents, jobs, VillageWorkshopApprenticeship.SourceCareer.UNEMPLOYED, null, home.pos()))) continue;
            var bed = worker.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
            var stock = inventories.inventory(worker.getUUID());
            var faces = NaturalStoneFaces.nearby(level, bed.pos(), worker.blockPosition());
            if (faces.isEmpty()) continue;
            // Furnace block entities provide a loaded-only index of potential workplaces.
            for (var station : NaturalStoneFaces.furnaces(level, bed.pos(), worker.blockPosition())) {
                var zone = plot(level, worker, station);
                if (MinerFurnaceWorkstation.findUnclaimed(level, worker, zone).isEmpty()
                        || !BlockPos.betweenClosedStream(station.offset(-2, -1, -2), station.offset(2, 1, 2))
                            .allMatch(p -> WorldWorkPermissions.mayUseHouseholdLand(level, worker, p))) continue;
                var face = faces.stream().filter(p -> Math.abs(p.getX()-station.getX()) <= 2 && Math.abs(p.getZ()-station.getZ()) <= 2
                        && Math.abs(p.getY()-station.getY()) <= 1 && level.getBlockState(p).is(tag)
                        && NaturalStoneFaces.usable(level, worker, zone, p) && WorldWorkNavigation.canReach(level, worker, p)).findFirst().orElse(null);
                if (face == null) continue;
                if (!ToolsmithVillageEconomyRuntime.ReplacementTool.PICKAXE.hasUsableTool(stock)) {
                    var table = VillageWorktable.near(level, worker, bed.pos());
                    if (table.isEmpty()) VillageWorktable.prepareTable(level, worker, bed.pos(), stock, inventories);
                    else if (!VillageWorktable.seekMissingWood(level, worker, stock, inventories, 3)
                            && HouseholdSurvivalRuntime.atTable(worker, table.get())) VillageWoodCrafting.preparePickaxe(level, stock);
                    continue;
                }
                if (!WorldWorkNavigation.isWithinReach(worker, face)) return WorldWorkNavigation.moveToReach(level, worker, face, .5);
                if (buyer.distanceToSqr(worker)>16 || !buyer.hasLineOfSight(worker)) {
                    var path = buyer.getNavigation().createPath(worker, 1);
                    if (path != null && path.canReach() && buyer.getNavigation().moveTo(path, .5)) return true;
                    continue;
                }
                var money = wallet.reserveExactMatchingItem(price).orElse(null);
                if (money == null) return false;
                if (!wallet.canInsertExact(goods) || !stock.insertExact(price)) { money.rollback(); continue; }
                if (!MINING.completeForOrder(level, worker, face, tag, order, stock, goods)) {
                    stock.takeExactMatchingItem(price).orElseThrow();
                    money.rollback();
                    continue;
                }
                money.commitWithReturn(stock.takeExactMatchingItem(goods).orElseThrow());
                var record = jobs.createZone("totem:miner", zone);
                jobs.putAssignment(new WorkerAssignment(worker.getUUID(), "totem:miner", Optional.of(record.id()), Optional.empty(), true, Optional.of(bed)));
                VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
                worker.setVillagerData(worker.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        BuiltInRegistries.VILLAGER_PROFESSION.getValue(TotemVillagerProfessions.MINER_ID))));
                worker.setVillagerDataFinalized(true);
                worker.refreshBrain(level);
                worker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), station));
                if (!returning) VillagerCareerHistory.appointed(worker);
                HouseholdResourceRequests.clearStone(buyer);
                return true;
            }
        }
        return false;
    }

    static WorkZone plot(ServerLevel level, Villager worker, BlockPos p) {
        return new WorkZone(worker.getUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(p.getX()-2, p.getY()-1, p.getZ()-2), new BlockCoordinate(p.getX()+2, p.getY()+1, p.getZ()+2), false, true);
    }
    private HouseholdQuarry() {}
}
