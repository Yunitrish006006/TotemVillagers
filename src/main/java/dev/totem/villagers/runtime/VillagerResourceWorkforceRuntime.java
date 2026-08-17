package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Safe resource-workforce bootstrap for an opt-in, configured village. It uses
 * only unemployed adults and never replaces a manual assignment or an existing
 * trade profession. Once a Miner or Lumberjack Work Zone exists nearby, roles
 * are filled in this strict order: Farmer, Miner, Lumberjack, then Toolsmith.
 */
public final class VillagerResourceWorkforceRuntime {
    private static final long ALLOCATION_INTERVAL_TICKS = 100L;
    private static final int FARMER_SITE_RADIUS = 16;
    private static final int FARMER_SITE_MAX_CHECKS = 384;
    /** Covers every perimeter cell and the seven searched Y levels through radius 16. */
    private static final int TOOLSMITH_SITE_MAX_CHECKS = 8_192;
    private static final double RESOURCE_ZONE_RECRUIT_RANGE_SQUARED = 64.0D * 64.0D;

    private VillagerResourceWorkforceRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerResourceWorkforceRuntime::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager villager) {
                recruitLoadedVillager(level, villager);
            }
        });
    }

    /** Public only for deterministic GameTests; the live server allocates every five seconds. */
    public static void tickForGameTest(MinecraftServer server) {
        allocate(server);
    }

    /**
     * Lets generated-village bootstrap fill all newly created resource sites in
     * the same Farmer → Miner → Lumberjack → Toolsmith order before vanilla POI claiming can
     * consume the remaining unemployed residents.
     */
    static void allocateFoundingWorkforce(MinecraftServer server) {
        allocate(server);
    }

    /** Public only for deterministic GameTests of the live entity-load recruitment path. */
    public static void recruitLoadedVillagerForGameTest(ServerLevel level, Villager villager) {
        recruitLoadedVillager(level, villager);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED
                || server.overworld().getGameTime() % ALLOCATION_INTERVAL_TICKS != 0L) {
            return;
        }
        allocate(server);
    }

    /**
     * A newly spawned or newly loaded unemployed adult must be considered before
     * vanilla POI AI can turn it into an unrelated profession.  The periodic
     * allocator remains as a recovery path for pre-existing villagers.
     */
    private static void recruitLoadedVillager(ServerLevel level, Villager villager) {
        MinecraftServer server = level.getServer();
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED
                || !isEligibleCandidate(villager, WorkerAssignmentSavedData.forServer(server))) {
            return;
        }
        allocate(server);
    }

    private static void allocate(MinecraftServer server) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        Map<UUID, WorkZoneRecord> zones = assignments.zoneSnapshot();
        for (ServerLevel level : server.getAllLevels()) {
            List<WorkZoneRecord> resourceZones = zones.values().stream()
                    .filter(zone -> isResourceRole(zone.roleId()))
                    .filter(zone -> zone.zone().dimensionId().equals(level.dimension().identifier().toString()))
                    .toList();
            if (resourceZones.isEmpty()) {
                continue;
            }
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            List<Villager> candidates = villagers.stream()
                    .filter(villager -> isEligibleCandidate(villager, assignments))
                    .sorted(Comparator.comparing(villager -> villager.getUUID().toString()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            Set<UUID> staffedZones = staffedResourceZones(assignments.assignmentSnapshot());

            assignFarmers(level, villagers, candidates, resourceZones);
            assignSpecialists(level, candidates, resourceZones, staffedZones, TotemVillagerProfessions.MINER_ID, assignments);
            assignSpecialists(level, candidates, resourceZones, staffedZones, TotemVillagerProfessions.LUMBERJACK_ID, assignments);
            assignToolsmith(level, villagers, candidates, resourceZones);
        }
    }

    /** Uses only the fourth remaining unemployed adult; the three founding resource roles always run first. */
    private static void assignToolsmith(
            ServerLevel level,
            List<? extends Villager> villagers,
            List<Villager> candidates,
            List<WorkZoneRecord> resourceZones
    ) {
        VillagerProfession toolsmith = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "toolsmith"));
        if (toolsmith == null) {
            return;
        }
        for (Villager candidate : candidates) {
            if (!isUnemployed(candidate) || !nearResourceZone(candidate, resourceZones)) {
                continue;
            }
            Optional<BlockPos> site = findUnstaffedJobSite(level, candidate, villagers, Blocks.SMITHING_TABLE,
                    "minecraft:toolsmith");
            Optional<BlockPos> claimed = site.flatMap(position -> claimExactJobSite(level, toolsmith, position));
            if (claimed.isEmpty()) {
                continue;
            }
            establishVanillaProfession(level, candidate, toolsmith, claimed.orElseThrow());
        }
    }

    private static void assignFarmers(
            ServerLevel level,
            List<? extends Villager> villagers,
            List<Villager> candidates,
            List<WorkZoneRecord> resourceZones
    ) {
        VillagerProfession farmer = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        if (farmer == null) {
            return;
        }
        for (Villager candidate : candidates) {
            if (!isUnemployed(candidate) || !nearResourceZone(candidate, resourceZones)) {
                continue;
            }
            Optional<BlockPos> site = findUnstaffedComposter(level, candidate, villagers);
            Optional<BlockPos> claimed = site.flatMap(position -> claimExactJobSite(level, farmer, position));
            if (claimed.isEmpty()) {
                continue;
            }
            establishVanillaProfession(level, candidate, farmer, claimed.orElseThrow());
        }
    }

    private static Optional<BlockPos> claimExactJobSite(
            ServerLevel level, VillagerProfession profession, BlockPos site
    ) {
        return level.getPoiManager().take(profession.heldJobSite(),
                (poi, candidate) -> candidate.equals(site), site, 1).filter(site::equals);
    }

    private static void establishVanillaProfession(
            ServerLevel level, Villager villager, VillagerProfession profession, BlockPos jobSite
    ) {
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        villager.setVillagerDataFinalized(true);
        villager.refreshBrain(level);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), jobSite));
    }

    private static void assignSpecialists(
            ServerLevel level,
            List<Villager> candidates,
            List<WorkZoneRecord> resourceZones,
            Set<UUID> staffedZones,
            Identifier roleId,
            WorkerAssignmentSavedData assignments
    ) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(roleId);
        if (profession == null) {
            return;
        }
        for (Villager candidate : candidates) {
            if (!isUnemployed(candidate)) {
                continue;
            }
            Optional<WorkZoneRecord> zone = resourceZones.stream()
                    .filter(candidateZone -> candidateZone.roleId().equals(roleId.toString()))
                    .filter(candidateZone -> !staffedZones.contains(candidateZone.id()))
                    .filter(candidateZone -> distanceToZoneSquared(candidate, candidateZone) <= RESOURCE_ZONE_RECRUIT_RANGE_SQUARED)
                    .min(Comparator.comparing(entry -> entry.id().toString()));
            if (zone.isEmpty()) {
                continue;
            }
            WorkZoneRecord selected = zone.orElseThrow();
            Optional<BlockPos> station = roleId.equals(TotemVillagerProfessions.MINER_ID)
                    ? MinerFurnaceWorkstation.findUnclaimed(level, candidate, selected.zone())
                    : Optional.empty();
            if (roleId.equals(TotemVillagerProfessions.MINER_ID) && station.isEmpty()) {
                continue;
            }
            candidate.setVillagerData(candidate.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
            station.ifPresent(site -> candidate.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(level.dimension(), site)));
            assignments.putAssignment(new WorkerAssignment(candidate.getUUID(), roleId.toString(),
                    Optional.of(selected.id()), Optional.empty()));
            staffedZones.add(selected.id());
        }
    }

    private static boolean isEligibleCandidate(Villager villager, WorkerAssignmentSavedData assignments) {
        return !villager.isBaby() && isUnemployed(villager) && assignments.getAssignment(villager.getUUID()).isEmpty();
    }

    private static boolean isUnemployed(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && ("minecraft:unemployed".equals(id.toString()) || "minecraft:none".equals(id.toString()));
    }

    private static Optional<BlockPos> findUnstaffedComposter(
            ServerLevel level,
            Villager candidate,
            List<? extends Villager> villagers
    ) {
        BlockPos origin = candidate.blockPosition();
        int checks = 0;
        for (int radius = 0; radius <= FARMER_SITE_RADIUS && checks < FARMER_SITE_MAX_CHECKS; radius++) {
            for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < FARMER_SITE_MAX_CHECKS; x++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < FARMER_SITE_MAX_CHECKS; z++) {
                    if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                            && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                        continue;
                    }
                    for (int y = origin.getY() - 4; y <= origin.getY() + 2 && checks < FARMER_SITE_MAX_CHECKS; y++) {
                        BlockPos site = new BlockPos(x, y, z);
                        checks++;
                        if (!level.isLoaded(site) || !level.getBlockState(site).is(Blocks.COMPOSTER)
                                || composterAlreadyStaffed(level, site, villagers)) {
                            continue;
                        }
                        return Optional.of(site);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findUnstaffedJobSite(
            ServerLevel level,
            Villager candidate,
            List<? extends Villager> villagers,
            net.minecraft.world.level.block.Block block,
            String professionId
    ) {
        BlockPos origin = candidate.blockPosition();
        int checks = 0;
        for (int radius = 0; radius <= FARMER_SITE_RADIUS && checks < TOOLSMITH_SITE_MAX_CHECKS; radius++) {
            for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < TOOLSMITH_SITE_MAX_CHECKS; x++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < TOOLSMITH_SITE_MAX_CHECKS; z++) {
                    if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                            && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                        continue;
                    }
                    for (int y = origin.getY() - 4; y <= origin.getY() + 2 && checks < TOOLSMITH_SITE_MAX_CHECKS; y++) {
                        BlockPos site = new BlockPos(x, y, z);
                        checks++;
                        if (!level.isLoaded(site) || !level.getBlockState(site).is(block)
                                || jobSiteAlreadyStaffed(level, site, villagers, professionId)) {
                            continue;
                        }
                        return Optional.of(site);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean jobSiteAlreadyStaffed(
            ServerLevel level, BlockPos site, List<? extends Villager> villagers, String professionId
    ) {
        return villagers.stream().anyMatch(villager -> professionId.equals(professionId(villager))
                && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(jobSite -> jobSite.dimension().equals(level.dimension()) && jobSite.pos().equals(site)).isPresent());
    }

    private static boolean composterAlreadyStaffed(ServerLevel level, BlockPos site, List<? extends Villager> villagers) {
        return villagers.stream().anyMatch(villager -> isFarmer(villager)
                && (villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                        .filter(jobSite -> jobSite.dimension().equals(level.dimension()) && jobSite.pos().equals(site)).isPresent()
                || villager.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(site)) <= 16.0D));
    }

    private static boolean isFarmer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:farmer".equals(id.toString());
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static Set<UUID> staffedResourceZones(Map<UUID, WorkerAssignment> assignments) {
        Set<UUID> result = new HashSet<>();
        assignments.values().stream()
                .filter(assignment -> isResourceRole(assignment.roleId()))
                .map(WorkerAssignment::workZoneId)
                .flatMap(Optional::stream)
                .forEach(result::add);
        return result;
    }

    private static boolean nearResourceZone(Villager villager, List<WorkZoneRecord> zones) {
        return zones.stream().anyMatch(zone -> distanceToZoneSquared(villager, zone) <= RESOURCE_ZONE_RECRUIT_RANGE_SQUARED);
    }

    private static double distanceToZoneSquared(Villager villager, WorkZoneRecord zone) {
        double x = clamp(villager.getX(), zone.zone().minimum().x(), zone.zone().maximum().x());
        double y = clamp(villager.getY(), zone.zone().minimum().y(), zone.zone().maximum().y());
        double z = clamp(villager.getZ(), zone.zone().minimum().z(), zone.zone().maximum().z());
        return villager.distanceToSqr(x, y, z);
    }

    private static double clamp(double value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isResourceRole(String roleId) {
        return TotemVillagerProfessions.MINER_ID.toString().equals(roleId)
                || TotemVillagerProfessions.LUMBERJACK_ID.toString().equals(roleId);
    }
}
