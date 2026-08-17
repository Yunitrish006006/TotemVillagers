package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.world.ShepherdWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Existing vanilla Shepherd profession performs bounded flock shearing near its native job site. */
public final class VillagerShepherdWorkRuntime {
    private static final double FLOCK_RADIUS_SQUARED = 32.0D * 32.0D;
    private static final double WORK_REACH_SQUARED = 16.0D;
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final ShepherdWorldWorkAction ACTION = new ShepherdWorldWorkAction();

    private VillagerShepherdWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerShepherdWorkRuntime::tick);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    /** Runs discovery immediately for deterministic GameTests. */
    public static void tickForGameTest(MinecraftServer server) {
        run(server, true);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        if (catalog.snapshot().values().stream().noneMatch(order -> "minecraft:shepherd".equals(order.professionId())
                && order.allowedSources().contains(WorkSource.WORLD))) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if ("minecraft:shepherd".equals(professionId(villager))) {
                    tickShepherd(level, villager, catalog, states, forceIdleScan);
                }
            }
        }
    }

    private static void tickShepherd(ServerLevel level, Villager shepherd, WorkOrderCatalog catalog,
                                     VillagerWorkSavedData states, boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(shepherd.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(shepherd)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            shepherd.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, shepherd)) {
            return;
        }
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer())
                .inventory(shepherd.getUUID());
        Optional<ShepherdContext> context = resolveContext(level, shepherd);
        List<WorkCandidate> candidates = context.map(value -> candidates(
                level, shepherd, state, value, catalog, inventory)).orElseGet(List::of);
        Optional<Sheep> activeSheep = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::entityId).map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast);
        boolean atTarget = activeSheep.map(sheep -> shepherd.distanceToSqr(sheep) <= WORK_REACH_SQUARED).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(shepherd.getUUID(), "minecraft:shepherd", level.getGameTime(),
                shepherd.isAlive(), level.isLoaded(shepherd.blockPosition()), inDanger(shepherd), shepherd.isSleeping(),
                level.isRaided(shepherd.blockPosition()), context.isPresent(), atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, shepherd, scheduled.state(), scheduled.readyToCommit().orElseThrow(), context.orElse(null),
                    inventory);
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            shepherd.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId).isPresent()
                && !atTarget && !inDanger(shepherd)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, shepherd)) {
            Sheep sheep = next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId)
                    .map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast).orElse(null);
            if (sheep != null) {
                shepherd.getNavigation().moveTo(sheep, .5D);
            }
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    private static List<WorkCandidate> candidates(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                                   ShepherdContext context, WorkOrderCatalog catalog,
                                                   VillagerWorkInventory inventory) {
        return catalog.snapshot().values().stream()
                .filter(order -> "minecraft:shepherd".equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(level, shepherd, inventory, order))
                .map(order -> candidateFor(level, shepherd, state, context, order))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                                         ShepherdContext context, WorkOrder order) {
        if (!"minecraft:sheep".equals(order.worldTargetEntityType())) {
            return Optional.empty();
        }
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<Sheep> sheep = current.flatMap(WorldWorkTarget::entityId).map(level::getEntity)
                .filter(Sheep.class::isInstance).map(Sheep.class::cast)
                .filter(target -> validTarget(level, shepherd, context, target, order, false));
        if (sheep.isEmpty() && current.isEmpty()) {
            AABB flock = new AABB(context.jobSite().pos()).inflate(32.0D);
            sheep = level.getEntitiesOfClass(Sheep.class, flock,
                            target -> validTarget(level, shepherd, context, target, order, true))
                    .stream().limit(64).min(Comparator.comparingDouble(shepherd::distanceToSqr));
        }
        return sheep.map(target -> new WorkCandidate(order.id(), WorkSource.WORLD, 10,
                Optional.of(WorldWorkTarget.entity(level.dimension().identifier().toString(), target.getUUID()))));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                            WorkOrder order, ShepherdContext context, VillagerWorkInventory inventory) {
        if (context == null) return failed(state, order, "job site changed");
        ItemStack produced = new ItemStack(Blocks.WOOL.pick(
                ShepherdWorldWorkAction.outputColour(order).orElseThrow()).asItem(), order.output().count());
        if (!inventory.canInsertExact(produced)) return failed(state, order, "personal work inventory is full");
        Sheep sheep = state.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId)
                .map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast).orElse(null);
        if (sheep == null || !validTarget(level, shepherd, context, sheep, order, false)
                || !ACTION.complete(level, shepherd, sheep, order)) {
            return failed(state, order, "flock target rejected");
        }
        if (!inventory.insertExact(produced)) {
            throw new IllegalStateException("Shepherd inventory changed during same-tick wool insertion");
        }
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    private static Optional<ShepherdContext> resolveContext(ServerLevel level, Villager shepherd) {
        GlobalPos jobSite = shepherd.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || !jobSite.dimension().equals(level.dimension())
                || !level.isLoaded(jobSite.pos())) return Optional.empty();
        return Optional.of(new ShepherdContext(jobSite));
    }

    private static boolean validTarget(ServerLevel level, Villager shepherd, ShepherdContext context, Sheep sheep,
                                       WorkOrder order, boolean requirePath) {
        var outputColour = ShepherdWorldWorkAction.outputColour(order).orElse(null);
        return outputColour != null && sheep.isAlive() && sheep.getColor() == outputColour && sheep.readyForShearing()
                && level.isLoaded(sheep.blockPosition()) && sheep.distanceToSqr(Vec3.atCenterOf(context.jobSite().pos())) <= FLOCK_RADIUS_SQUARED
                && WorldWorkPermissions.mayWork(level, shepherd, sheep.blockPosition())
                && (!requirePath || shepherd.getNavigation().createPath(sheep, 0) != null)
                && "minecraft:sheep".equals(order.worldTargetEntityType());
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

    private record ShepherdContext(GlobalPos jobSite) {
    }
}
