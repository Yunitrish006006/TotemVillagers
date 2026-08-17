package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.runtime.VillagerResourceWorkforceRuntime;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/** Covers the safe Farmer → Miner → Lumberjack → Toolsmith automatic workforce priority. */
public final class VillagerResourceWorkforceGameTest {
    @GameTest(maxTicks = 40)
    public void unemployedVillagersFillConfiguredResourceRolesInPriorityOrder(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        WorkZoneRecord minerZone = assignments.createZone("totem:miner", zone(helper, owner, new BlockPos(7, 1, 6)));
        WorkZoneRecord lumberjackZone = assignments.createZone("totem:lumberjack", zone(helper, owner, new BlockPos(12, 1, 8)));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.COMPOSTER);
        helper.setBlock(new BlockPos(7, 2, 6), Blocks.FURNACE);
        Villager first = spawnUnemployed(helper, new BlockPos(3, 3, 4));
        Villager second = spawnUnemployed(helper, new BlockPos(5, 3, 4));
        Villager third = spawnUnemployed(helper, new BlockPos(7, 3, 4));
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerResourceWorkforceRuntime.tickForGameTest(server);
            List<String> roles = List.of(first, second, third).stream().map(VillagerResourceWorkforceGameTest::professionId).sorted().toList();
            require(helper, roles.equals(List.of("minecraft:farmer", "totem:lumberjack", "totem:miner")),
                    "Resource workforce did not fill Farmer, Miner, then Lumberjack roles: " + roles);
            require(helper, assignments.assignmentSnapshot().values().stream()
                            .anyMatch(assignment -> assignment.workZoneId().filter(minerZone.id()::equals).isPresent()),
                    "Miner Work Zone was not staffed");
            require(helper, assignments.assignmentSnapshot().values().stream()
                            .anyMatch(assignment -> assignment.workZoneId().filter(lumberjackZone.id()::equals).isPresent()),
                    "Lumberjack Work Zone was not staffed");
            Villager miner = List.of(first, second, third).stream().filter(villager -> "totem:miner".equals(professionId(villager)))
                    .findFirst().orElseThrow(() -> helper.assertionException("Configured Furnace did not produce a Miner"));
            BlockPos furnace = miner.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                    .map(net.minecraft.core.GlobalPos::pos)
                    .orElseThrow(() -> helper.assertionException("Miner did not bind a physical Furnace job site"));
            require(helper, helper.getLevel().getBlockState(furnace).is(Blocks.FURNACE),
                    "Miner job site was not the configured Furnace");
            VillagerProfession unemployed = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "unemployed"));
            require(helper, unemployed != null, "Missing minecraft:unemployed profession");
            miner.setVillagerData(miner.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(unemployed)));
            require(helper, "totem:miner".equals(professionId(miner)),
                    "Vanilla profession reset erased a durably assigned Miner career");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            assignments.removeAssignment(first.getUUID());
            assignments.removeAssignment(second.getUUID());
            assignments.removeAssignment(third.getUUID());
            assignments.removeZone(minerZone.id());
            assignments.removeZone(lumberjackZone.id());
            first.discard();
            second.discard();
            third.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fourthUnemployedAdultClaimsTheCoreSmithingTableAfterResourceRoles(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        UUID owner = UUID.randomUUID();
        WorkZoneRecord minerZone = assignments.createZone("totem:miner", zone(helper, owner, new BlockPos(7, 1, 6)));
        WorkZoneRecord lumberjackZone = assignments.createZone("totem:lumberjack", zone(helper, owner, new BlockPos(12, 1, 8)));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.COMPOSTER);
        helper.setBlock(new BlockPos(7, 2, 6), Blocks.FURNACE);
        BlockPos smithingTable = new BlockPos(9, 2, 3);
        helper.setBlock(smithingTable, Blocks.SMITHING_TABLE);
        Villager first = spawnUnemployed(helper, new BlockPos(3, 3, 4));
        Villager second = spawnUnemployed(helper, new BlockPos(5, 3, 4));
        Villager third = spawnUnemployed(helper, new BlockPos(7, 3, 4));
        Villager fourth = spawnUnemployed(helper, new BlockPos(9, 3, 4));
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerResourceWorkforceRuntime.tickForGameTest(server);
            List<String> roles = List.of(first, second, third, fourth).stream()
                    .map(VillagerResourceWorkforceGameTest::professionId).sorted().toList();
            require(helper, roles.equals(List.of("minecraft:farmer", "minecraft:toolsmith", "totem:lumberjack", "totem:miner")),
                    "Fourth-priority Toolsmith displaced a core resource role or was not recruited: " + roles);
            Villager toolsmith = List.of(first, second, third, fourth).stream()
                    .filter(villager -> "minecraft:toolsmith".equals(professionId(villager))).findFirst()
                    .orElseThrow(() -> helper.assertionException("No fourth-priority Toolsmith was recruited"));
            BlockPos claimed = toolsmith.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                    .map(net.minecraft.core.GlobalPos::pos)
                    .orElseThrow(() -> helper.assertionException("Toolsmith did not bind the generated Smithing Table"));
            require(helper, claimed.equals(helper.absolutePos(smithingTable))
                            && helper.getLevel().getBlockState(claimed).is(Blocks.SMITHING_TABLE),
                    "Toolsmith bound a different or missing workstation");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : List.of(first, second, third, fourth)) {
                assignments.removeAssignment(villager.getUUID());
                villager.discard();
            }
            assignments.removeZone(minerZone.id());
            assignments.removeZone(lumberjackZone.id());
        }
    }

    @GameTest(maxTicks = 40)
    public void newlyLoadedUnemployedVillagerIsRecruitedBeforeVanillaPoiClaiming(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        WorkZoneRecord minerZone = assignments.createZone("totem:miner", zone(helper, UUID.randomUUID(), new BlockPos(7, 1, 6)));
        helper.setBlock(new BlockPos(7, 2, 6), Blocks.FURNACE);
        Villager candidate = spawnUnemployed(helper, new BlockPos(7, 3, 4));
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerResourceWorkforceRuntime.recruitLoadedVillagerForGameTest(helper.getLevel(), candidate);
            require(helper, "totem:miner".equals(professionId(candidate)),
                    "A newly loaded unemployed villager was not immediately recruited as Miner");
            BlockPos furnace = candidate.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                    .map(net.minecraft.core.GlobalPos::pos)
                    .orElseThrow(() -> helper.assertionException("Immediately recruited Miner did not bind the Furnace"));
            require(helper, helper.getLevel().getBlockState(furnace).is(Blocks.FURNACE),
                    "Immediately recruited Miner bound a non-Furnace job site");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            assignments.removeAssignment(candidate.getUUID());
            assignments.removeZone(minerZone.id());
            candidate.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerZoneWithoutFurnaceDoesNotFindOrBindAnExternalWorkstation(GameTestHelper helper) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(helper.getLevel().getServer());
        BlockPos zoneMinimum = new BlockPos(8, 1, 8);
        clearZone(helper, zoneMinimum);
        WorkZoneRecord minerZone = assignments.createZone("totem:miner", zone(helper, UUID.randomUUID(), zoneMinimum));
        Villager candidate = spawnUnemployed(helper, new BlockPos(7, 3, 4));
        try {
            require(helper, MinerFurnaceWorkstation.findUnclaimed(helper.getLevel(), candidate, minerZone.zone()).isEmpty(),
                    "Miner found a Furnace outside its assigned Work Zone");
            require(helper, MinerFurnaceWorkstation.ensureAssigned(helper.getLevel(), candidate, minerZone.zone()).isEmpty(),
                    "Miner bound an external Furnace despite an empty Work Zone");
            helper.succeed();
        } finally {
            assignments.removeAssignment(candidate.getUUID());
            assignments.removeZone(minerZone.id());
            candidate.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void nearbyMineFurnaceCanBeClaimedAcrossATransientPathfindingGap(GameTestHelper helper) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(helper.getLevel().getServer());
        BlockPos furnacePosition = new BlockPos(13, 10, 3);
        WorkZoneRecord minerZone = assignments.createZone("totem:miner",
                zone(helper, UUID.randomUUID(), new BlockPos(12, 9, 2)));
        helper.setBlock(furnacePosition, Blocks.FURNACE);
        Villager candidate = spawnUnemployed(helper, new BlockPos(3, 10, 3));
        try {
            BlockPos absoluteFurnace = helper.absolutePos(furnacePosition);
            require(helper, candidate.getNavigation().createPath(absoluteFurnace, 0) == null,
                    "Floating fixture unexpectedly produced a navigation path");
            require(helper, MinerFurnaceWorkstation.findUnclaimed(helper.getLevel(), candidate, minerZone.zone())
                            .filter(absoluteFurnace::equals).isPresent(),
                    "A nearby founding Furnace was rejected solely because pathfinding was temporarily unavailable");
            helper.succeed();
        } finally {
            assignments.removeAssignment(candidate.getUUID());
            assignments.removeZone(minerZone.id());
            candidate.discard();
        }
    }

    private static WorkZone zone(GameTestHelper helper, UUID owner, BlockPos minimum) {
        BlockPos absoluteMinimum = helper.absolutePos(minimum);
        return new WorkZone(owner, helper.getLevel().dimension().identifier().toString(),
                new dev.totem.villagers.worker.BlockCoordinate(absoluteMinimum.getX(), absoluteMinimum.getY(), absoluteMinimum.getZ()),
                new dev.totem.villagers.worker.BlockCoordinate(absoluteMinimum.getX() + 2, absoluteMinimum.getY() + 3,
                        absoluteMinimum.getZ() + 2));
    }

    private static void clearZone(GameTestHelper helper, BlockPos minimum) {
        for (int x = minimum.getX(); x <= minimum.getX() + 2; x++) {
            for (int y = minimum.getY(); y <= minimum.getY() + 3; y++) {
                for (int z = minimum.getZ(); z <= minimum.getZ() + 2; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnUnemployed(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession unemployed = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "unemployed"));
        if (unemployed == null) {
            throw new IllegalStateException("Missing minecraft:unemployed profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(unemployed)));
        return villager;
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
