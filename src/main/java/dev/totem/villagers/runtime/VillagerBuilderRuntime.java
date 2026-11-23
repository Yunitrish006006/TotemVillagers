package dev.totem.villagers.runtime;

import dev.totem.villagers.builder.BuilderSite;
import dev.totem.villagers.builder.BuilderProject;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.builder.BuilderSiteSavedData;
import dev.totem.villagers.builder.VanillaVillageBlueprints;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Comparator;
import java.util.List;

/**
 * Executes one material-backed vanilla village-house block at a time. It never
 * force-loads chunks, clears a non-air block, copies template NBT, or spawns
 * template entities.
 */
public final class VillagerBuilderRuntime {
    private static final int BUILD_INTERVAL_TICKS = 10;
    private static final int MAX_ALREADY_BUILT_STEPS_PER_TICK = 32;
    private static final double BUILD_REACH_SQUARED = 16.0D;

    private VillagerBuilderRuntime() {
    }

    public static void register() {
        BuilderMaterialOrders.register();
        ServerTickEvents.END_SERVER_TICK.register(VillagerBuilderRuntime::tick);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!(entity instanceof Villager worker) || entity.getRemovalReason() == null
                    || !entity.getRemovalReason().shouldDestroy()) return;
            var sites = BuilderSiteSavedData.forServer(level.getServer());
            sites.getByBuilder(worker.getUUID()).ifPresent(site -> sites.removeByBuilder(worker.getUUID(), site.ownerId()));
            var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
            assignments.getAssignment(worker.getUUID()).filter(a -> a.roleId().equals("totem:builder"))
                    .ifPresent(a -> assignments.removeAssignment(worker.getUUID()));
        });
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % BUILD_INTERVAL_TICKS != 0
                || WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        BuilderSiteSavedData sites = BuilderSiteSavedData.forServer(server);
        VillagerWorkSavedData workStates = VillagerWorkSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            if (server.getTickCount() % 100 == 0) {
                for (var project : sites.projects()) {
                    if (project.dimension().equals(level.dimension().identifier().toString())) {
                        workProject(level, project, sites, assignments, inventories);
                    }
                }
            }
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (!"totem:builder".equals(professionId(villager))) {
                    continue;
                }
                sites.getByBuilder(villager.getUUID()).ifPresent(site -> tickBuilder(server, level, villager, site,
                        sites, workStates, assignments, inventories));
            }
        }
    }

    static void tickBuilder(MinecraftServer server, ServerLevel level, Villager builder, BuilderSite site,
                                    BuilderSiteSavedData sites, VillagerWorkSavedData workStates, WorkerAssignmentSavedData assignments,
                                    VillagerWorkInventorySavedData inventories) {
        if (!VillagerWorkNeeds.canWork(builder)) {
            VillagerWorkState state = workStates.getOrCreate(builder.getUUID());
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                workStates.put(paused);
            }
            builder.getNavigation().stop();
            return;
        }
        String dimensionId = level.dimension().identifier().toString();
        if (!site.dimensionId().equals(dimensionId) || inDanger(builder) || builder.isSleeping()
                || level.isRaided(builder.blockPosition())) {
            return;
        }
        WorkZoneRecord zone = assignments.getAssignment(builder.getUUID())
                .filter(assignment -> "totem:builder".equals(assignment.roleId()))
                .flatMap(assignment -> assignment.workZoneId().flatMap(assignments::getZone))
                .filter(record -> "totem:builder".equals(record.roleId()))
                .filter(record -> record.zone().ownerId().equals(site.ownerId()))
                .filter(record -> record.zone().dimensionId().equals(dimensionId))
                .orElse(null);
        if (zone == null) {
            return;
        }
        VillagerWorkInventory inventory = inventories.inventory(builder.getUUID());
        Identifier templateId = Identifier.tryParse(site.templateId());
        if (templateId == null) {
            return;
        }
        var blueprint = VanillaVillageBlueprints.resolve(server, templateId, BlockPos.of(site.anchorPosition())).orElse(null);
        if (blueprint == null) {
            return;
        }
        blueprint = site.inConstructionOrder(blueprint);
        BuilderSite currentSite = site;
        for (int scanned = 0; scanned < MAX_ALREADY_BUILT_STEPS_PER_TICK; scanned++) {
            int index = currentSite.nextBlockIndex();
            if (index >= blueprint.blocks().size()) return;
            VanillaVillageBlueprints.BlueprintBlock planned = blueprint.blocks().get(index);
            var partner = partner(blueprint, planned);
            if (!planned.consumesMaterial() && partner != null && level.isLoaded(partner.position())
                    && level.getBlockState(partner.position()).isAir()) {
                var unpaidRoot = partner;
                partner = planned;
                planned = unpaidRoot;
            }
            BlockPos target = planned.position();
            if (!level.isLoaded(target)
                    || !zone.zone().contains(dimensionId, new BlockCoordinate(target.getX(), target.getY(), target.getZ()))
                    || !WorldWorkPermissions.mayWork(level, builder, target)) {
                return;
            }
            if (planned.installed(level.getBlockState(target))) {
                if (index + 1 == blueprint.blocks().size()) {
                    inspectFinishedHouse(level, currentSite, blueprint, sites);
                    return;
                }
                currentSite = currentSite.withNextBlockIndex(index + 1);
                sites.updateProgress(currentSite);
                continue;
            }
            if (!level.getBlockState(target).isAir()) {
                return;
            }
            boolean missing = planned.consumesMaterial() && !inventory.canReserveExact(List.of(new ItemAmount(planned.materialItemId(), 1)));
            // Searching a new construction path also changes the navigation target. Leave an in-flight raw supply trip alone between market pulses.
            if (missing && BuilderMaterialOrders.rawMaterial(planned) && !BuilderMaterialOrders.shopping(builder, currentSite) && level.getGameTime() % 100 >= 10) return;
            // Step down the unfinished wall before closing a stairwell cell above it.
            int maxFootY = currentSite.finishingCourse().contains(target.asLong())
                    ? Math.max(BlockPos.of(currentSite.anchorPosition()).getY(), target.getY() - 2) : Integer.MAX_VALUE;
            BlockPos standingPosition = missing && BuilderMaterialOrders.shopping(builder, currentSite) ? builder.blockPosition()
                    : findStandingPosition(level, builder, target, partner == null ? null : partner.position(), maxFootY).orElse(null);
            if (standingPosition == null) {
                return;
            }
            if (missing) {
                int lot = 1;
                if (planned.state().is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) {
                    for (int i = index + 1; i < Math.min(index + 32, blueprint.blocks().size()) && lot < 8; i++) {
                        var next = blueprint.blocks().get(i);
                        var pos = next.position();
                        if (next.state().equals(planned.state()) && level.isLoaded(pos) && level.getBlockState(pos).isAir()
                                && zone.zone().contains(dimensionId, new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))
                                && WorldWorkPermissions.mayWork(level, builder, pos)) lot++;
                    }
                }
                BuilderMaterialOrders.request(level, builder, currentSite, planned, inventory, level.getGameTime(), lot);
                return;
            }
            if (builder.blockPosition().getY() > maxFootY || !canPlaceFrom(level, builder, builder.getEyePosition(), target, partner == null ? null : partner.position())) {
                walk(builder, builder.getNavigation().createPath(standingPosition, 0));
                return;
            }
            if (!planned.consumesMaterial()) {
                if (level.isUnobstructed(planned.state(), target, net.minecraft.world.phys.shapes.CollisionContext.empty())
                        && level.setBlock(target, planned.state(), 3)) {
                    if (index + 1 < blueprint.blocks().size()) sites.updateProgress(currentSite.withNextBlockIndex(index + 1));
                    builder.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    builder.playWorkSound();
                }
                return;
            }
            if (placePaidBlock(level, builder, planned, inventory, blueprint, zone) && index + 1 < blueprint.blocks().size()) {
                sites.updateProgress(currentSite.withNextBlockIndex(index + 1));
            }
            return;
        }
    }

    private static void inspectFinishedHouse(ServerLevel level, BuilderSite site,
                                             VanillaVillageBlueprints.Blueprint blueprint, BuilderSiteSavedData sites) {
        // Check the final projection in one server turn. An edit between two tick batches must not escape inspection.
        for (int i = 0; i < blueprint.blocks().size(); i++) {
            var block = blueprint.blocks().get(i);
            if (!level.isLoaded(block.position())) return;
            if (!block.installed(level.getBlockState(block.position()))) {
                sites.updateProgress(site.withNextBlockIndex(i));
                return;
            }
        }
        sites.updateProgress(site.withNextBlockIndex(blueprint.blocks().size()));
    }

    private static boolean placePaidBlock(ServerLevel level, Villager worker,
                                         VanillaVillageBlueprints.BlueprintBlock block, VillagerWorkInventory inventory,
                                         VanillaVillageBlueprints.Blueprint blueprint, WorkZoneRecord zone) {
        BlockPos target = block.position();
        if (!block.consumesMaterial() || !level.isLoaded(target) || !level.getBlockState(target).isAir()
                || !WorldWorkPermissions.mayWork(level, worker, target)
                || !level.isUnobstructed(block.state(), target, net.minecraft.world.phys.shapes.CollisionContext.empty())) return false;
        var partner = partner(blueprint, block);
        if (paired(block) && partner == null) return false;
        if (partner != null) {
            var pos = partner.position();
            if (!level.isLoaded(pos) || !zone.zone().contains(zone.zone().dimensionId(), new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))
                    || !WorldWorkPermissions.mayWork(level, worker, pos)
                    || !(level.getBlockState(pos).equals(partner.state()) || level.getBlockState(pos).isAir())
                    || !level.isUnobstructed(partner.state(), pos, CollisionContext.empty())) return false;
        }
        var reservation = inventory.reserveExact(List.of(new ItemAmount(block.materialItemId(), 1))).orElse(null);
        if (reservation == null) return false;
        boolean placed = false;
        try {
            if (partner == null || level.getBlockState(partner.position()).equals(partner.state())) {
                placed = level.setBlock(target, block.state(), Block.UPDATE_ALL);
            } else {
                int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
                if (!level.setBlock(target, block.state(), flags)) return false;
                placed = level.setBlock(partner.position(), partner.state(), flags);
                if (!placed) level.setBlock(target, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                else for (var part : List.of(block, partner)) {
                    level.updateNeighborsAt(part.position(), part.state().getBlock());
                    part.state().updateNeighbourShapes(level, part.position(), Block.UPDATE_ALL);
                }
            }
            if (!placed) return false;
            reservation.commit();
        } finally {
            if (!placed) reservation.rollback();
        }
        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        worker.playWorkSound();
        return true;
    }

    private static boolean paired(VanillaVillageBlueprints.BlueprintBlock block) {
        return block.state().getBlock() instanceof DoorBlock || block.state().getBlock() instanceof BedBlock;
    }

    private static VanillaVillageBlueprints.BlueprintBlock partner(VanillaVillageBlueprints.Blueprint blueprint,
                                                                    VanillaVillageBlueprints.BlueprintBlock block) {
        var state = block.state();
        BlockPos other;
        if (state.getBlock() instanceof DoorBlock) {
            other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? block.position().above() : block.position().below();
        } else if (state.getBlock() instanceof BedBlock) {
            var direction = state.getValue(BedBlock.FACING);
            other = block.position().relative(state.getValue(BedBlock.PART) == BedPart.FOOT ? direction : direction.getOpposite());
        } else return null;
        return blueprint.blocks().stream().filter(b -> b.position().equals(other) && b.state().is(state.getBlock())
                && b.consumesMaterial() != block.consumesMaterial()).findFirst().orElse(null);
    }

    static void workProject(ServerLevel level, BuilderProject project, BuilderSiteSavedData sites,
                                    WorkerAssignmentSavedData assignments, VillagerWorkInventorySavedData inventories) {
        var zone = assignments.getZone(project.zone()).orElse(null);
        if (zone == null || !zone.roleId().equals("totem:builder") || !zone.zone().ownerId().equals(project.owner())) {
            sites.cancelProject(project.zone(), project.owner());
            return;
        }
        BlockPos anchor = BlockPos.of(project.anchor());
        if (!level.isLoaded(anchor) || !zone.zone().dimensionId().equals(project.dimension())) return;
        if (assignments.assignmentSnapshot().values().stream().anyMatch(a -> a.workZoneId().filter(project.zone()::equals).isPresent())) {
            sites.cancelProject(project.zone(), project.owner());
            return;
        }
        var residents = LoadedVillagerCache.loaded(level).stream().filter(v -> !v.isBaby() && v.isAlive())
                .filter(v -> v.blockPosition().distSqr(anchor) <= 32 * 32)
                .filter(v -> v.getBrain().getMemory(MemoryModuleType.HOME)
                        .filter(home -> home.dimension().equals(level.dimension()) && home.pos().distSqr(anchor) <= 32 * 32).isPresent()).toList();
        if (residents.isEmpty()) return;
        if (project.loadedTicks() < BuilderProject.DEMAND_TICKS) {
            sites.observe(project, 100);
            return;
        }
        if (!VillageFoodForecast.coversNextMeal(level, residents, inventories)) return;
        var blueprint = VanillaVillageBlueprints.resolve(level.getServer(), Identifier.parse(project.template()), anchor).orElse(null);
        if (blueprint == null) return;
        int firstMissing = -1;
        int index = -1;
        for (int i = 0; i < blueprint.blocks().size(); i++) {
            var block = blueprint.blocks().get(i);
            BlockPos pos = block.position();
            if (!level.isLoaded(pos) || !zone.zone().contains(project.dimension(), new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))) return;
            if (block.installed(level.getBlockState(pos))) continue;
            if (firstMissing < 0) firstMissing = i;
            if (!level.getBlockState(pos).isAir()) return;
            if (block.consumesMaterial()) {
                index = i;
                break;
            }
        }
        if (firstMissing == -1) {
            sites.cancelProject(project.zone(), project.owner());
            return;
        }
        // A paid trial can follow a deferred door/bed half. The builder resumes at the first gap.
        if (index < 0) return;
        var planned = blueprint.blocks().get(index);
        for (var worker : residents.stream().sorted(Comparator.comparingDouble(v -> v.blockPosition().distSqr(planned.position()))).toList()) {
            String role = professionId(worker);
            if (!role.equals("totem:miner") && !role.equals("totem:lumberjack")) continue;
            var donor = assignments.getAssignment(worker.getUUID()).orElse(null);
            if (donor == null || !donor.automaticallyAssigned() || !role.equals(donor.roleId())
                    || !servesProject(level, donor, project, assignments) || !VillagerCareerHistory.mayChange(worker) || worker.isSleeping() || inDanger(worker)
                    || level.isRaided(worker.blockPosition()) || !VillagerWorkNeeds.canWork(worker)
                    || sites.getByBuilder(worker.getUUID()).isPresent()) continue;
            var state = VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID()).orElse(null);
            if (state != null && (state.activeWork().isPresent() || !state.merchantStock().isEmpty()
                    || !state.variantMerchantStock().isEmpty())) continue;
            if (residents.stream().noneMatch(v -> v != worker && professionId(v).equals(role) && VillagerWorkNeeds.canWork(v)
                    && assignments.getAssignment(v.getUUID()).filter(a -> a.roleId().equals(role) && !a.workZoneId().equals(donor.workZoneId())
                            && servesProject(level, a, project, assignments))
                            .flatMap(a -> a.workZoneId().flatMap(assignments::getZone))
                            .filter(z -> VillagerWorldWorkRuntime.canKeepSupplying(level, v, z)).isPresent())) continue;
            var inventory = inventories.inventory(worker.getUUID());
            if (!inventory.canReserveExact(List.of(new ItemAmount(planned.materialItemId(), 1)))
                    || !WorldWorkPermissions.mayWork(level, worker, planned.position())) continue;
            var companion = partner(blueprint, planned);
            var otherCell = companion == null ? null : companion.position();
            var stand = findStandingPosition(level, worker, planned.position(), otherCell, Integer.MAX_VALUE).orElse(null);
            if (stand == null) continue;
            if (!canPlaceFrom(level, worker, worker.getEyePosition(), planned.position(), otherCell)) {
                walk(worker, worker.getNavigation().createPath(stand, 0));
                return;
            }
            if (!placePaidBlock(level, worker, planned, inventory, blueprint, zone)) continue;
            var site = new BuilderSite(java.util.UUID.randomUUID(), project.owner(), worker.getUUID(), project.template(),
                    project.dimension(), project.anchor(), firstMissing == index ? index + 1 : firstMissing).planAccess(level, blueprint);
            sites.registerOrReplace(site, project.owner());
            assignments.putAssignment(new WorkerAssignment(worker.getUUID(), "totem:builder",
                    java.util.Optional.of(project.zone()), java.util.Optional.empty(), true, worker.getBrain().getMemory(MemoryModuleType.HOME)));
            var builder = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("totem:builder"));
            worker.setVillagerData(worker.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(builder)));
            worker.setVillagerDataFinalized(true);
            worker.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            worker.refreshBrain(level);
            VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
            VillagerCareerHistory.appointed(worker);
            sites.cancelProject(project.zone(), project.owner());
            return;
        }
    }

    private static boolean servesProject(ServerLevel level, WorkerAssignment donor, BuilderProject project,
                                         WorkerAssignmentSavedData assignments) {
        return servesWorksite(level, donor, project.owner(), project.dimension(), BlockPos.of(project.anchor()), assignments);
    }

    static boolean servesWorksite(ServerLevel level, WorkerAssignment donor, java.util.UUID owner, String dimension,
                                 BlockPos anchor, WorkerAssignmentSavedData assignments) {
        var record = donor.workZoneId().flatMap(assignments::getZone).orElse(null);
        if (record == null || !record.roleId().equals(donor.roleId())) return false;
        var zone = record.zone();
        if (!zone.dimensionId().equals(dimension)) return false;
        var nearest = new BlockPos(Math.clamp(anchor.getX(), zone.minimum().x(), zone.maximum().x()),
                Math.clamp(anchor.getY(), zone.minimum().y(), zone.maximum().y()),
                Math.clamp(anchor.getZ(), zone.minimum().z(), zone.maximum().z()));
        if (nearest.distSqr(anchor) > 48 * 48 || !level.isLoaded(nearest)) return false;
        if (zone.personal()) return zone.ownerId().equals(donor.villagerId()) && donor.automaticallyAssigned()
                && donor.home().filter(h -> h.dimension().equals(level.dimension()) && h.pos().distSqr(anchor) <= 32 * 32).isPresent();
        if (zone.ownerId().equals(owner)) return true;
        return GeneratedVillageSavedData.forServer(level.getServer()).snapshot().stream()
                .filter(v -> v.dimensionId().equals(dimension)
                        && anchor.getX() >= v.minimum().x() && anchor.getX() <= v.maximum().x()
                        && anchor.getZ() >= v.minimum().z() && anchor.getZ() <= v.maximum().z())
                .anyMatch(v -> v.lumberjackZoneId().filter(record.id()::equals).isPresent()
                        || v.minerZoneId().filter(record.id()::equals).isPresent());
    }

    private static java.util.Optional<BlockPos> findStandingPosition(ServerLevel level, Villager builder, BlockPos target, BlockPos otherCell, int maxFootY) {
        if (builder.blockPosition().getY() <= maxFootY && canPlaceFrom(level, builder, builder.getEyePosition(), target, otherCell)) return java.util.Optional.of(builder.blockPosition());
        var stances = BlockPos.betweenClosedStream(target.offset(-3, -4, -3), target.offset(3, 2, 3))
                .filter(position -> position.getY() <= maxFootY && !position.equals(target) && !position.equals(builder.blockPosition()))
                .filter(position -> level.isLoaded(position) && level.isLoaded(position.above()) && level.isLoaded(position.below()))
                .filter(position -> level.getBlockState(position).isAir() && level.getBlockState(position.above()).isAir())
                .filter(position -> level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP))
                .filter(position -> canPlaceFrom(level, builder, Vec3.atBottomCenterOf(position).add(0, builder.getEyeHeight(), 0), target, otherCell))
                .map(BlockPos::immutable).collect(java.util.stream.Collectors.toSet());
        if (stances.isEmpty()) return java.util.Optional.empty();
        var path = builder.getNavigation().createPath(stances, 0);
        return path != null && path.canReach() && stances.contains(path.getTarget())
                ? java.util.Optional.of(path.getTarget()) : java.util.Optional.empty();
    }

    static boolean walk(Villager worker, net.minecraft.world.level.pathfinder.Path path) {
        if (path == null || !path.canReach() || path.getEndNode() == null) return false;
        var feet = worker.blockPosition();
        // Vanilla may start a new path on the foundation supporting the back of the worker's feet.
        // When the next node is immediately below their centre, keep moving toward that landing.
        if (path.getNextNodeIndex() == 0 && path.getNodeCount() > 1
                && path.getNode(1).asBlockPos().equals(feet.below())) path.advance();
        if (!worker.getNavigation().moveTo(path, .55D)) return false;
        var destination = path.getEndNode().asBlockPos();
        var brain = worker.getBrain();
        // Door interaction reads PATH from the brain. Let ordinary movement resume after this short work route expires.
        brain.setMemoryWithExpiry(MemoryModuleType.PATH, worker.getNavigation().getPath(), 15);
        if (brain.getMemory(MemoryModuleType.WALK_TARGET)
                .filter(w -> w.getTarget().currentBlockPosition().equals(destination)).isEmpty()) {
            brain.setMemory(MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(destination, .55F, 0));
        }
        return true;
    }

    private static boolean canPlaceFrom(ServerLevel level, Villager worker, Vec3 eyes, BlockPos target, BlockPos otherCell) {
        var body = worker.getBoundingBox().move(eyes.subtract(worker.getEyePosition()));
        if (body.intersects(new net.minecraft.world.phys.AABB(target)) || otherCell != null && body.intersects(new net.minecraft.world.phys.AABB(otherCell)))
            return false;
        var center = Vec3.atCenterOf(target);
        if (visiblePoint(level, worker, eyes, center)) return true;
        for (var face : net.minecraft.core.Direction.values()) {
            var point = center.add(face.getStepX() * .49, face.getStepY() * .49, face.getStepZ() * .49);
            if (visiblePoint(level, worker, eyes, point)) return true;
        }
        return false;
    }

    private static boolean visiblePoint(ServerLevel level, Villager worker, Vec3 eyes, Vec3 point) {
        return eyes.distanceToSqr(point) <= BUILD_REACH_SQUARED
                && level.clip(new net.minecraft.world.level.ClipContext(eyes, point,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker))
                .getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static boolean inDanger(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET);
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }
}
