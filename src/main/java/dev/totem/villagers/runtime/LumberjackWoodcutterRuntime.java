package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.woodcutter.LumberjackWoodcutterAction;
import dev.totem.villagers.woodcutter.WoodcutterRecipes;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lets a Lumberjack turn gathered wood into an actually requested material at
 * a nearby physical Woodcutter. The machine is never an invisible inventory:
 * the worker must navigate to a loaded station, while both input and output
 * remain in the worker's persistent personal inventory.
 */
public final class LumberjackWoodcutterRuntime {
    private static final long PROCESS_INTERVAL_TICKS = 20L;
    private static final int STATION_RADIUS = 16;
    private static final int STATION_VERTICAL_RADIUS = 4;
    private static final int STATION_MAX_CHECKS = 5_000;
    private static final double STATION_REACH_SQUARED = 16.0D;
    private static final double DEMAND_RANGE_SQUARED = 32.0D * 32.0D;
    private static final LumberjackWoodcutterAction ACTION = new LumberjackWoodcutterAction();

    private LumberjackWoodcutterRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(LumberjackWoodcutterRuntime::tick);
    }

    /** Public only for GameTests; production runs the same work once each second. */
    public static void tickForGameTest(MinecraftServer server) {
        process(server);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED
                || server.overworld().getGameTime() % PROCESS_INTERVAL_TICKS != 0L) {
            return;
        }
        process(server);
    }

    private static void process(MinecraftServer server) {
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWorkSavedData workStates = VillagerWorkSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        List<GeneratedVillageState> generatedVillages = GeneratedVillageSavedData.forServer(server).snapshot();
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            for (Villager lumberjack : villagers) {
                if (!isLumberjack(lumberjack) || !VillagerWorkNeeds.canWork(lumberjack)
                        || workStates.getOrCreate(lumberjack.getUUID()).activeWork().isPresent()) {
                    continue;
                }
                VillagerWorkInventory inventory = inventories.inventory(lumberjack.getUUID());
                Optional<GeneratedVillageState> generatedVillage = assignedGeneratedVillage(
                        level, lumberjack, assignments, generatedVillages);
                Optional<WoodcutterRecipes.Match> match = requestedConversion(
                        level, lumberjack, villagers, inventories, inventory, generatedVillage);
                if (match.isEmpty()) {
                    continue;
                }
                Optional<BlockPos> station = generatedVillage
                        .flatMap(GeneratedVillageState::woodcutterPosition)
                        .map(LumberjackWoodcutterRuntime::blockPosition)
                        .filter(candidate -> isReachableStation(level, lumberjack, candidate))
                        .or(() -> findReachableStation(level, lumberjack));
                if (station.isEmpty()) {
                    continue;
                }
                BlockPos target = station.orElseThrow();
                if (lumberjack.distanceToSqr(Vec3.atCenterOf(target)) > STATION_REACH_SQUARED) {
                    lumberjack.getNavigation().moveTo(target.getX() + .5D, target.getY(), target.getZ() + .5D, .5D);
                    continue;
                }
                ACTION.complete(level, lumberjack, target, match.orElseThrow(), inventory);
            }
        }
    }

    private static Optional<WoodcutterRecipes.Match> requestedConversion(
            ServerLevel level,
            Villager lumberjack,
            List<? extends Villager> villagers,
            VillagerWorkInventorySavedData inventories,
            VillagerWorkInventory inventory,
            Optional<GeneratedVillageState> generatedVillage
    ) {
        Set<String> demandedItems = new LinkedHashSet<>();
        villagers.stream()
                .filter(candidate -> candidate != lumberjack)
                .filter(candidate -> generatedVillage
                        .map(village -> isKnownVillageResident(candidate, village))
                        .orElse(true))
                .filter(candidate -> lumberjack.distanceToSqr(candidate) <= DEMAND_RANGE_SQUARED)
                .sorted(java.util.Comparator.<Villager>comparingDouble(candidate -> lumberjack.distanceToSqr(candidate))
                        .thenComparing(candidate -> candidate.getUUID().toString()))
                .forEach(candidate -> VillagerWorkshopRuntime.materialDemandFor(level, candidate)
                        .ifPresent(order -> addMissingMaterials(demandedItems, inventories.inventory(candidate.getUUID()), order.requiredInputs())));
        if (demandedItems.isEmpty()) {
            return Optional.empty();
        }
        for (ItemStack input : inventory.snapshot()) {
            if (!WoodcutterRecipes.acceptsInput(input)) {
                continue;
            }
            Optional<WoodcutterRecipes.Match> match = WoodcutterRecipes.matching(level, input).stream()
                    .filter(candidate -> demandedItems.contains(itemId(candidate.output())))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private static void addMissingMaterials(Set<String> results, VillagerWorkInventory inventory, List<ItemAmount> required) {
        for (ItemAmount material : required) {
            int available = inventory.snapshot().stream()
                    .filter(stack -> !stack.isEmpty() && itemId(stack).equals(material.itemId()))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (available < material.count()) {
                results.add(material.itemId());
            }
        }
    }

    private static Optional<BlockPos> findReachableStation(ServerLevel level, Villager lumberjack) {
        BlockPos origin = lumberjack.blockPosition();
        int checks = 0;
        for (int radius = 0; radius <= STATION_RADIUS && checks < STATION_MAX_CHECKS; radius++) {
            for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < STATION_MAX_CHECKS; x++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < STATION_MAX_CHECKS; z++) {
                    if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                            && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                        continue;
                    }
                    for (int y = origin.getY() - STATION_VERTICAL_RADIUS;
                         y <= origin.getY() + STATION_VERTICAL_RADIUS && checks < STATION_MAX_CHECKS; y++) {
                        BlockPos station = new BlockPos(x, y, z);
                        checks++;
                        if (isReachableStation(level, lumberjack, station)) {
                            return Optional.of(station);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Resolves the persisted village identity through the worker's assigned generated Lumberyard. */
    private static Optional<GeneratedVillageState> assignedGeneratedVillage(
            ServerLevel level,
            Villager lumberjack,
            WorkerAssignmentSavedData assignments,
            List<GeneratedVillageState> villages
    ) {
        return assignments.getAssignment(lumberjack.getUUID())
                .flatMap(assignment -> assignment.workZoneId())
                .flatMap(zoneId -> villages.stream()
                        .filter(village -> village.dimensionId().equals(level.dimension().identifier().toString()))
                        .filter(village -> village.lumberjackZoneId().filter(zoneId::equals).isPresent())
                        .findFirst());
    }

    /**
     * Modern generated villages keep a persistent resident UUID ledger, so a
     * wandering resident still belongs to its own economy. Legacy records
     * without that ledger fall back to the exact structure bounds. This stops
     * two nearby villages (and parallel GameTests) from steering one another's
     * Woodcutter output merely because they happen to be within 32 blocks.
     */
    private static boolean isKnownVillageResident(Villager candidate, GeneratedVillageState village) {
        Optional<List<java.util.UUID>> residents = village.endowedResidents()
                .filter(ledger -> !ledger.isEmpty());
        if (residents.isPresent()) {
            return residents.orElseThrow().contains(candidate.getUUID());
        }
        BlockPos position = candidate.blockPosition();
        return position.getX() >= village.minimum().x() && position.getX() <= village.maximum().x()
                && position.getY() >= village.minimum().y() && position.getY() <= village.maximum().y()
                && position.getZ() >= village.minimum().z() && position.getZ() <= village.maximum().z();
    }

    private static boolean isReachableStation(ServerLevel level, Villager lumberjack, BlockPos station) {
        return level.isLoaded(station)
                && level.getBlockState(station).is(TotemVillagerBlocks.WOODCUTTER)
                && (lumberjack.distanceToSqr(Vec3.atCenterOf(station)) <= STATION_REACH_SQUARED
                    || lumberjack.getNavigation().createPath(station, 0) != null);
    }

    private static boolean isLumberjack(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "totem:lumberjack".equals(id.toString());
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static BlockPos blockPosition(BlockCoordinate coordinate) {
        return new BlockPos(coordinate.x(), coordinate.y(), coordinate.z());
    }
}
