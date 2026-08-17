package dev.totem.villagers.runtime;

import dev.totem.villagers.builder.BuilderSite;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

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
        ServerTickEvents.END_SERVER_TICK.register(VillagerBuilderRuntime::tick);
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
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (!"totem:builder".equals(professionId(villager))) {
                    continue;
                }
                sites.getByBuilder(villager.getUUID()).ifPresent(site -> tickBuilder(server, level, villager, site,
                        sites, workStates, assignments, inventories));
            }
        }
    }

    private static void tickBuilder(MinecraftServer server, ServerLevel level, Villager builder, BuilderSite site,
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
        BuilderSite currentSite = site;
        for (int scanned = 0; scanned < MAX_ALREADY_BUILT_STEPS_PER_TICK; scanned++) {
            int index = currentSite.nextBlockIndex();
            if (index >= blueprint.blocks().size()) {
                builder.getNavigation().stop();
                return;
            }
            VanillaVillageBlueprints.BlueprintBlock planned = blueprint.blocks().get(index);
            BlockPos target = planned.position();
            if (!level.isLoaded(target)
                    || !zone.zone().contains(dimensionId, new BlockCoordinate(target.getX(), target.getY(), target.getZ()))
                    || !WorldWorkPermissions.mayWork(level, builder, target)) {
                return;
            }
            if (level.getBlockState(target).equals(planned.state())) {
                currentSite = currentSite.withNextBlockIndex(index + 1);
                sites.updateProgress(currentSite);
                continue;
            }
            if (!level.getBlockState(target).isAir()) {
                return;
            }
            BlockPos standingPosition = findStandingPosition(level, builder, target).orElse(null);
            if (standingPosition == null) {
                return;
            }
            if (builder.distanceToSqr(Vec3.atCenterOf(standingPosition)) > BUILD_REACH_SQUARED) {
                builder.getNavigation().moveTo(standingPosition.getX() + .5D, standingPosition.getY(), standingPosition.getZ() + .5D, .5D);
                return;
            }
            if (!planned.consumesMaterial()) {
                if (level.setBlock(target, planned.state(), 3)) {
                    sites.updateProgress(currentSite.withNextBlockIndex(index + 1));
                    builder.swing(InteractionHand.MAIN_HAND);
                    builder.playWorkSound();
                }
                return;
            }
            dev.totem.villagers.inventory.WorkInventory.Reservation reservation = inventory
                    .reserveExact(List.of(new ItemAmount(planned.materialItemId(), 1))).orElse(null);
            if (reservation == null) {
                return;
            }
            boolean placed = false;
            try {
                if (!level.isLoaded(target) || !level.getBlockState(target).isAir()
                        || !WorldWorkPermissions.mayWork(level, builder, target)
                        || !(placed = level.setBlock(target, planned.state(), 3))) {
                    reservation.rollback();
                    return;
                }
                reservation.commit();
                sites.updateProgress(currentSite.withNextBlockIndex(index + 1));
                builder.swing(InteractionHand.MAIN_HAND);
                builder.playWorkSound();
            } catch (RuntimeException failure) {
                // Never restore materials after setBlock succeeded: doing so would mint a free block.
                if (!placed) {
                    reservation.rollback();
                }
            }
            return;
        }
    }

    private static java.util.Optional<BlockPos> findStandingPosition(ServerLevel level, Villager builder, BlockPos target) {
        return BlockPos.betweenClosedStream(target.offset(-2, -1, -2), target.offset(2, 1, 2))
                .filter(position -> !position.equals(target))
                .filter(position -> level.isLoaded(position) && level.isLoaded(position.above()) && level.isLoaded(position.below()))
                .filter(position -> level.getBlockState(position).isAir() && level.getBlockState(position.above()).isAir())
                .filter(position -> level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP))
                .filter(position -> builder.getNavigation().createPath(position, 0) != null)
                .min(Comparator.comparingDouble(position -> builder.distanceToSqr(Vec3.atCenterOf(position))));
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
