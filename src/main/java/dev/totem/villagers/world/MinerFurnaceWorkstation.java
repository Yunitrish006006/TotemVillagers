package dev.totem.villagers.world;

import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.runtime.LoadedVillagerCache;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The physical Furnace job site for the custom Miner role. Custom professions
 * never claim arbitrary POIs through vanilla AI, so this service performs the
 * same bounded, loaded-only claim explicitly when a Miner is assigned. A
 * Furnace must be inside the Miner's assigned Work Zone, rather than letting
 * the worker silently claim an unrelated village Furnace.
 */
public final class MinerFurnaceWorkstation {
    /** Matches the maximum radius used to place a generated village mine from a resident. */
    private static final int SEARCH_RADIUS = 24;
    private static final int VERTICAL_RADIUS = 4;
    private static final int MAX_CHECKS = 12_000;
    /** Nearby founding workstations can be claimed despite a transient pathfinding miss on uneven village terrain. */
    private static final double REACH_SQUARED = 16.0D * 16.0D;

    private MinerFurnaceWorkstation() {
    }

    /** Uses the existing valid job site or claims an unclaimed, reachable Furnace in the assigned Zone. */
    public static Optional<BlockPos> ensureAssigned(ServerLevel level, Villager miner, WorkZone zone) {
        Optional<BlockPos> current = miner.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(site -> insideZone(level, zone, site) && level.isLoaded(site) && level.getBlockState(site).is(Blocks.FURNACE));
        if (current.isPresent()) {
            ensureGeneratedMineEntranceOpen(level, current.orElseThrow());
            return current;
        }
        Optional<BlockPos> claimed = findUnclaimed(level, miner, zone);
        claimed.ifPresent(site -> {
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), site));
            ensureGeneratedMineEntranceOpen(level, site);
        });
        return claimed;
    }

    /**
     * Repairs mines generated before the entrance became navigation-safe.
     * The stair signature prevents an ordinary player Furnace and gate from
     * being changed just because both happen to lie in a custom work zone.
     */
    private static void ensureGeneratedMineEntranceOpen(ServerLevel level, BlockPos furnace) {
        BlockPos firstStair = VillageUtilityFeature.mineLanding(furnace, 0).below();
        BlockPos gate = VillageUtilityFeature.mineGate(furnace);
        if (!level.isLoaded(firstStair) || !level.isLoaded(gate)
                || !level.getBlockState(firstStair).is(Blocks.COBBLESTONE_STAIRS)) {
            return;
        }
        var gateState = level.getBlockState(gate);
        if (gateState.getBlock() instanceof FenceGateBlock
                && gateState.hasProperty(BlockStateProperties.OPEN)
                && !gateState.getValue(BlockStateProperties.OPEN)) {
            level.setBlock(gate, gateState.setValue(BlockStateProperties.OPEN, true), 3);
        }
    }

    /** Finds but does not mutate the candidate, for role allocation to stay transactional. */
    public static Optional<BlockPos> findUnclaimed(ServerLevel level, Villager miner, WorkZone zone) {
        BlockPos origin = miner.blockPosition();
        int checks = 0;
        for (int radius = 0; radius <= SEARCH_RADIUS && checks < MAX_CHECKS; radius++) {
            for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < MAX_CHECKS; x++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < MAX_CHECKS; z++) {
                    if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                            && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                        continue;
                    }
                    for (int y = origin.getY() - VERTICAL_RADIUS; y <= origin.getY() + VERTICAL_RADIUS && checks < MAX_CHECKS; y++) {
                        BlockPos station = new BlockPos(x, y, z);
                        checks++;
                        if (insideZone(level, zone, station) && level.isLoaded(station) && level.getBlockState(station).is(Blocks.FURNACE)
                                && !claimedByAnotherMiner(level, miner, station)
                                && (miner.distanceToSqr(Vec3.atCenterOf(station)) <= REACH_SQUARED
                                    || miner.getNavigation().createPath(station, 0) != null)) {
                            return Optional.of(station);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean insideZone(ServerLevel level, WorkZone zone, BlockPos station) {
        return zone.contains(level.dimension().identifier().toString(),
                new BlockCoordinate(station.getX(), station.getY(), station.getZ()));
    }

    private static boolean claimedByAnotherMiner(ServerLevel level, Villager miner, BlockPos station) {
        return LoadedVillagerCache.loaded(level).stream()
                .filter(other -> other != miner && isMiner(other))
                .map(other -> other.getBrain().getMemory(MemoryModuleType.JOB_SITE))
                .flatMap(Optional::stream)
                .anyMatch(site -> site.dimension().equals(level.dimension()) && site.pos().equals(station));
    }

    private static boolean isMiner(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && Identifier.fromNamespaceAndPath("totem", "miner").equals(id);
    }
}
