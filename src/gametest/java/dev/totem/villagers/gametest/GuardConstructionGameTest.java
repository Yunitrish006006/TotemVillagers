package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.guard.GuardPost;
import dev.totem.villagers.guard.ManagedVillageSavedData;
import dev.totem.villagers.guard.ManagedVillageState;
import dev.totem.villagers.runtime.GuardConstructionRuntime;
import dev.totem.villagers.network.TradeSnapshotPayload;
import dev.totem.villagers.trade.TradeSnapshotSender;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Exercises the registered Guard runtime against real block-pattern golem spawning. */
public final class GuardConstructionGameTest {
    @GameTest(maxTicks = 20)
    public void guardTradeSnapshotShowsDefenceDemandAndRegisteredPost(GameTestHelper helper) {
        GuardFixture fixture = configureGuard(helper, false);
        try {
            TradeSnapshotPayload snapshot = TradeSnapshotSender.snapshot(helper.getLevel(), fixture.guard(),
                    new MerchantOffers(), 14);
            TradeSnapshotPayload.GuardPostStatus status = snapshot.guardPost().orElse(null);
            require(helper, status != null && "defence_needed".equals(status.state())
                            && status.managedGolems() == 0 && status.defenceDemand() == 1 && status.nearbyThreats() == 0,
                    "Guard snapshot did not report the current defence demand");
            require(helper, status.post().isPresent() && status.post().orElseThrow().villageId().equals(fixture.villageId().toString())
                            && status.post().orElseThrow().padX() == fixture.pad().getX()
                            && status.post().orElseThrow().padY() == fixture.pad().getY()
                            && status.post().orElseThrow().padZ() == fixture.pad().getZ(),
                    "Guard snapshot did not report the registered Guard Post coordinates");
            require(helper, snapshot.reservedMaterials().isEmpty() && status.construction().isEmpty(),
                    "Idle Guard reported a construction reservation or progress");
            helper.succeed();
        } finally {
            fixture.cleanup();
        }
    }

    @GameTest(maxTicks = 220)
    public void guardReservesMaterialsBuildsAndRecordsOneManagedGolem(GameTestHelper helper) {
        GuardFixture fixture = configureGuard(helper, true);
        int[] schedulerTick = {0};
        // The shared server executes the existing GameTests in one batch; a
        // few older synchronous tests restore the global mode while the batch
        // is still being set up. Reassert it while this asynchronous
        // integration test waits for its own result.
        helper.succeedWhen(() -> {
            fixture.enableEnforcedMode();
            fixture.placeGuardAtSafeWorkPosition();
            advanceGuardScheduler(helper, schedulerTick);
            ManagedVillageState state = ManagedVillageSavedData.forServer(helper.getLevel().getServer())
                    .get(fixture.villageId()).orElseThrow();
            require(helper, state.construction().isEmpty() && state.managedGolemIds().size() == 1,
                    "Guard did not finish one durable managed-golem construction; construction="
                            + state.construction().map(value -> value.placedSteps()).orElse(-1)
                            + ", managed=" + state.managedGolemIds().size()
                            + ", iron=" + count(fixture.inventory().snapshot(), Items.IRON_BLOCK)
                            + ", pumpkin=" + count(fixture.inventory().snapshot(), Items.CARVED_PUMPKIN));
            require(helper, count(fixture.inventory().snapshot(), Items.IRON_BLOCK) == 0
                            && count(fixture.inventory().snapshot(), Items.CARVED_PUMPKIN) == 0,
                    "Guard construction did not consume its exact reserved materials");
            IronGolem golem = helper.getLevel().getEntityInAnyDimension(state.managedGolemIds().iterator().next()) instanceof IronGolem value
                    ? value : null;
            require(helper, golem != null && golem.isAlive(), "Guard did not record the real spawned Iron Golem");
            fixture.cleanup();
        });
    }

    @GameTest(maxTicks = 60)
    public void guardPostSuppressesOnlyAutomaticGolems(GameTestHelper helper) {
        GuardFixture fixture = configureGuard(helper, false);
        int[] schedulerTick = {0};
        IronGolem automatic = spawnIronGolem(helper, new BlockPos(9, 2, 8));
        IronGolem playerBuilt = spawnIronGolem(helper, new BlockPos(10, 2, 8));
        playerBuilt.setPlayerCreated(true);
        helper.succeedWhen(() -> {
            fixture.enableEnforcedMode();
            fixture.placeGuardAtSafeWorkPosition();
            GuardConstructionRuntime.tickForGameTest(helper.getLevel().getServer(), schedulerTick[0]++);
            require(helper, automatic.isRemoved(), "Managed Guard Post left a vanilla-created Iron Golem active");
            require(helper, !playerBuilt.isRemoved() && playerBuilt.isAlive(),
                    "Managed Guard Post removed a player-created Iron Golem");
            automatic.discard();
            playerBuilt.discard();
            fixture.cleanup();
        });
    }

    private static GuardFixture configureGuard(GameTestHelper helper, boolean supplyMaterials) {
        // Keep the whole five-block shape inside Fabric's default 8x8
        // GameTest template rather than placing an arm in its boundary wall.
        BlockPos pad = helper.absolutePos(new BlockPos(3, 2, 3));
        // Keep the no-AI test villager diagonally clear of the five-block
        // structure while retaining work range without pathfinding.
        Villager guard = spawnVillager(helper, new BlockPos(7, 2, 7));
        guard.setNoGravity(true);
        guard.setInvulnerable(true);
        setGuard(guard);
        VillagerNutrition.setFoodLevel(guard, 20);
        clearIronGolemPad(helper, pad);
        UUID owner = UUID.randomUUID();
        UUID villageId = UUID.randomUUID();
        var server = helper.getLevel().getServer();
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(guard.getUUID());
        if (supplyMaterials) {
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_BLOCK, 4)), "Could not supply Guard iron");
            require(helper, inventory.insertExact(new ItemStack(Items.CARVED_PUMPKIN, 1)), "Could not supply Guard pumpkin");
        }
        GuardPost post = new GuardPost(villageId, owner, helper.getLevel().dimension().identifier().toString(), pad.asLong());
        ManagedVillageState state = new ManagedVillageState(post, guard.getUUID(), Set.of(), Optional.empty());
        require(helper, ManagedVillageSavedData.forServer(server).registerOrUpdate(state, owner), "Could not register managed Guard village");
        WorkerAssignmentSavedData.forServer(server).putAssignment(new WorkerAssignment(guard.getUUID(), "totem:guard",
                Optional.empty(), Optional.of(villageId)));
        return new GuardFixture(helper, guard, owner, villageId, pad, inventory);
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    @SuppressWarnings("unchecked")
    private static IronGolem spawnIronGolem(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "iron_golem"));
        require(helper, type != null, "Missing minecraft:iron_golem entity type");
        return helper.spawn((EntityType<IronGolem>) type, relativePosition);
    }

    private static void setGuard(Villager guard) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.fromNamespaceAndPath("totem", "guard"));
        if (profession == null) {
            throw new IllegalStateException("Missing totem:guard profession");
        }
        guard.setVillagerData(guard.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static void clearIronGolemPad(GameTestHelper helper, BlockPos pad) {
        for (BlockPos target : new BlockPos[]{
                pad, pad.above(), pad.above().west(), pad.above().east(), pad.above(2)
        }) {
            helper.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void advanceGuardScheduler(GameTestHelper helper, int[] schedulerTick) {
        for (int step = 0; step < 60; step++) {
            GuardConstructionRuntime.tickForGameTest(helper.getLevel().getServer(), schedulerTick[0]++);
        }
    }

    private static int count(java.util.List<ItemStack> inventory, Item item) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private record GuardFixture(GameTestHelper helper, Villager guard, UUID owner, UUID villageId,
                                BlockPos pad, VillagerWorkInventory inventory) {
        private void enableEnforcedMode() {
            WorkBackedTradingSettingsSavedData.forServer(helper.getLevel().getServer()).setMode(WorkBackedTradingMode.ENFORCED);
        }

        private void placeGuardAtSafeWorkPosition() {
            guard.setPos(pad.getX() - .5D, pad.getY(), pad.getZ() - .5D);
        }

        private void cleanup() {
            var server = helper.getLevel().getServer();
            ManagedVillageSavedData.forServer(server).remove(villageId, owner);
            VillagerWorkInventorySavedData.forServer(server).drain(guard.getUUID());
            WorkerAssignmentSavedData.forServer(server).getAssignment(guard.getUUID())
                    .ifPresent(assignment -> WorkerAssignmentSavedData.forServer(server).putAssignment(
                            assignment.withManagedVillage(Optional.empty())));
            guard.discard();
        }
    }
}
