package dev.totem.villagers.runtime;

import dev.totem.villagers.worker.ProductionParcel;

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
        MinerFurnaceWorkstation.register();
        HouseholdResourceRequests.register();
        ServerTickEvents.END_SERVER_TICK.register(VillagerResourceWorkforceRuntime::tick);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Villager && entity.getRemovalReason() != null && entity.getRemovalReason().shouldDestroy()) {
                ProductionParcel.get(level.getServer()).release(entity.getUUID());
                var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
                assignments.getAssignment(entity.getUUID()).filter(a -> isResourceRole(a.roleId()))
                        .ifPresent(a -> assignments.removeAssignment(a.villagerId()));
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager villager) {
                recruitLoadedVillager(level, villager);
            }
        });
    }

    /** Public only for deterministic GameTests; the live server allocates every five seconds. */
    public static void tickForGameTest(MinecraftServer server) {
        allocate(server, Set.of());
    }

    /**
     * Lets generated-village bootstrap fill all newly created resource sites in
     * the same Farmer → Miner → Lumberjack → Toolsmith order before vanilla POI claiming can
     * consume the remaining unemployed residents.
     */
    public static void allocateFoundingWorkforce(MinecraftServer server, List<Villager> founders) {
        allocate(server, founders.stream().map(Villager::getUUID).collect(java.util.stream.Collectors.toSet()));
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
        allocate(server, Set.of());
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
        allocate(server, Set.of());
    }

    private static void allocate(MinecraftServer server, Set<UUID> founders) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager resident : LoadedVillagerCache.loaded(level)) {
                ProductionParcel.get(server).reconcile(level, resident);
                reconcileResourceHome(level, resident, assignments);
            }
            List<WorkZoneRecord> resourceZones = assignments.zoneSnapshot().values().stream()
                    .filter(zone -> isResourceRole(zone.roleId()))
                    .filter(zone -> zone.zone().dimensionId().equals(level.dimension().identifier().toString()))
                    .toList();
            if (resourceZones.isEmpty()) {
                continue;
            }
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            List<Villager> candidates = villagers.stream()
                    .filter(villager -> isEligibleCandidate(villager, assignments) && VillagerCareerHistory.mayChange(villager))
                    .sorted(Comparator.comparing(villager -> villager.getUUID().toString()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            Set<UUID> staffedZones = staffedResourceZones(assignments.assignmentSnapshot());

            assignFarmers(level, villagers, candidates, resourceZones);
            // An established miner or lumberjack can take a new local zone without losing trades or changing career.
            candidates.addAll(0, villagers.stream().filter(v -> assignments.getAssignment(v.getUUID())
                    .filter(a -> a.automaticallyAssigned() && isResourceRole(a.roleId()) && a.workZoneId().isEmpty()
                            && a.home().isPresent() && a.home().equals(v.getBrain().getMemory(MemoryModuleType.HOME))).isPresent())
                    .sorted(Comparator.comparing(Villager::getUUID)).toList());
            assignSpecialists(level, candidates, resourceZones, staffedZones, TotemVillagerProfessions.MINER_ID, assignments);
            assignSpecialists(level, candidates, resourceZones, staffedZones, TotemVillagerProfessions.LUMBERJACK_ID, assignments);
            if (!founders.isEmpty()) assignToolsmith(level, villagers, candidates.stream()
                    .filter(v -> founders.contains(v.getUUID())).toList(), resourceZones);
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

    /** Successful starter work may establish the first food producer of a household. */
    static void settleFoodProducer(ServerLevel level, Villager worker, BlockPos productionCell, boolean field) {
        if (!VillagerCareerHistory.mayChange(worker)
                || !isEligibleCandidate(worker, WorkerAssignmentSavedData.forServer(level.getServer()))) return;
        var residents = LoadedVillagerCache.loaded(level);
        var home = worker.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
        if (residents.stream().anyMatch(resident -> resident.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(bed -> bed.dimension().equals(home.dimension()) && bed.pos().distSqr(home.pos()) < 24 * 24).isPresent()
                && (isFarmer(resident) || "minecraft:fisherman".equals(professionId(resident)))
                && resident.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE))) return;
        String role = field ? "minecraft:farmer" : "minecraft:fisherman";
        var profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(role));
        var block = field ? Blocks.COMPOSTER : Blocks.BARREL;
        var parcels = ProductionParcel.get(level.getServer());
        for (BlockPos site : BlockPos.withinManhattan(worker.blockPosition(), 6)) {
            if (!level.isLoaded(site) || !level.getBlockState(site).is(block)
                    || !dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, worker, site)
                    || jobSiteAlreadyStaffed(level, site, residents, role)
                    || !field && !dev.totem.villagers.world.FishermanWorkstation.isSupportedJobSite(level, site)) continue;
            if (site.distSqr(worker.blockPosition()) > 4) {
                var path = worker.getNavigation().createPath(site, 1);
                if (path == null || !path.canReach()) continue;
            }
            var ticket = claimExactJobSite(level, profession, site);
            if (ticket.isEmpty()) continue;
            var station = GlobalPos.of(level.dimension(), ticket.get().immutable());
            if (!parcels.reserve(level, worker, station, productionCell, field)) {
                level.getPoiManager().release(ticket.get());
                continue;
            }
            establishVanillaProfession(level, worker, profession, station.pos());
            VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
            return;
        }
    }

    static Optional<BlockPos> claimExactJobSite(
            ServerLevel level, VillagerProfession profession, BlockPos site
    ) {
        return level.getPoiManager().take(profession.heldJobSite(),
                (poi, candidate) -> candidate.equals(site), site, 1).filter(site::equals);
    }

    static void establishVanillaProfession(
            ServerLevel level, Villager villager, VillagerProfession profession, BlockPos jobSite
    ) {
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        villager.setVillagerDataFinalized(true);
        if (villager.getVillagerData().profession().is(VillagerProfession.TOOLSMITH)
                || villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN))
            GuardEquipmentOrders.appointedSpecialist(villager, GlobalPos.of(level.dimension(), jobSite));
        VillagerCareerHistory.appointed(villager);
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
            var prior = assignments.getAssignment(candidate.getUUID());
            boolean relocating = prior.filter(a -> a.automaticallyAssigned() && a.roleId().equals(roleId.toString())
                    && a.workZoneId().isEmpty() && roleId.toString().equals(professionId(candidate))).isPresent();
            if (prior.isPresent() ? !relocating : !isUnemployed(candidate)) continue;
            var home = candidate.getBrain().getMemory(MemoryModuleType.HOME)
                    .filter(h -> h.dimension().equals(level.dimension()) && level.isLoaded(h.pos())
                            && level.getBlockState(h.pos()).is(net.minecraft.tags.BlockTags.BEDS));
            if (relocating && home.isEmpty()) continue;
            var vacant = resourceZones.stream()
                    .filter(zone -> zone.roleId().equals(roleId.toString()) && !staffedZones.contains(zone.id()))
                    .filter(zone -> !zone.zone().personal() || zone.zone().ownerId().equals(candidate.getUUID()))
                    .filter(zone -> distanceToZoneSquared(candidate, zone) <= RESOURCE_ZONE_RECRUIT_RANGE_SQUARED)
                    .filter(zone -> home.isEmpty() || distanceToZoneSquared(net.minecraft.world.phys.Vec3.atCenterOf(home.get().pos()), zone)
                            <= RESOURCE_ZONE_RECRUIT_RANGE_SQUARED)
                    .sorted(Comparator.comparingDouble((WorkZoneRecord zone) -> distanceToZoneSquared(candidate, zone))
                            .thenComparing(WorkZoneRecord::id))
                    .toList();
            for (var zone : vacant) {
                var station = roleId.equals(TotemVillagerProfessions.MINER_ID)
                        ? MinerFurnaceWorkstation.findUnclaimed(level, candidate, zone.zone()) : Optional.<BlockPos>empty();
                if (roleId.equals(TotemVillagerProfessions.MINER_ID) && station.isEmpty()) continue;
                candidate.setVillagerData(candidate.getVillagerData().withProfession(
                        BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
                station.ifPresent(site -> candidate.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                        GlobalPos.of(level.dimension(), site)));
                assignments.putAssignment(new WorkerAssignment(candidate.getUUID(), roleId.toString(),
                        Optional.of(zone.id()), Optional.empty(), true, home));
                if (!relocating) VillagerCareerHistory.appointed(candidate);
                staffedZones.add(zone.id());
                break;
            }
        }
    }

    static void reconcileResourceHome(ServerLevel level, Villager worker, WorkerAssignmentSavedData assignments) {
        var a = assignments.getAssignment(worker.getUUID()).orElse(null);
        if (a == null || !a.automaticallyAssigned() || !isResourceRole(a.roleId())) return;
        var brain = worker.getBrain();
        var rememberedHome = brain.getMemory(MemoryModuleType.HOME);
        if (rememberedHome.filter(h -> h.dimension().equals(level.dimension()) && !level.isLoaded(h.pos())).isPresent()) return;
        var home = rememberedHome.filter(h -> h.dimension().equals(level.dimension()) && level.getBlockState(h.pos()).is(net.minecraft.tags.BlockTags.BEDS));
        var assignedZone = a.workZoneId().flatMap(assignments::getZone);
        boolean outsideHomeRange = rememberedHome.isPresent() && assignedZone.filter(z ->
                !z.zone().dimensionId().equals(rememberedHome.get().dimension().identifier().toString())
                        || distanceToZoneSquared(net.minecraft.world.phys.Vec3.atCenterOf(rememberedHome.get().pos()), z)
                        > RESOURCE_ZONE_RECRUIT_RANGE_SQUARED).isPresent();
        boolean lostForest = assignedZone.map(WorkZoneRecord::zone).filter(z -> z.naturalTimber()
                && z.dimensionId().equals(level.dimension().identifier().toString())).map(z -> {
                    var root = z.minimum();
                    return !dev.totem.villagers.world.NaturalOakTimber.viableForestRoot(level, new BlockPos(root.x(), root.y(), root.z()));
                }).orElse(false);
        boolean exhausted = assignedZone.map(WorkZoneRecord::zone).filter(z -> z.naturalQuarry()
                && z.dimensionId().equals(level.dimension().identifier().toString()))
                .filter(z -> dev.totem.villagers.world.NaturalStoneFaces.exhausted(level,z)).isPresent();
        boolean moved = exhausted || lostForest || outsideHomeRange || a.home().isPresent() && !a.home().equals(home);
        var site = brain.getMemory(MemoryModuleType.JOB_SITE);
        boolean lostFurnace = false;
        if (!moved && a.roleId().equals("totem:miner") && assignedZone.isPresent()) {
            var zone = assignedZone.get().zone();
            if (zone.naturalQuarry()) {
                var furnace = new BlockPos(zone.minimum().x() + 2, zone.minimum().y() + 1, zone.minimum().z() + 2);
                lostFurnace = level.isLoaded(furnace) && !MinerFurnaceWorkstation.privateStationAvailable(level, worker, zone, furnace);
            } else if (site.isPresent()) {
                var station = site.get();
                lostFurnace = station.dimension().equals(level.dimension()) && level.isLoaded(station.pos())
                        && !level.getBlockState(station.pos()).is(Blocks.FURNACE)
                        && MinerFurnaceWorkstation.ensureAssigned(level, worker, zone).isEmpty();
            } else if (zone.dimensionId().equals(level.dimension().identifier().toString())
                    && distanceToZoneSquared(worker, assignedZone.get()) <= RESOURCE_ZONE_RECRUIT_RANGE_SQUARED
                    && MinerFurnaceWorkstation.ensureAssigned(level, worker, zone).isEmpty()) {
                // Verify absence even if vanilla has already cleared its job-site memory.
                var low = zone.minimum();
                var high = zone.maximum();
                long dx = (long) high.x() - low.x() + 1, dy = (long) high.y() - low.y() + 1, dz = (long) high.z() - low.z() + 1;
                if (dx <= 4096 && dy <= 4096 && dz <= 4096 && dx * dy * dz <= 4096) {
                    lostFurnace = BlockPos.betweenClosedStream(new BlockPos(low.x(), low.y(), low.z()), new BlockPos(high.x(), high.y(), high.z()))
                            .allMatch(p -> level.isLoaded(p) && !level.getBlockState(p).is(Blocks.FURNACE));
                }
            }
        }
        if (!moved && !lostFurnace && a.home().equals(home)) return;
        var zone = moved || lostFurnace ? Optional.<UUID>empty() : a.workZoneId();
        assignments.putAssignment(new WorkerAssignment(a.villagerId(), a.roleId(), zone, a.managedVillageId(), true, home));
        if ((moved || lostFurnace) && a.roleId().equals("totem:miner")) brain.eraseMemory(MemoryModuleType.JOB_SITE);
    }

    static boolean isEligibleCandidate(Villager villager, WorkerAssignmentSavedData assignments) {
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
        return distanceToZoneSquared(villager.position(), zone);
    }

    private static double distanceToZoneSquared(net.minecraft.world.phys.Vec3 position, WorkZoneRecord zone) {
        double x = clamp(position.x, zone.zone().minimum().x(), zone.zone().maximum().x());
        double y = clamp(position.y, zone.zone().minimum().y(), zone.zone().maximum().y());
        double z = clamp(position.z, zone.zone().minimum().z(), zone.zone().maximum().z());
        return position.distanceToSqr(x, y, z);
    }

    private static double clamp(double value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isResourceRole(String roleId) {
        return TotemVillagerProfessions.MINER_ID.toString().equals(roleId)
                || TotemVillagerProfessions.LUMBERJACK_ID.toString().equals(roleId);
    }
}
