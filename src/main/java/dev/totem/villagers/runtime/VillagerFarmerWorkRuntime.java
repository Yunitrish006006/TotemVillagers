package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded Farmer field work. Mature wheat within the native Composter's local
 * field is placed in the same personal work inventory used by recipe-backed
 * bread job; harvesting itself never creates merchant stock.
 */
public final class VillagerFarmerWorkRuntime {
    private static final int FARM_RADIUS = 24;
    private static final int MAX_CROP_CHECKS = 1_024;
    private static final int[] FARM_Y_OFFSETS = {0, -1, 1, -2, 2, -3, -4};
    private static final double FARM_REACH_SQUARED = 16.0D;
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final FarmerWorldWorkAction ACTION = new FarmerWorldWorkAction();

    private VillagerFarmerWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerFarmerWorkRuntime::tick);
    }

    /** Public only for deterministic GameTests of selection, navigation and commit. */
    public static void tickForGameTest(MinecraftServer server) {
        run(server, true);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        if (catalog.snapshot().values().stream().noneMatch(ACTION::supports)) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager farmer : LoadedVillagerCache.loaded(level)) {
                if ("minecraft:farmer".equals(professionId(farmer))) {
                    tickFarmer(level, farmer, catalog, states, inventories, forceIdleScan);
                }
            }
        }
    }

    private static void tickFarmer(ServerLevel level, Villager farmer, WorkOrderCatalog catalog,
                                   VillagerWorkSavedData states, VillagerWorkInventorySavedData inventories,
                                   boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(farmer.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(farmer)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            farmer.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, farmer)) {
            return;
        }
        Optional<FarmerContext> context = resolveContext(level, farmer, inventories);
        List<WorkCandidate> candidates = context.map(value -> candidates(level, farmer, state, value, catalog)).orElseGet(List::of);
        Optional<BlockPos> activeCrop = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of);
        boolean atTarget = activeCrop.map(crop -> farmer.distanceToSqr(Vec3.atCenterOf(crop)) <= FARM_REACH_SQUARED).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(farmer.getUUID(), "minecraft:farmer", level.getGameTime(),
                farmer.isAlive(), level.isLoaded(farmer.blockPosition()), inDanger(farmer), farmer.isSleeping(),
                level.isRaided(farmer.blockPosition()), context.isPresent(), atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, farmer, scheduled.state(), scheduled.readyToCommit().orElseThrow(), context.orElse(null));
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            farmer.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition).isPresent()
                && !atTarget && !inDanger(farmer)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, farmer)) {
            BlockPos crop = BlockPos.of(next.activeWork().orElseThrow().worldTarget().orElseThrow()
                    .packedBlockPosition().orElseThrow());
            farmer.getNavigation().moveTo(crop.getX() + .5D, crop.getY(), crop.getZ() + .5D, .5D);
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    private static List<WorkCandidate> candidates(ServerLevel level, Villager farmer, VillagerWorkState state,
                                                   FarmerContext context, WorkOrderCatalog catalog) {
        if (!ACTION.hasUsableHoe(context.inventory())) {
            return List.of();
        }
        List<WorkOrder> orders = catalog.snapshot().values().stream()
                .filter(ACTION::supports)
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(
                        level, farmer, context.inventory(), order))
                .sorted(java.util.Comparator.comparing(WorkOrder::id))
                .toList();
        if (orders.isEmpty()) {
            return List.of();
        }
        if (state.activeWork().isPresent()) {
            String activeOrderId = state.activeWork().orElseThrow().orderId();
            return orders.stream()
                    .filter(order -> order.id().equals(activeOrderId))
                    .map(order -> candidateFor(level, farmer, state, context, order))
                    .flatMap(Optional::stream)
                    .toList();
        }
        Map<String, WorkOrder> ordersById = new LinkedHashMap<>();
        for (WorkOrder order : orders) {
            ordersById.put(order.id(), order);
        }
        return findCrop(level, farmer, context, ordersById)
                .map(found -> List.of(candidate(level, found.order(), found.position())))
                .orElseGet(List::of);
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager farmer, VillagerWorkState state,
                                                         FarmerContext context, WorkOrder order) {
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<BlockPos> crop = current.flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(target -> validCropTarget(level, farmer, context, target, order, false));
        return crop.map(target -> candidate(level, order, target));
    }

    private static WorkCandidate candidate(ServerLevel level, WorkOrder order, BlockPos target) {
        return new WorkCandidate(order.id(), WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), target.asLong())));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager farmer, VillagerWorkState state,
                                            WorkOrder order, FarmerContext context) {
        BlockPos crop = state.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition)
                .map(BlockPos::of).orElse(null);
        if (context == null || crop == null || !validCropTarget(level, farmer, context, crop, order, false)
                || !ACTION.complete(level, farmer, crop, order, context.inventory())) {
            return failed(state, order, "crop harvest rejected");
        }
        // A successful harvest changes the server-owned work inventory. Push
        // the updated snapshot immediately to a player who is already looking
        // at this Farmer, rather than making the backpack wait for its normal
        // periodic merchant-screen refresh.
        TradeSnapshotRuntime.refreshOpenTrade(farmer);
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    private static Optional<FarmerContext> resolveContext(ServerLevel level, Villager farmer,
                                                           VillagerWorkInventorySavedData inventories) {
        GlobalPos jobSite = farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || !jobSite.dimension().equals(level.dimension())
                || !level.isLoaded(jobSite.pos()) || !level.getBlockState(jobSite.pos()).is(Blocks.COMPOSTER)) {
            return Optional.empty();
        }
        return Optional.of(new FarmerContext(jobSite, inventories.inventory(farmer.getUUID())));
    }

    private static boolean validCropTarget(ServerLevel level, Villager farmer, FarmerContext context,
                                           BlockPos crop, WorkOrder order, boolean requirePath) {
        return ACTION.supports(order)
                && ACTION.hasUsableHoe(context.inventory())
                && ACTION.isMatureCrop(level, crop, order)
                && crop.distSqr(context.jobSite().pos()) <= (double) FARM_RADIUS * FARM_RADIUS
                && WorldWorkPermissions.mayWork(level, farmer, crop)
                && (!requirePath || farmer.getNavigation().createPath(crop, 0) != null)
                && ACTION.primaryHarvest(order).filter(context.inventory()::canInsertExact).isPresent();
    }

    private static Optional<LocatedCrop> findCrop(ServerLevel level, Villager farmer, FarmerContext context,
                                                  Map<String, WorkOrder> ordersById) {
        int checks = 0;
        BlockPos jobSite = context.jobSite().pos();
        // Search the Composter's own elevation across the complete field
        // before spending the bounded budget on less likely vertical layers.
        // Each position is checked once for all supported crops, avoiding four
        // identical field scans when no mature crop exists.
        for (int yOffset : FARM_Y_OFFSETS) {
            int y = jobSite.getY() + yOffset;
            for (int radius = 1; radius <= FARM_RADIUS && checks < MAX_CROP_CHECKS; radius++) {
                for (int x = jobSite.getX() - radius; x <= jobSite.getX() + radius && checks < MAX_CROP_CHECKS; x++) {
                    for (int z = jobSite.getZ() - radius; z <= jobSite.getZ() + radius && checks < MAX_CROP_CHECKS; z++) {
                        if (x != jobSite.getX() - radius && x != jobSite.getX() + radius
                                && z != jobSite.getZ() - radius && z != jobSite.getZ() + radius) {
                            continue;
                        }
                        BlockPos crop = new BlockPos(x, y, z);
                        checks++;
                        String orderId = ACTION.matureCropOrderId(level, crop).orElse(null);
                        WorkOrder order = orderId == null ? null : ordersById.get(orderId);
                        if (order != null && validCropTarget(level, farmer, context, crop, order, true)) {
                            return Optional.of(new LocatedCrop(order, crop));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static VillagerWorkState failed(VillagerWorkState state, WorkOrder order, String reason) {
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(order.id(), WorkSource.WORLD,
                order.workTicks(), reason)));
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

    private record FarmerContext(GlobalPos jobSite, VillagerWorkInventory inventory) {
    }

    private record LocatedCrop(WorkOrder order, BlockPos position) {
    }
}
