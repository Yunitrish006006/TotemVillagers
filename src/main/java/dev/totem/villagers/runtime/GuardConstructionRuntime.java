package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.guard.GuardConstructionState;
import dev.totem.villagers.guard.GuardDefenceOrder;
import dev.totem.villagers.guard.GuardDefenceOrderDefinitions;
import dev.totem.villagers.guard.GuardDefencePlanner;
import dev.totem.villagers.guard.GuardDefenceDemand;
import dev.totem.villagers.guard.GuardPlacement;
import dev.totem.villagers.guard.GuardPost;
import dev.totem.villagers.guard.ManagedVillageSavedData;
import dev.totem.villagers.guard.ManagedVillageState;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative Guard Post construction. Materials are removed from the
 * Guard's personal work inventory once and represented by persistent reservation state until
 * they are visibly placed or safely returned.
 */
public final class GuardConstructionRuntime {
    private static final int VILLAGE_RADIUS = 48;
    private static final int THREAT_RADIUS = 32;
    private static final int PLACEMENT_INTERVAL_TICKS = 10;
    private static final double CONSTRUCTION_REACH_SQUARED = 16.0D;
    private static final double GOLEM_MATCH_RADIUS_SQUARED = 36.0D;
    private static final GuardDefencePlanner PLANNER = new GuardDefencePlanner();

    private GuardConstructionRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GuardConstructionRuntime::tick);
    }

    private static void tick(MinecraftServer server) {
        tick(server, server.getTickCount(), false);
    }

    /**
     * Deterministic test hook for the same server-authoritative state machine.
     * GameTest can advance its virtual clock faster than END_SERVER_TICK is
     * dispatched and transforms its fixture coordinates, so callers provide
     * the scheduler tick explicitly and bypass only the physical navigation
     * gate. Reservation, placement, spawning and persistence remain real.
     */
    public static void tickForGameTest(MinecraftServer server, int schedulerTick) {
        tick(server, schedulerTick, true);
    }

    private static void tick(MinecraftServer server, int schedulerTick, boolean bypassNavigationForTest) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        ManagedVillageSavedData villages = ManagedVillageSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        for (ManagedVillageState persisted : villages.snapshot().values()) {
            PostContext post = resolvePost(server, persisted.post(), persisted.guardVillagerId(), inventories);
            GuardDefenceOrder reservedOrder = persisted.construction()
                    .flatMap(construction -> GuardDefenceOrderDefinitions.get(construction.orderId())).orElse(null);
            if (post == null) {
                // Do not drop an unloaded reservation: the durable state is the
                // source of truth until the relevant dimension is available again.
                continue;
            }
            Villager guard = findGuard(post.level(), persisted.guardVillagerId());
            if (!isResponsibleGuard(guard, persisted, post, assignments)) {
                if (reservedOrder != null) {
                    persist(villages, cancel(persisted, reservedOrder, post));
                }
                continue;
            }
            // Spawn suppression protects the configured village boundary, not a
            // Guard's current stamina. A hungry Guard must stop constructing,
            // but that must not briefly re-enable vanilla automatic spawning.
            ManagedVillageState current = pruneManagedGolems(persisted, post.level());
            current = recoverCompletedConstruction(current, post);
            suppressAutomaticGolems(current, post);
            if (!current.equals(persisted)) {
                persist(villages, current);
            }
            if (!VillagerWorkNeeds.canWork(guard)) {
                GuardDefenceOrder currentOrder = current.construction()
                        .flatMap(construction -> GuardDefenceOrderDefinitions.get(construction.orderId())).orElse(null);
                if (currentOrder != null) {
                    persist(villages, cancel(current, currentOrder, post));
                }
                guard.getNavigation().stop();
                continue;
            }
            if (guard.isSleeping()) {
                continue;
            }

            GuardConstructionState construction = current.construction().orElse(null);
            if (construction == null) {
                beginConstruction(current, guard, post).ifPresent(next -> persist(villages, next));
                continue;
            }
            GuardDefenceOrder order = GuardDefenceOrderDefinitions.get(construction.orderId()).orElse(null);
            if (order == null) {
                // Removing a live data-pack order cannot safely infer which
                // partial blocks were already placed, so keep the reservation
                // durable until the order returns instead of duplicating it.
                continue;
            }
            ManagedVillageState next = advanceConstruction(current, guard, post, order, schedulerTick, bypassNavigationForTest);
            if (!next.equals(current)) {
                persist(villages, next);
            }
        }
    }

    private static Optional<ManagedVillageState> beginConstruction(ManagedVillageState village, Villager guard, PostContext post) {
        GuardDefenceOrder order = GuardDefenceOrderDefinitions.get(GuardDefenceOrder.VANILLA_IRON_GOLEM.id()).orElse(null);
        if (order == null || !isClearConstructionPad(post, guard, order)) {
            return Optional.empty();
        }
        int demand = defenceDemand(post.level(), post.pad());
        GuardConstructionState construction = PLANNER.beginIfNeeded(village, demand, order).orElse(null);
        if (construction == null) {
            return Optional.empty();
        }
        var reservation = post.inventory().reserveExact(order.requiredInputs()).orElse(null);
        if (reservation == null) {
            return Optional.empty();
        }
        reservation.commit();
        return Optional.of(village.withConstruction(Optional.of(construction)));
    }

    private static ManagedVillageState advanceConstruction(
            ManagedVillageState village, Villager guard, PostContext post, GuardDefenceOrder order, int serverTick,
            boolean bypassNavigationForTest
    ) {
        GuardConstructionState construction = village.construction().orElseThrow();
        if (construction.placedSteps() >= order.placements().size()) {
            return rollbackEntireConstruction(village, order, post);
        }
        if (!hasExpectedPartialStructure(post, order, construction.placedSteps())) {
            return cancel(village, order, post);
        }
        GuardPlacement placement = order.placements().get(construction.placedSteps());
        BlockPos target = placementPosition(post.pad(), placement);
        if (!post.level().isLoaded(target) || !post.level().getBlockState(target).isAir()
                || !WorldWorkPermissions.mayWork(post.level(), guard, target)) {
            return cancel(village, order, post);
        }
        if (!bypassNavigationForTest && guard.distanceToSqr(Vec3.atCenterOf(post.pad())) > CONSTRUCTION_REACH_SQUARED) {
            guard.getNavigation().moveTo(post.pad().getX() + .5D, post.pad().getY(), post.pad().getZ() + .5D, .5D);
            return village;
        }
        if (serverTick % PLACEMENT_INTERVAL_TICKS != 0) {
            return village;
        }
        Block block = block(placement.blockId());
        if (block == null) {
            return cancel(village, order, post);
        }
        Set<UUID> before = nearbyGolems(post.level(), post.pad()).stream().map(IronGolem::getUUID).collect(java.util.stream.Collectors.toSet());
        if (!post.level().setBlock(target, block.defaultBlockState(), 3)) {
            return cancel(village, order, post);
        }
        guard.swing(InteractionHand.MAIN_HAND);
        guard.playWorkSound();
        int nextStep = construction.placedSteps() + 1;
        if (nextStep < order.placements().size()) {
            return village.withConstruction(Optional.of(construction.withPlacedSteps(nextStep)));
        }
        IronGolem created = nearbyGolems(post.level(), post.pad()).stream()
                .filter(golem -> !before.contains(golem.getUUID())).findFirst().orElse(null);
        if (created != null) {
            return PLANNER.recordManagedGolem(village, created.getUUID());
        }
        return rollbackEntireConstruction(village, order, post);
    }

    private static ManagedVillageState recoverCompletedConstruction(ManagedVillageState village, PostContext post) {
        GuardConstructionState construction = village.construction().orElse(null);
        if (construction == null) {
            return village;
        }
        GuardDefenceOrder order = GuardDefenceOrderDefinitions.get(construction.orderId()).orElse(null);
        if (order == null || construction.placedSteps() != order.placements().size() - 1) {
            return village;
        }
        IronGolem created = nearbyGolems(post.level(), post.pad()).stream()
                .filter(golem -> !village.managedGolemIds().contains(golem.getUUID())).findFirst().orElse(null);
        return created == null ? village : PLANNER.recordManagedGolem(village, created.getUUID());
    }

    private static ManagedVillageState cancel(ManagedVillageState village, GuardDefenceOrder order, PostContext post) {
        GuardConstructionState construction = village.construction().orElse(null);
        if (construction == null) {
            return village;
        }
        refund(post, unplacedInputs(construction, order));
        return PLANNER.cancel(village);
    }

    private static ManagedVillageState rollbackEntireConstruction(ManagedVillageState village, GuardDefenceOrder order, PostContext post) {
        for (GuardPlacement placement : order.placements()) {
            Block block = block(placement.blockId());
            BlockPos target = placementPosition(post.pad(), placement);
            if (block != null && post.level().isLoaded(target) && post.level().getBlockState(target).is(block)) {
                post.level().removeBlock(target, false);
            }
        }
        GuardConstructionState construction = village.construction().orElseThrow();
        refund(post, construction.reservedInputs());
        return PLANNER.cancel(village);
    }

    private static List<ItemAmount> unplacedInputs(GuardConstructionState construction, GuardDefenceOrder order) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        construction.reservedInputs().forEach(input -> remaining.merge(input.itemId(), input.count(), Math::addExact));
        int placed = Math.min(construction.placedSteps(), order.placements().size());
        for (int index = 0; index < placed; index++) {
            String itemId = order.placements().get(index).blockId();
            Integer count = remaining.get(itemId);
            if (count == null || count < 1) {
                throw new IllegalStateException("Guard placement exceeds its material reservation");
            }
            if (count == 1) remaining.remove(itemId); else remaining.put(itemId, count - 1);
        }
        return remaining.entrySet().stream().map(entry -> new ItemAmount(entry.getKey(), entry.getValue())).toList();
    }

    private static void refund(PostContext post, List<ItemAmount> materials) {
        for (ItemAmount material : materials) {
            Identifier id = Identifier.tryParse(material.itemId());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item == null) {
                continue;
            }
            int remaining = material.count();
            while (remaining > 0) {
                int count = Math.min(remaining, item.getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(item, count);
                if (!post.inventory().insertExact(stack)) {
                    post.level().addFreshEntity(new ItemEntity(post.level(), post.pad().getX() + .5D,
                            post.pad().getY() + 1D, post.pad().getZ() + .5D, stack));
                }
                remaining -= count;
            }
        }
    }

    private static boolean isClearConstructionPad(PostContext post, Villager guard, GuardDefenceOrder order) {
        return order.placements().stream().map(placement -> placementPosition(post.pad(), placement))
                .allMatch(target -> post.level().isLoaded(target) && post.level().getBlockState(target).isAir()
                        && WorldWorkPermissions.mayWork(post.level(), guard, target));
    }

    private static boolean hasExpectedPartialStructure(PostContext post, GuardDefenceOrder order, int placedSteps) {
        for (int index = 0; index < placedSteps; index++) {
            GuardPlacement placement = order.placements().get(index);
            Block block = block(placement.blockId());
            BlockPos target = placementPosition(post.pad(), placement);
            if (block == null || !post.level().isLoaded(target) || !post.level().getBlockState(target).is(block)) {
                return false;
            }
        }
        return true;
    }

    private static int defenceDemand(ServerLevel level, BlockPos pad) {
        int residents = (int) LoadedVillagerCache.loaded(level).stream()
                .filter(villager -> villager.isAlive()
                        && villager.distanceToSqr(Vec3.atCenterOf(pad)) <= (double) VILLAGE_RADIUS * VILLAGE_RADIUS)
                .count();
        int threats = level.getEntities(EntityTypeTest.forClass(Monster.class), monster -> monster.isAlive()
                && monster.distanceToSqr(Vec3.atCenterOf(pad)) <= (double) THREAT_RADIUS * THREAT_RADIUS).size();
        return GuardDefenceDemand.fromCounts(residents, threats).targetGolems();
    }

    private static ManagedVillageState pruneManagedGolems(ManagedVillageState village, ServerLevel level) {
        Set<UUID> live = new LinkedHashSet<>();
        for (UUID golemId : village.managedGolemIds()) {
            Entity entity = level.getEntityInAnyDimension(golemId);
            if (entity instanceof IronGolem golem && golem.isAlive()) {
                live.add(golemId);
            }
        }
        return live.equals(village.managedGolemIds()) ? village : village.withManagedGolems(live);
    }

    /** Removes only vanilla-created golems at this explicitly managed Guard Post; player-built golems remain untouched. */
    private static void suppressAutomaticGolems(ManagedVillageState village, PostContext post) {
        for (IronGolem golem : golemsWithin(post.level(), post.pad(), VILLAGE_RADIUS)) {
            if (!golem.isPlayerCreated() && !village.managedGolemIds().contains(golem.getUUID())) {
                golem.discard();
            }
        }
    }

    private static List<IronGolem> nearbyGolems(ServerLevel level, BlockPos pad) {
        return golemsWithin(level, pad, Math.sqrt(GOLEM_MATCH_RADIUS_SQUARED));
    }

    private static List<IronGolem> golemsWithin(ServerLevel level, BlockPos pad, double radius) {
        return level.getEntities(EntityTypeTest.forClass(IronGolem.class), golem -> golem.isAlive()
                        && golem.distanceToSqr(Vec3.atCenterOf(pad)) <= radius * radius)
                .stream().map(IronGolem.class::cast).toList();
    }

    private static PostContext resolvePost(MinecraftServer server, GuardPost post, UUID guardId,
                                           VillagerWorkInventorySavedData inventories) {
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(post.dimensionId())) {
                level = candidate;
                break;
            }
        }
        if (level == null) {
            return null;
        }
        BlockPos pad = BlockPos.of(post.packedConstructionPad());
        if (!level.isLoaded(pad)) {
            return null;
        }
        return new PostContext(level, pad, inventories.inventory(guardId));
    }

    private static Villager findGuard(ServerLevel level, UUID guardId) {
        net.minecraft.world.entity.Entity entity = level.getEntity(guardId);
        return entity instanceof Villager villager && villager.isAlive() ? villager : null;
    }

    private static boolean isResponsibleGuard(Villager guard, ManagedVillageState village, PostContext post,
                                              WorkerAssignmentSavedData assignments) {
        if (guard == null || !"totem:guard".equals(professionId(guard))) {
            return false;
        }
        return assignments.getAssignment(guard.getUUID())
                .filter(assignment -> "totem:guard".equals(assignment.roleId()))
                .flatMap(assignment -> assignment.managedVillageId())
                .filter(village.post().villageId()::equals)
                .isPresent();
    }

    private static BlockPos placementPosition(BlockPos pad, GuardPlacement placement) {
        return pad.offset(placement.x(), placement.y(), placement.z());
    }

    private static Block block(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? null : BuiltInRegistries.BLOCK.getValue(identifier);
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static void persist(ManagedVillageSavedData villages, ManagedVillageState state) {
        villages.registerOrUpdate(state, state.post().ownerId());
    }

    private record PostContext(ServerLevel level, BlockPos pad, VillagerWorkInventory inventory) {
    }
}
