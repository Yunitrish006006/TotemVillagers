package dev.totem.villagers.client;

import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Captures a naturally generated Mangrove village in an unmodified default world. */
@SuppressWarnings("UnstableApiUsage")
public final class MangroveVillageNaturalWorldClientGameTest implements FabricClientGameTest {
    private static final long NATURAL_WORLD_SEED = 96874758687607637L;
    private static final BlockPos LOCATE_REFERENCE = new BlockPos(-8688, 62, 32);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setSeed(Long.toString(NATURAL_WORLD_SEED));
                    settings.setGenerateStructures(true);
                })
                .create()) {
            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set 1000");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run gamemode spectator @a");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a -8640 94 -14");

            // The distant teleport causes the candidate chunks to be generated
            // through the normal random-spread structure pipeline.
            context.waitTicks(240);
            singleplayer.getClientLevel().waitForChunksRender();
            BlockPos bell = verifyNaturalVillage(singleplayer);
            List<NaturalVillage> villages = naturalMangroveVillages(singleplayer);
            require(!villages.isEmpty(), "Natural worldgen did not persist a Mangrove-village bootstrap record");
            WorkerPair showcaseWorkers = null;
            for (NaturalVillage village : villages) {
                singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a "
                        + village.center().getX() + " " + (village.center().getY() + 18) + " " + village.center().getZ());
                context.waitTicks(100);
                singleplayer.getClientLevel().waitForChunksRender();
                WorkerPair workers = waitForNaturalWorkers(context, singleplayer, village);
                verifyFoundingFisherman(singleplayer, village);
                showcaseWorkers = workers;
            }
            require(showcaseWorkers != null, "Natural worker showcase pair was unavailable");
            int deckY = bell.getY() - 1;

            context.getInput().pressKey(GLFW.GLFW_KEY_F1);
            context.waitTicks(2);
            captureWorker(context, showcaseWorkers.lumberjack(), "totem-villagers-natural-lumberjack-working");
            captureWorker(context, showcaseWorkers.miner(), "totem-villagers-natural-miner-working");
            positionCamera(context, bell.getX() + 48.0D, bell.getY() + 27.0D, bell.getZ() - 46.0D,
                    new Vec3(bell.getX(), bell.getY() + 1.0D, bell.getZ()));
            context.waitTicks(8);
            context.takeScreenshot("totem-villagers-mangrove-village-natural-world-oblique");

            positionCamera(context, bell.getX(), bell.getY() + 65.0D, bell.getZ(),
                    new Vec3(bell.getX(), deckY, bell.getZ() + 0.01D));
            context.waitTicks(8);
            singleplayer.getClientLevel().waitForChunksRender();
            context.takeScreenshot("totem-villagers-mangrove-village-natural-world-aerial");
            context.getInput().pressKey(GLFW.GLFW_KEY_F1);
            context.waitTicks(2);
        }
    }

    private static List<NaturalVillage> naturalMangroveVillages(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
            return GeneratedVillageSavedData.forServer(server).snapshot().stream()
                    .filter(village -> village.id().contains("|totem:mangrove_village|"))
                    .filter(village -> village.minerZoneId().isPresent() && village.lumberjackZoneId().isPresent())
                    .filter(village -> assignments.getZone(village.minerZoneId().orElseThrow()).isPresent())
                    .map(village -> naturalVillage(village, assignments.getZone(village.minerZoneId().orElseThrow())
                            .orElseThrow().zone().minimum().y()))
                    .sorted(Comparator.comparing(NaturalVillage::id))
                    .toList();
        });
    }

    private static NaturalVillage naturalVillage(GeneratedVillageState village, int initialMineMinimumY) {
        return new NaturalVillage(village.id(), new BlockPos(
                (village.minimum().x() + village.maximum().x()) / 2,
                Math.max(village.minimum().y(), 64),
                (village.minimum().z() + village.maximum().z()) / 2),
                village.minimum().x(), village.minimum().y(), village.minimum().z(),
                village.maximum().x(), village.maximum().y(), village.maximum().z(),
                village.minerZoneId().orElseThrow(), village.lumberjackZoneId().orElseThrow(), initialMineMinimumY);
    }

    private static WorkerPair waitForNaturalWorkers(ClientGameTestContext context,
                                                     TestSingleplayerContext singleplayer,
                                                     NaturalVillage village) {
        WorkerPair latest = workerPair(singleplayer, village);
        MineProgress mineProgress = mineProgress(singleplayer, village);
        for (int attempt = 0; attempt < 25 && !(latest.complete() && mineProgress.extended()); attempt++) {
            context.waitTicks(20);
            latest = workerPair(singleplayer, village);
            mineProgress = mineProgress(singleplayer, village);
        }
        require(latest.complete() && mineProgress.extended(),
                "Natural village workers did not complete real world work and extend the Mine in " + village.id()
                + "; miner={" + latest.miner().facts() + "}; mine={"
                + minerZoneFacts(singleplayer, village, latest.miner().id())
                + ",initialMinimumY=" + village.initialMineMinimumY()
                + ",currentMinimumY=" + mineProgress.minimumY() + "}; lumberjack={"
                + latest.lumberjack().facts() + "}");
        return latest;
    }

    private static MineProgress mineProgress(TestSingleplayerContext singleplayer, NaturalVillage village) {
        return singleplayer.getServer().computeOnServer(server -> WorkerAssignmentSavedData.forServer(server)
                .getZone(village.minerZoneId())
                .map(record -> new MineProgress(record.zone().minimum().y(),
                        record.zone().minimum().y() < village.initialMineMinimumY()))
                .orElse(new MineProgress(Integer.MAX_VALUE, false)));
    }

    private static String minerZoneFacts(TestSingleplayerContext singleplayer, NaturalVillage village, UUID minerId) {
        return singleplayer.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var assignments = WorkerAssignmentSavedData.forServer(server);
            var zoneRecord = assignments.zoneSnapshot().get(village.minerZoneId());
            Entity entity = level.getEntityInAnyDimension(minerId);
            if (zoneRecord == null || !(entity instanceof Villager miner)) {
                return "zone or Miner unavailable";
            }
            var zone = zoneRecord.zone();
            TagKey<Block> targets = TagKey.create(Registries.BLOCK,
                    Identifier.fromNamespaceAndPath("totem", "miner_targets"));
            List<BlockPos> tagged = BlockPos.betweenClosedStream(
                            zone.minimum().x(), zone.minimum().y(), zone.minimum().z(),
                            zone.maximum().x(), zone.maximum().y(), zone.maximum().z())
                    .map(BlockPos::immutable)
                    .filter(position -> level.getBlockState(position).is(targets))
                    .sorted(Comparator.comparingDouble(position -> position.distSqr(miner.blockPosition())))
                    .limit(16)
                    .toList();
            String candidates = tagged.stream().map(position -> position + "[near="
                            + WorldWorkNavigation.isWithinReach(miner, position) + ",path="
                            + WorldWorkNavigation.pathToReach(level, miner, position)
                            .map(path -> path.canReach() + "/" + path.getNodeCount() + "/"
                                    + BlockPos.containing(path.getEntityPosAtNode(miner, path.getNodeCount() - 1)))
                            .orElse("none") + "]")
                    .toList().toString();
            return "bounds=" + zone.minimum() + ".." + zone.maximum() + ",closestTargets=" + candidates;
        });
    }

    private static WorkerPair workerPair(TestSingleplayerContext singleplayer, NaturalVillage village) {
        return singleplayer.getServer().computeOnServer(server -> {
            WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
            UUID minerId = assignedWorker(assignments, village.minerZoneId()).orElse(null);
            UUID lumberjackId = assignedWorker(assignments, village.lumberjackZoneId()).orElse(null);
            return new WorkerPair(workerProbe(server.overworld(), minerId, "totem:miner"),
                    workerProbe(server.overworld(), lumberjackId, "totem:lumberjack"));
        });
    }

    private static Optional<UUID> assignedWorker(WorkerAssignmentSavedData assignments, UUID zoneId) {
        return assignments.assignmentSnapshot().values().stream()
                .filter(assignment -> assignment.workZoneId().filter(zoneId::equals).isPresent())
                .map(assignment -> assignment.villagerId())
                .findFirst();
    }

    private static WorkerProbe workerProbe(net.minecraft.server.level.ServerLevel level, UUID workerId,
                                           String expectedProfession) {
        if (workerId == null) {
            return WorkerProbe.missing(expectedProfession, "zone has no assigned worker");
        }
        Entity entity = level.getEntityInAnyDimension(workerId);
        if (!(entity instanceof Villager villager)) {
            return WorkerProbe.missing(expectedProfession, "assigned entity is not loaded: " + workerId);
        }
        List<ItemStack> inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).snapshot(workerId);
        boolean toolWorked = inventory.stream().anyMatch(stack -> !stack.isEmpty() && stack.getDamageValue() > 0
                && ("totem:miner".equals(expectedProfession) ? stack.is(Items.IRON_PICKAXE) : stack.is(Items.IRON_AXE)));
        String profession = professionId(villager);
        var state = VillagerWorkSavedData.forServer(level.getServer()).get(workerId);
        String work = state.map(value -> value.activeWork()
                        .map(active -> active.orderId() + "@" + active.elapsedTicks() + active.worldTarget()
                                .flatMap(target -> target.packedBlockPosition()).map(BlockPos::of)
                                .map(target -> ",target=" + target + ",reachable="
                                        + WorldWorkNavigation.canReach(level, villager, target)).orElse(""))
                        .orElseGet(() -> value.diagnostic()
                                .map(diagnostic -> diagnostic.orderId() + ":" + diagnostic.blockedReason())
                                .orElse("idle")))
                .orElse("no-state");
        String items = inventory.stream().filter(stack -> !stack.isEmpty())
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
                        + (stack.getDamageValue() > 0 ? "#" + stack.getDamageValue() : ""))
                .sorted().toList().toString();
        String facts = "id=" + workerId + ",profession=" + profession + ",pos=" + villager.blockPosition()
                + ",onGround=" + villager.onGround() + ",food=" + VillagerNutrition.foodLevel(villager)
                + ",work=" + work + ",inventory=" + items;
        return new WorkerProbe(workerId, expectedProfession, villager.position(),
                expectedProfession.equals(profession) && toolWorked, facts);
    }

    private static void verifyFoundingFisherman(TestSingleplayerContext singleplayer, NaturalVillage village) {
        String facts = singleplayer.getServer().computeOnServer(server -> {
            List<? extends Villager> residents = server.overworld().getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Villager.class), Villager::isAlive).stream()
                    .filter(villager -> village.contains(villager.blockPosition(), 16))
                    .toList();
            long fishermen = residents.stream().filter(villager -> "minecraft:fisherman".equals(professionId(villager))).count();
            return fishermen == 1L ? "" : residents.stream()
                    .map(villager -> professionId(villager) + "@" + villager.blockPosition()).sorted().toList().toString();
        });
        require(facts.isEmpty(), "Natural Mangrove founding Fisherman lost its profession: " + facts);
    }

    private static void captureWorker(ClientGameTestContext context, WorkerProbe worker, String screenshotName) {
        Vec3 position = worker.position();
        positionCamera(context, position.x + 4.0D, position.y + 1.8D, position.z + 4.0D,
                position.add(0.0D, 1.1D, 0.0D));
        context.waitTicks(8);
        context.takeScreenshot(screenshotName);
    }

    private static BlockPos verifyNaturalVillage(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            var level = server.overworld();
            BlockPos bell = findVillageBell(level);
            BlockPos barrel = bell.offset(-10, 0, -6);
            BlockPos woodcutter = bell.offset(-8, 0, 11);
            BlockPos furnace = bell.offset(9, 0, 11);

            require(level.getBiome(bell).is(Biomes.MANGROVE_SWAMP),
                    "Expected the natural structure candidate to be in a Mangrove Swamp");
            require(level.getBlockState(bell).is(Blocks.BELL),
                    "Natural worldgen did not place the village Bell at " + bell);
            require(level.getBlockState(barrel).is(Blocks.BARREL),
                    "Natural worldgen did not place the Fisherman Barrel at " + barrel);
            require(level.getBlockState(woodcutter).is(TotemVillagerBlocks.WOODCUTTER),
                    "Natural worldgen did not place the Lumberjack Woodcutter at " + woodcutter);
            require(level.getBlockState(furnace).is(Blocks.FURNACE),
                    "Natural worldgen did not place the Miner Furnace at " + furnace);
            return bell;
        });
    }

    private static BlockPos findVillageBell(net.minecraft.server.level.ServerLevel level) {
        for (int radius = 0; radius <= 64; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    for (int y = 60; y <= 80; y++) {
                        BlockPos candidate = new BlockPos(
                                LOCATE_REFERENCE.getX() + x, y, LOCATE_REFERENCE.getZ() + z);
                        if (level.getBlockState(candidate).is(Blocks.BELL)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        throw new AssertionError("Natural worldgen placed no village Bell within 64 blocks of "
                + LOCATE_REFERENCE);
    }

    private static void positionCamera(ClientGameTestContext context, double x, double y, double z, Vec3 target) {
        context.runOnClient(client -> {
            if (client.player == null) {
                throw new AssertionError("Natural-world Mangrove-village camera player was unavailable");
            }
            client.player.setPosRaw(x, y, z);
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
            client.player.xo = client.player.getX();
            client.player.yo = client.player.getY();
            client.player.zo = client.player.getZ();
            client.player.yRotO = client.player.getYRot();
            client.player.xRotO = client.player.getXRot();
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private record NaturalVillage(String id, BlockPos center,
                                  int minimumX, int minimumY, int minimumZ,
                                  int maximumX, int maximumY, int maximumZ,
                                  UUID minerZoneId, UUID lumberjackZoneId, int initialMineMinimumY) {
        private boolean contains(BlockPos position, int margin) {
            return position.getX() >= minimumX - margin && position.getX() <= maximumX + margin
                    && position.getY() >= minimumY - margin && position.getY() <= maximumY + margin
                    && position.getZ() >= minimumZ - margin && position.getZ() <= maximumZ + margin;
        }
    }

    private record MineProgress(int minimumY, boolean extended) {
    }

    private record WorkerPair(WorkerProbe miner, WorkerProbe lumberjack) {
        private boolean complete() {
            return miner.worked() && lumberjack.worked();
        }
    }

    private record WorkerProbe(UUID id, String role, Vec3 position, boolean worked, String facts) {
        private static WorkerProbe missing(String role, String facts) {
            return new WorkerProbe(new UUID(0L, 0L), role, Vec3.ZERO, false, facts);
        }
    }
}
