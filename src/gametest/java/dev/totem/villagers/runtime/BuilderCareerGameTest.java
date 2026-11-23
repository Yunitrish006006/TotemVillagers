package dev.totem.villagers.runtime;

import dev.totem.villagers.builder.BuilderProject;
import dev.totem.villagers.builder.BuilderSiteSavedData;
import dev.totem.villagers.builder.VanillaVillageBlueprints;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class BuilderCareerGameTest {
    @GameTest(padding = 48) public void minerCompletesPaidBuildingTrial(GameTestHelper h) { trial(h, "totem:miner"); }
    @GameTest(padding = 48) public void lumberjackCompletesPaidBuildingTrial(GameTestHelper h) { trial(h, "totem:lumberjack"); }

    @GameTest
    public void operatorRegistersAndCancelsAFullBlueprintIdentifier(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var server = level.getServer();
        var player = h.makeMockServerPlayerInLevel();
        var source = server.createCommandSourceStack().withLevel(level).withEntity(player);
        var anchor = h.absolutePos(new BlockPos(2, 3, 2));
        var start = anchor.offset(-2, -2, -2);
        var end = anchor.offset(14, 14, 14);
        var data = WorkerAssignmentSavedData.forServer(server);
        var before = data.zoneSnapshot().keySet();
        String prefix = "totemvillagers ";
        server.getCommands().performPrefixedCommand(source, prefix + "work-zone create totem:builder " + coords(start) + " " + coords(end));
        var zone = data.zoneSnapshot().keySet().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        try {
            String template = "minecraft:village/plains/houses/plains_small_house_1";
            String project = prefix + "builder-site project " + zone + " " + coords(anchor) + " " + template;
            // Minecraft executes Brigadier commands through its execution queue. Inspect the actual saved request below.
            server.getCommands().performPrefixedCommand(source, project);
            var sites = BuilderSiteSavedData.forServer(server);
            check(h, sites.projects().stream().anyMatch(p -> p.zone().equals(zone) && p.template().equals(template)
                    && p.loadedTicks() == 0 && p.owner().equals(player.getUUID())), "Command did not persist an approved site");
            server.getCommands().performPrefixedCommand(source, prefix + "builder-site cancel-project " + zone);
            check(h, sites.projects().stream().noneMatch(p -> p.zone().equals(zone)), "Project cancellation failed");
            h.succeed();
        } finally {
            data.removeZone(zone);
        }
    }

    private static String coords(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    @SuppressWarnings("unchecked")
    private void trial(GameTestHelper h, String role) {
        var level = h.getLevel();
        var server = level.getServer();
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var sites = BuilderSiteSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var parcels = ProductionParcel.get(server);
        var anchor = h.absolutePos(new BlockPos(2, 4, 2));
        var dimension = level.dimension().identifier().toString();
        var template = Identifier.parse("minecraft:village/plains/houses/plains_small_house_1");
        var blueprint = VanillaVillageBlueprints.resolve(server, template, anchor).orElseThrow();
        var block = blueprint.blocks().getFirst();
        var target = block.position();
        var stand = target.west();
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var worker = h.spawnWithNoFreeWill(type, new BlockPos(2, 4, 2));
        var other = h.spawnWithNoFreeWill(type, new BlockPos(2, 4, 3));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(2, 4, 4));
        var owner = UUID.randomUUID();
        var boundary = new WorkZone(owner, dimension, point(anchor.offset(-2,-3,-2)), point(anchor.offset(12,12,12)));
        var donorBounds = new WorkZone(owner, dimension, point(anchor.west(4)), point(anchor.west(3).above()));
        var projectZone = assignments.createZone("totem:builder", boundary);
        var donor = assignments.createZone(role, donorBounds);
        var face = anchor.east(6);
        var backup = assignments.createZone(role, new WorkZone(owner, dimension, point(face), point(face.above(3))));
        var soil = anchor.west(6);
        var station = GlobalPos.of(level.dimension(), soil.north());
        var project = new BuilderProject(projectZone.id(), owner, dimension, template.toString(), anchor.asLong(), BuilderProject.DEMAND_TICKS);
        var inventory = inventories.inventory(worker.getUUID());
        var logger = role.equals("totem:miner") ? h.spawnWithNoFreeWill(type, new BlockPos(2, 4, 2)) : null;
        var forest = logger == null ? null : assignments.createZone("totem:lumberjack", new WorkZone(owner, dimension,
                point(anchor.south(7)), point(anchor.south(7).above(4))));
        var material = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(block.materialItemId())));
        try {
            level.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(stand, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(stand.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            worker.snapTo(stand.getX()+.5, stand.getY(), stand.getZ()+.5, 0, 0);
            var home = GlobalPos.of(level.dimension(), stand.south());
            for (var v : List.of(worker, other, farmer)) {
                String profession = v == farmer ? "minecraft:farmer" : role;
                v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(profession)))));
                v.getBrain().setMemory(MemoryModuleType.HOME, home);
                VillagerNutrition.setFoodLevel(v, 20);
            }
            if (logger != null) {
                logger.setVillagerData(logger.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("totem:lumberjack")))));
                logger.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), stand.south(3)));
                VillagerNutrition.setFoodLevel(logger, 20);
                var stock = inventories.inventory(logger.getUUID());
                stock.insertExact(new ItemStack(Items.BREAD, 4));
                stock.insertExact(new ItemStack(Items.STONE_AXE));
                stock.insertExact(material.copy());
                assignments.putAssignment(new WorkerAssignment(logger.getUUID(), "totem:lumberjack", Optional.of(forest.id()), Optional.empty(), true));
            }
            farmer.snapTo(soil.getX()+.5, soil.getY()+1, soil.getZ()+1.5, 0, 0);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
            level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            check(h, parcels.acquire(level, farmer, station, soil, true), "Missing supplying farm");
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            inventories.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.WOODEN_HOE));
            for (var resident : List.of(worker, other)) inventories.inventory(resident.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            inventories.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.BREAD, 32));
            var original = new WorkerAssignment(worker.getUUID(), role, Optional.of(donor.id()), Optional.empty(), true);
            assignments.putAssignment(original);
            check(h, !sites.submit(project, UUID.randomUUID()) && sites.submit(project, owner) && !sites.submit(project, owner), "Bad authorization or duplicate reservation");
            inventory.insertExact(material.copy());
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            check(h, inventory.countMatchingItem(material) == 1 && level.getBlockState(target).isAir(), "Last essential supplier reassigned");
            assignments.putAssignment(new WorkerAssignment(other.getUUID(), role, Optional.of(backup.id()), Optional.empty()));
            var standByFace = face.south();
            other.snapTo(standByFace.getX()+.5, standByFace.getY(), standByFace.getZ()+.5, 0, 0);
            level.setBlockAndUpdate(standByFace.below(), Blocks.DIRT.defaultBlockState());
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            check(h, level.getBlockState(target).isAir(), "An idle backup with no workable resources released the last supplier");
            if ("totem:miner".equals(role)) {
                level.setBlockAndUpdate(face, Blocks.FURNACE.defaultBlockState());
                other.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), face));
                level.setBlockAndUpdate(face.above(), Blocks.STONE.defaultBlockState());
                inventories.inventory(other.getUUID()).insertExact(new ItemStack(Items.STONE_PICKAXE));
            } else {
                level.setBlockAndUpdate(face.below(), Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(face, Blocks.OAK_LOG.defaultBlockState());
                level.setBlockAndUpdate(face.above(), Blocks.OAK_LOG.defaultBlockState());
                level.setBlockAndUpdate(face.above(2), Blocks.OAK_LEAVES.defaultBlockState());
                inventories.inventory(other.getUUID()).insertExact(new ItemStack(Items.STONE_AXE));
                inventories.inventory(other.getUUID()).insertExact(new ItemStack(Items.OAK_SAPLING));
            }
            var backupStock = inventories.inventory(other.getUUID());
            int slots = backupStock.snapshot().size();
            for (int i = 0; i < slots; i++) backupStock.insertExact(new ItemStack(Items.WOODEN_SHOVEL));
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            check(h, level.getBlockState(target).isAir(), "A backup unable to store any harvest released the last supplier");
            backupStock.takeExactMatchingItem(new ItemStack(Items.WOODEN_SHOVEL)).orElseThrow();
            worker.setVillagerXp(1);
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            check(h, assignments.getAssignment(worker.getUUID()).filter(original::equals).isPresent() && inventory.countMatchingItem(material) == 1, "Traded donor lost career or materials");
            worker.setVillagerXp(0);
            VillagerCareerHistory.appointed(worker);
            VillagerCareerHistory.observe(worker, 71_900);
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            check(h, level.getBlockState(target).isAir(), "Paid trial ran during the three-day cooldown");
            VillagerCareerHistory.observe(worker, 100);
            VillagerBuilderRuntime.workProject(level, project, sites, assignments, inventories);
            var site = sites.getByBuilder(worker.getUUID()).orElseThrow();
            check(h, inventory.countMatchingItem(material) == 0 && level.getBlockState(target).equals(block.state())
                    && site.nextBlockIndex() == 1 && worker.getBrain().getMemory(MemoryModuleType.HOME).filter(home::equals).isPresent()
                    && assignments.getAssignment(worker.getUUID()).filter(a -> a.roleId().equals("totem:builder")
                        && a.workZoneId().filter(projectZone.id()::equals).isPresent()).isPresent(), "Incomplete paid handover");
            if (logger != null)
                check(h, assignments.getAssignment(logger.getUUID()).filter(a -> a.roleId().equals("totem:lumberjack")
                                && a.workZoneId().filter(forest.id()::equals).isPresent()).isPresent()
                        && inventories.inventory(logger.getUUID()).countMatchingItem(material) == 1,
                        "Mixed village released its sole lumberjack instead of the spare miner");
            h.succeed();
        } finally {
            if (logger != null) {
                assignments.removeZone(forest.id());
                logger.discard();
            }
            sites.cancelProject(projectZone.id(), owner);
            sites.removeByBuilder(worker.getUUID(), owner);
            for (var zone : List.of(projectZone, donor, backup)) assignments.removeZone(zone.id());
            for (var v : List.of(worker, farmer, other)) v.discard();
        }
    }

    private static BlockCoordinate point(BlockPos p) { return new BlockCoordinate(p.getX(), p.getY(), p.getZ()); }
    private static void check(GameTestHelper h, boolean pass, String why) {
        if (!pass) throw h.assertionException(why);
    }
}
