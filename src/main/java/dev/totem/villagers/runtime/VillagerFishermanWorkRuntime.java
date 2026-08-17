package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.inventory.WorkInventory;
import dev.totem.villagers.world.FishermanWorldWorkAction;
import dev.totem.villagers.world.FishermanWorkstation;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bounded autonomous fishing for the existing vanilla Fisherman profession.
 * It scans only loaded water blocks close to the linked native Barrel. A
 * nearby Campfire supplies cooking, while legacy direct-Campfire memories stay
 * valid. The active cast's water position is preserved until completion.
 */
public final class VillagerFishermanWorkRuntime {
    private static final int FISH_RADIUS = 24;
    private static final int MAX_WATER_CHECKS = 384;
    private static final double FISH_REACH_SQUARED = 16.0D;
    private static final TagKey<Block> FISHING_WATER = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "fishing_water"));
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final FishermanWorldWorkAction ACTION = new FishermanWorldWorkAction();

    private VillagerFishermanWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerFishermanWorkRuntime::tick);
    }

    /** Runs loaded Fishermen synchronously for deterministic GameTests. */
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
        if (catalog.snapshot().values().stream().noneMatch(order -> "minecraft:fisherman".equals(order.professionId())
                && order.allowedSources().contains(WorkSource.WORLD))) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if ("minecraft:fisherman".equals(professionId(villager))) {
                    tickFisherman(level, villager, catalog, states, inventories.inventory(villager.getUUID()),
                            forceIdleScan);
                }
            }
        }
    }

    private static void tickFisherman(ServerLevel level, Villager fisherman, WorkOrderCatalog catalog,
                                      VillagerWorkSavedData states, VillagerWorkInventory inventory,
                                      boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(fisherman.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(fisherman)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            fisherman.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, fisherman)) {
            return;
        }
        Optional<FishermanContext> context = resolveContext(level, fisherman);
        List<WorkCandidate> candidates = context.map(value -> candidates(level, fisherman, state, value, catalog, inventory)).orElseGet(List::of);
        Optional<BlockPos> activeWater = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of);
        boolean atTarget = activeWater.map(water -> fisherman.distanceToSqr(Vec3.atCenterOf(water)) <= FISH_REACH_SQUARED).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(fisherman.getUUID(), "minecraft:fisherman", level.getGameTime(),
                fisherman.isAlive(), level.isLoaded(fisherman.blockPosition()), inDanger(fisherman), fisherman.isSleeping(),
                level.isRaided(fisherman.blockPosition()), context.isPresent(), atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, fisherman, scheduled.state(), scheduled.readyToCommit().orElseThrow(), context.orElse(null), inventory);
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            fisherman.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition).isPresent()
                && !atTarget && !inDanger(fisherman)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, fisherman)) {
            BlockPos water = BlockPos.of(next.activeWork().orElseThrow().worldTarget().orElseThrow()
                    .packedBlockPosition().orElseThrow());
            castingPosition(level, fisherman, water).ifPresent(position -> fisherman.getNavigation().moveTo(
                    position.getX() + .5D, position.getY(), position.getZ() + .5D, .5D));
        }
        if (!next.equals(state)) {
            states.put(next);
            MerchantOffers offers = ((dev.totem.villagers.mixin.AbstractVillagerOffersAccessor) (Object) fisherman)
                    .totemVillagers$existingOffers();
            if (offers != null) {
                VillagerTradeStockAuthority.refreshOffers(fisherman, offers);
            }
        }
    }

    private static List<WorkCandidate> candidates(ServerLevel level, Villager fisherman, VillagerWorkState state,
                                                   FishermanContext context, WorkOrderCatalog catalog, VillagerWorkInventory inventory) {
        return catalog.snapshot().values().stream()
                .filter(order -> "minecraft:fisherman".equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(level, fisherman, inventory, order))
                .filter(order -> FishingRodUse.bestAvailable(inventory.snapshot()).isPresent())
                .filter(order -> !usesCarriedBucket(order) || inventory.canReserveExact(order.requiredInputs()))
                .filter(order -> !requiresCampfireFuel(order)
                        || FishermanCampfireFuelSavedData.forServer(level.getServer())
                        .remainingCookings(fisherman.getUUID()) > 0
                        || carriedCampfireFuel(inventory) != null)
                .map(order -> candidateFor(level, fisherman, state, context, order))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager fisherman, VillagerWorkState state,
                                                         FishermanContext context, WorkOrder order) {
        if (!"totem:fishing_water".equals(order.worldTargetTag())) {
            return Optional.empty();
        }
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<BlockPos> water = current.flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(target -> validWaterTarget(level, fisherman, context, target, order, false));
        if (water.isEmpty() && current.isEmpty()) {
            water = findWater(level, fisherman, context.jobSite().pos());
        }
        return water.filter(target -> validWaterTarget(level, fisherman, context, target, order, false))
                .map(target -> new WorkCandidate(order.id(), WorkSource.WORLD, 10,
                        Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), target.asLong()))));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager fisherman, VillagerWorkState state,
                                            WorkOrder order, FishermanContext context, VillagerWorkInventory inventory) {
        BlockPos water = state.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition)
                .map(BlockPos::of).orElse(null);
        if (context == null || water == null || !validWaterTarget(level, fisherman, context, water, order, false)) {
            return failed(state, order, "fishing catch rejected");
        }
        ItemStack rod = FishingRodUse.nextForWork(inventory.snapshot()).orElse(null);
        if (rod == null) {
            return failed(state, order, "usable fishing rod unavailable");
        }
        WorkInventory.Reservation reservation = null;
        boolean completedOrder = false;
        boolean cookedCatch = false;
        boolean consumedNewCoal = false;
        try {
            List<ItemStack> returned = new ArrayList<>();
            if (usesCarriedBucket(order)) {
                reservation = inventory.reserveExactMatching(rod, order.requiredInputs()).orElse(null);
                if (reservation == null) {
                    return failed(state, order, "rod or bucket unavailable");
                }
                Optional<net.minecraft.world.item.ItemStack> captured = ACTION.captureBucketedFish(level, fisherman, water, order);
                if (captured.isEmpty() || !order.matchesOutput(captured.orElseThrow(), level.registryAccess())) {
                    reservation.rollback();
                    return failed(state, order, "fishing catch rejected");
                }
                returned.add(captured.orElseThrow());
                completedOrder = true;
            } else {
                FishermanWorldWorkAction.FishingAttempt attempt = ACTION.attempt(level, fisherman, water, order);
                if (!attempt.caughtAnything()) {
                    return failed(state, order, "fishing catch rejected");
                }
                attempt.orderOutput().ifPresent(returned::add);
                attempt.bycatch().stream()
                        .filter(stack -> VillageProductionStockPolicy.mayRetainFishingBycatch(
                                level, fisherman, inventory, stack))
                        .forEach(returned::add);
                completedOrder = attempt.orderOutput().isPresent();
                cookedCatch = returned.stream().anyMatch(VillagerFishermanWorkRuntime::isCampfireCookedFish);
                FishermanCampfireFuelSavedData fuel = FishermanCampfireFuelSavedData.forServer(level.getServer());
                consumedNewCoal = cookedCatch && fuel.remainingCookings(fisherman.getUUID()) < 1;
                Item carriedFuel = consumedNewCoal ? carriedCampfireFuel(inventory) : null;
                if (consumedNewCoal && carriedFuel == null) {
                    return failed(state, order, "campfire fuel unavailable");
                }
                Identifier fuelId = carriedFuel == null ? null : BuiltInRegistries.ITEM.getKey(carriedFuel);
                List<ItemAmount> fuelInput = consumedNewCoal
                        ? List.of(new ItemAmount(fuelId.toString(), 1)) : List.of();
                reservation = inventory.reserveExactMatching(rod, fuelInput).orElse(null);
                if (reservation == null) {
                    return failed(state, order, consumedNewCoal
                            ? "campfire fuel unavailable" : "usable fishing rod unavailable");
                }
            }
            ItemStack wornRod = FishingRodUse.wearOnce(rod);
            if (!wornRod.isEmpty()) {
                returned.add(wornRod);
            }
            if (!reservation.commitWithReturns(returned)) {
                reservation.rollback();
                return failed(state, order, "personal work inventory is full");
            }
        } catch (RuntimeException exception) {
            if (reservation != null) {
                reservation.rollback();
            }
            throw exception;
        }
        if (cookedCatch && !FishermanCampfireFuelSavedData.forServer(level.getServer())
                .consumeCooking(fisherman.getUUID(), consumedNewCoal)) {
            throw new IllegalStateException("Committed cooked fish without a Campfire fuel charge");
        }
        if (!completedOrder) {
            return failed(state, order, "fishing bycatch stored");
        }
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    private static boolean usesCarriedBucket(WorkOrder order) {
        return "minecraft:cod_bucket".equals(order.output().itemId());
    }

    private static boolean requiresCampfireFuel(WorkOrder order) {
        return "minecraft:cooked_cod".equals(order.output().itemId())
                || "minecraft:cooked_salmon".equals(order.output().itemId());
    }

    private static boolean isCampfireCookedFish(ItemStack stack) {
        return stack.is(Items.COOKED_COD) || stack.is(Items.COOKED_SALMON);
    }

    /** Renewable charcoal is consumed first; carried coal remains a compatible fallback. */
    private static Item carriedCampfireFuel(VillagerWorkInventory inventory) {
        if (VillageProductionStockPolicy.countItem(inventory, Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        return VillageProductionStockPolicy.countItem(inventory, Items.COAL) > 0 ? Items.COAL : null;
    }

    private static Optional<FishermanContext> resolveContext(ServerLevel level, Villager fisherman) {
        GlobalPos jobSite = fisherman.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || !jobSite.dimension().equals(level.dimension())
                || !level.isLoaded(jobSite.pos())) {
            return Optional.empty();
        }
        return FishermanWorkstation.campfireForJobSite(level, jobSite.pos())
                .map(campfire -> new FishermanContext(jobSite, campfire));
    }

    private static boolean validWaterTarget(ServerLevel level, Villager fisherman, FishermanContext context,
                                            BlockPos water, WorkOrder order, boolean requireCastingPath) {
        return "totem:fishing_water".equals(order.worldTargetTag())
                && level.isLoaded(water)
                && level.getBlockState(water).is(FISHING_WATER)
                && level.getBlockState(water.above()).isAir()
                && water.distSqr(context.jobSite().pos()) <= (double) FISH_RADIUS * FISH_RADIUS
                && WorldWorkPermissions.mayWork(level, fisherman, water)
                && (!requireCastingPath || castingPosition(level, fisherman, water).isPresent());
    }

    private static Optional<BlockPos> findWater(ServerLevel level, Villager fisherman, BlockPos jobSite) {
        int checks = 0;
        for (int radius = 1; radius <= FISH_RADIUS && checks < MAX_WATER_CHECKS; radius++) {
            for (int x = jobSite.getX() - radius; x <= jobSite.getX() + radius && checks < MAX_WATER_CHECKS; x++) {
                for (int z = jobSite.getZ() - radius; z <= jobSite.getZ() + radius && checks < MAX_WATER_CHECKS; z++) {
                    if (x != jobSite.getX() - radius && x != jobSite.getX() + radius
                            && z != jobSite.getZ() - radius && z != jobSite.getZ() + radius) continue;
                    for (int y = jobSite.getY() - 4; y <= jobSite.getY() + 2 && checks < MAX_WATER_CHECKS; y++) {
                        BlockPos water = new BlockPos(x, y, z);
                        checks++;
                        if (level.isLoaded(water) && level.getBlockState(water).is(FISHING_WATER)
                                && level.getBlockState(water.above()).isAir() && castingPosition(level, fisherman, water).isPresent()) {
                            return Optional.of(water);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> castingPosition(ServerLevel level, Villager fisherman, BlockPos water) {
        // dy=1 is a normal shoreline: the land block sits beside the water and
        // the villager stands in the air above it. dy=0 preserves the lowered
        // casting ledges used by older generated villages and existing worlds.
        return BlockPos.betweenClosedStream(water.offset(-1, 0, -1), water.offset(1, 1, 1))
                .filter(position -> position.getX() != water.getX() || position.getZ() != water.getZ())
                .filter(position -> level.isLoaded(position) && level.getBlockState(position).isAir()
                        && level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP))
                .filter(position -> fisherman.getNavigation().createPath(position, 0) != null)
                .min(Comparator.comparingDouble((BlockPos position) -> fisherman.distanceToSqr(Vec3.atCenterOf(position))));
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

    private record FishermanContext(GlobalPos jobSite, BlockPos campfire) {
    }
}
