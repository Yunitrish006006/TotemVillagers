package dev.totem.villagers.gametest;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.ProductionParcel;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Loaded only from the GameTest source set. The three runs share a world and never share a Java process. */
public final class HouseholdRestartProbe implements ModInitializer {
    private static final BlockPos BED = new BlockPos(6, 100, 6);
    private static final Path PHASE = Path.of("household-test-phase");
    private static final Path SAVED = Path.of("household-test-saved");
    private static final Path PASSED = Path.of("household-test-passed");
    private final dev.totem.villagers.runtime.HarvestRestartFixture forecast = new dev.totem.villagers.runtime.HarvestRestartFixture();
    private UUID resident;
    private BlockPos oldSoil;
    private boolean resume;
    private boolean verify;
    private BlockPos secondSoil;
    private boolean restored;
    private boolean finished;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("totem.villagers.householdRestart")) return;
        forecast.watchLoad();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var phase = Files.readString(PHASE).trim();
                verify = phase.equals("verify");
                resume = switch (phase) {
                    case "prepare" -> false;
                    case "resume", "verify" -> true;
                    default -> throw new IllegalArgumentException("Unexpected household restart test phase");
                };
                var level = server.overworld();
                level.setChunkForced(0, 0, true);
                level.getChunkAt(BED);
                if (resume) {
                    forecast.restore();
                    var record = Files.readAllLines(SAVED);
                    resident = UUID.fromString(record.getFirst());
                    oldSoil = BlockPos.of(Long.parseLong(record.get(1)));
                    if (verify) secondSoil = BlockPos.of(Long.parseLong(record.get(2)));
                } else {
                    resident = prepare(level).getUUID();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read disposable household fixture", e);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (finished || resident == null) return;
            var level = server.overworld();
            boolean forecastReady = !resume || forecast.observe(level, verify, server.getTickCount());
            var worker = level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(BED).inflate(18),
                    v -> resident.equals(v.getUUID())).stream().findFirst().orElse(null);
            if (worker != null) {
                var claim = ProductionParcel.get(server).claim(resident).orElse(null);
                var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(resident);
                if (claim != null && claim.role().equals("starter") && claim.home().filter(h -> h.equals(GlobalPos.of(level.dimension(), BED))).isPresent()
                        && worker.getBrain().getMemory(MemoryModuleType.HOME).equals(claim.home())) {
                    if (!resume && claim.cells().size() == 1 && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0) {
                        forecast.prepare(level);
                        oldSoil = claim.cells().getFirst();
                        if (!inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS))) throw new IllegalStateException("Cannot store second seed");
                        server.saveEverything(false, true, false);
                        write(SAVED, resident + "\n" + oldSoil.asLong());
                        finished = true;
                        server.halt(false);
                    } else if (resume && claim.cells().contains(oldSoil) && level.getBlockState(oldSoil.above()).is(Blocks.WHEAT)) {
                        if (claim.cells().size() == 1 && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 1
                                && inventory.snapshot().stream().anyMatch(s -> s.is(Items.WOODEN_HOE) && s.getDamageValue() == 1)) restored = true;
                        if (forecastReady && (verify ? claim.cells().contains(secondSoil) : restored) && claim.cells().size() == 2 && claim.cells().stream().allMatch(p -> level.getBlockState(p.above()).is(Blocks.WHEAT))
                                && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0
                                && inventory.snapshot().stream().anyMatch(s -> s.is(Items.WOODEN_HOE) && s.getDamageValue() == 2)) {
                            if (verify) {
                                write(PASSED, "Two restarts retained the same resident, home, both paid plots and two tool uses. A separate farmer retained its harvest interval while its chunk stayed unloaded.");
                            } else {
                                var second = claim.cells().stream().filter(p -> !p.equals(oldSoil)).findFirst().orElseThrow();
                                server.saveEverything(false, true, false);
                                write(SAVED, resident + "\n" + oldSoil.asLong() + "\n" + second.asLong());
                            }
                            finished = true;
                            server.halt(false);
                        }
                    }
                }
            }
            if (!finished && server.getTickCount() > 2400) {
                finished = true;
                server.halt(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Villager prepare(ServerLevel level) {
        for (var p : BlockPos.betweenClosed(BED.offset(-4, -1, -4), BED.offset(5, -1, 5))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
            if (p.getX() == BED.getX() - 4 || p.getX() == BED.getX() + 5
                    || p.getZ() == BED.getZ() - 4 || p.getZ() == BED.getZ() + 5)
                level.setBlock(p.above(), Blocks.OAK_FENCE.defaultBlockState(), 2);
        }
        var bed = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(BED.south(), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(BED, bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        level.setBlock(BED.east(2).below(), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(BED.east(2), Blocks.OAK_SLAB.defaultBlockState(), 2);
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var worker = type.create(level, EntitySpawnReason.COMMAND);
        var start = BED.north();
        worker.snapTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        if (!level.addFreshEntity(worker)) throw new IllegalStateException("Cannot spawn test household");
        var inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(worker.getUUID());
        inventory.insertExact(new ItemStack(Items.WOODEN_HOE));
        inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS));
        return worker;
    }

    private static void write(Path path, String evidence) {
        try {
            Files.writeString(path, evidence);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write household test evidence", e);
        }
    }
}
