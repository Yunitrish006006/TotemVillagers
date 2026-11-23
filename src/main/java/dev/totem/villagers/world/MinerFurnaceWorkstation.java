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
    /** Nearby founding workstations can be claimed despite a transient pathfinding miss on uneven village terrain. */
    private static final double REACH_SQUARED = 16.0D * 16.0D;

    private static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<Boolean> GENERATED =
            net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.createPersistent(
                    Identifier.parse("totem-villagers:generated_mine_furnace"), com.mojang.serialization.Codec.BOOL);

    public static void register() {}

    public static void markGenerated(ServerLevel level, BlockPos station) {
        if (level.getBlockEntity(station) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace)
            ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) furnace).setAttached(GENERATED, true);
    }

    public static boolean privateStationAvailable(ServerLevel level, Villager worker, WorkZone zone, BlockPos station) {
        if (!zone.naturalQuarry()) return true;
        return zone.ownerId().equals(worker.getUUID()) && level.isLoaded(station)
                && zone.dimensionId().equals(level.dimension().identifier().toString())
                && station.equals(new BlockPos(zone.minimum().x() + 2, zone.minimum().y() + 1, zone.minimum().z() + 2))
                && level.getBlockEntity(station) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace
                && !furnace.isLocked() && furnace.getCustomName() == null && furnace.isEmpty()
                && !level.getBlockState(station).getValue(BlockStateProperties.LIT)
                && WorldWorkPermissions.mayWork(level, worker, station);
    }

    public static boolean mayUseAssignedStation(ServerLevel level, Villager worker, BlockPos station) {
        var jobs = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(level.getServer());
        return jobs.getAssignment(worker.getUUID()).flatMap(a -> a.workZoneId().flatMap(jobs::getZone))
                .map(r -> privateStationAvailable(level, worker, r.zone(), station)).orElse(true);
    }

    private MinerFurnaceWorkstation() {
    }

    /** Uses the existing valid job site or claims an unclaimed, reachable Furnace in the assigned Zone. */
    public static Optional<BlockPos> ensureAssigned(ServerLevel level, Villager miner, WorkZone zone) {
        Optional<BlockPos> current = miner.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(site -> insideZone(level, zone, site) && level.isLoaded(site) && level.getBlockState(site).is(Blocks.FURNACE)
                        && WorldWorkPermissions.mayWork(level, miner, site) && privateStationAvailable(level, miner, zone, site));
        if (current.isPresent()) {
            ensureGeneratedMineEntranceOpen(level, miner, zone, current.orElseThrow());
            return current;
        }
        Optional<BlockPos> claimed = findUnclaimed(level, miner, zone);
        claimed.ifPresent(site -> {
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), site));
            ensureGeneratedMineEntranceOpen(level, miner, zone, site);
        });
        return claimed;
    }

    /**
     * Repairs mines generated before the entrance became navigation-safe.
     * The stair signature prevents an ordinary player Furnace and gate from
     * being changed just because both happen to lie in a custom work zone.
     */
    private static void ensureGeneratedMineEntranceOpen(ServerLevel level, Villager miner, WorkZone zone, BlockPos furnace) {
        if (zone.personal()) return;
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
            if (!WorldWorkPermissions.mayWork(level, miner, gate)
                    || !(level.getBlockEntity(furnace) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity entity)) return;
            if (!Boolean.TRUE.equals(((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget) entity).getAttached(GENERATED))) {
                var jobs = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(level.getServer());
                var assigned = jobs.getAssignment(miner.getUUID()).flatMap(a -> a.workZoneId()).flatMap(jobs::getZone).orElse(null);
                if (assigned == null || !zone.equals(assigned.zone())
                        || dev.totem.villagers.worldgen.GeneratedVillageSavedData.forServer(level.getServer()).snapshot().stream()
                        .noneMatch(v -> v.dimensionId().equals(level.dimension().identifier().toString())
                                && v.minerZoneId().filter(assigned.id()::equals).isPresent())
                        || !dev.totem.villagers.runtime.VillageWorldgenBootstrapRuntime.originalPoolFurnace(level, zone, furnace)) return;
                markGenerated(level, furnace);
            }
            level.setBlock(gate, gateState.setValue(BlockStateProperties.OPEN, true), 3);
        }
    }

    /** Finds but does not mutate the candidate, for role allocation to stay transactional. */
    public static Optional<BlockPos> findUnclaimed(ServerLevel level, Villager miner, WorkZone zone) {
        if (!zone.dimensionId().equals(level.dimension().identifier().toString())) return Optional.empty();
        if (zone.naturalQuarry()) {
            var station = new BlockPos(zone.minimum().x() + 2, zone.minimum().y() + 1, zone.minimum().z() + 2);
            if (!privateStationAvailable(level, miner, zone, station) || claimedByAnotherMiner(level, miner, station)
                    || level.structureManager().getStructureWithPieceAt(station, holder -> true).isValid()
                    || !WorldWorkNavigation.canReach(level, miner, station)) return Optional.empty();
            return Optional.of(station);
        }
        BlockPos origin = miner.blockPosition();
        int lowY = Math.max(origin.getY() - VERTICAL_RADIUS, zone.minimum().y());
        int highY = Math.min(origin.getY() + VERTICAL_RADIUS, zone.maximum().y());
        int minX = Math.max(origin.getX() - SEARCH_RADIUS, zone.minimum().x());
        int maxX = Math.min(origin.getX() + SEARCH_RADIUS, zone.maximum().x());
        int minZ = Math.max(origin.getZ() - SEARCH_RADIUS, zone.minimum().z());
        int maxZ = Math.min(origin.getZ() + SEARCH_RADIUS, zone.maximum().z());
        var stations = new java.util.ArrayList<BlockPos>();
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var pos : chunk.getBlockEntities().keySet()) {
                    if (pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ
                            && pos.getY() >= lowY && pos.getY() <= highY && chunk.getBlockState(pos).is(Blocks.FURNACE))
                        stations.add(pos);
                }
            }
        }
        stations.sort(java.util.Comparator.comparingInt((BlockPos pos) -> Math.max(
                Math.abs(pos.getX()-origin.getX()), Math.abs(pos.getZ()-origin.getZ())))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY));
        for (var station : stations) {
            if (!WorldWorkPermissions.mayWork(level, miner, station) || claimedByAnotherMiner(level, miner, station)) continue;
            if (miner.distanceToSqr(Vec3.atCenterOf(station)) <= REACH_SQUARED) return Optional.of(station);
            var path = miner.getNavigation().createPath(station, 1);
            if (path != null && path.canReach()) return Optional.of(station);
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
