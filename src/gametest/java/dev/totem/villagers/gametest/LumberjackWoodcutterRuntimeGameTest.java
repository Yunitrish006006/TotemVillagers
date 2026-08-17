package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.runtime.LumberjackWoodcutterRuntime;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import dev.totem.villagers.woodcutter.WoodcutterRecipes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Proves a generated village's persisted station can drive an actual Lumberjack material conversion. */
public final class LumberjackWoodcutterRuntimeGameTest {
    @GameTest(maxTicks = 40)
    public void assignedLumberjackUsesItsDistantGeneratedWoodcutterForLiveVillageDemand(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        String villageId = "game-test-lumberjack-woodcutter-" + UUID.randomUUID();
        BlockPos lumberjackPosition = new BlockPos(3, 3, 3);
        BlockPos loom = new BlockPos(6, 2, 3);
        BlockPos outsideCampfire = new BlockPos(25, 2, 3);
        BlockPos station = helper.absolutePos(new BlockPos(23, 2, 3));
        helper.setBlock(loom, Blocks.LOOM);
        helper.setBlock(outsideCampfire, Blocks.CAMPFIRE);
        helper.setBlock(new BlockPos(23, 2, 3), TotemVillagerBlocks.WOODCUTTER);
        Villager lumberjack = spawnVillager(helper, lumberjackPosition, "totem:lumberjack");
        Villager shepherd = spawnVillager(helper, loom.above(), "minecraft:shepherd");
        Villager outsideFisherman = spawnVillager(helper, new BlockPos(25, 3, 5), "minecraft:fisherman");
        WorkZoneRecord zone = assignments.createZone("totem:lumberjack", new WorkZone(UUID.randomUUID(),
                level.dimension().identifier().toString(), coordinate(helper.absolutePos(new BlockPos(2, 2, 2))),
                coordinate(helper.absolutePos(new BlockPos(4, 6, 4)))));
        Runnable cleanup = () -> {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            assignments.removeAssignment(lumberjack.getUUID());
            assignments.removeZone(zone.id());
            GeneratedVillageSavedData.forServer(server).remove(villageId);
            VillagerWorkSavedData.forServer(server).remove(lumberjack.getUUID());
            VillagerWorkSavedData.forServer(server).remove(shepherd.getUUID());
            VillagerWorkSavedData.forServer(server).remove(outsideFisherman.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(lumberjack.getUUID());
            inventories.drain(shepherd.getUUID());
            inventories.drain(outsideFisherman.getUUID());
            lumberjack.discard();
            shepherd.discard();
            outsideFisherman.discard();
        };
        boolean handedOffToTickingAssertion = false;
        try {
            VillagerNutrition.setFoodLevel(lumberjack, 20);
            VillagerNutrition.setFoodLevel(shepherd, 20);
            VillagerNutrition.setFoodLevel(outsideFisherman, 20);
            shepherd.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), helper.absolutePos(loom)));
            outsideFisherman.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(level.dimension(), helper.absolutePos(outsideCampfire)));
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BED.white()), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) shepherd).totemVillagers$setExistingOffers(offers);
            MerchantOffers outsideOffers = new MerchantOffers();
            outsideOffers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1),
                    new ItemStack(Items.CAMPFIRE), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) outsideFisherman).totemVillagers$setExistingOffers(outsideOffers);
            VillagerWorkInventory lumberjackInventory = VillagerWorkInventorySavedData.forServer(server)
                    .inventory(lumberjack.getUUID());
            VillagerWorkInventory shepherdInventory = VillagerWorkInventorySavedData.forServer(server)
                    .inventory(shepherd.getUUID());
            VillagerWorkInventory outsideInventory = VillagerWorkInventorySavedData.forServer(server)
                    .inventory(outsideFisherman.getUUID());
            require(helper, lumberjackInventory.insertAllExact(List.of(
                            new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.OAK_LOG))),
                    "Could not seed the Lumberjack's decoy planks and gathered oak log");
            require(helper, shepherdInventory.insertExact(new ItemStack(Items.WOOL.white(), 3)),
                    "Could not seed the Shepherd's near-complete white-bed materials");
            require(helper, outsideInventory.insertAllExact(List.of(
                            new ItemStack(Items.COAL), new ItemStack(Items.OAK_LOG, 3))),
                    "Could not seed the outside Fisherman's near-complete Campfire materials");
            assignments.putAssignment(new WorkerAssignment(lumberjack.getUUID(), "totem:lumberjack",
                    Optional.of(zone.id()), Optional.empty()));
            GeneratedVillageSavedData.forServer(server).discover(new GeneratedVillageState(villageId,
                    level.dimension().identifier().toString(), coordinate(helper.absolutePos(new BlockPos(1, 1, 1))),
                    coordinate(helper.absolutePos(new BlockPos(24, 8, 8))), true, Optional.of(zone.id()),
                    Optional.of(coordinate(station))));

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            LumberjackWoodcutterRuntime.tickForGameTest(server);
            require(helper, count(lumberjackInventory, Items.OAK_LOG) == 1
                            && count(lumberjackInventory, Items.OAK_PLANKS) == 2,
                    "Lumberjack processed wood before reaching its distant generated Woodcutter");

            lumberjack.teleportTo(station.getX() - .5D, station.getY(), station.getZ() + .5D);
            var demand = VillagerWorkshopRuntime.materialDemandFor(level, shepherd);
            var outsideDemand = VillagerWorkshopRuntime.materialDemandFor(level, outsideFisherman);
            var plankMatch = WoodcutterRecipes.matching(level, new ItemStack(Items.OAK_LOG)).stream()
                    .filter(match -> match.output().is(Items.OAK_PLANKS) && match.output().getCount() == 4)
                    .findFirst();
            require(helper, demand.isPresent(),
                    "Shepherd's live white-bed offer did not expose a workshop material demand");
            require(helper, demand.orElseThrow().requiredInputs().stream()
                            .anyMatch(input -> "minecraft:oak_planks".equals(input.itemId()) && input.count() == 3),
                    "Shepherd's selected live order did not demand three oak planks: " + demand.orElseThrow());
            require(helper, outsideDemand.filter(order -> "totem:fisherman_campfire".equals(order.id())).isPresent()
                            && outsideDemand.orElseThrow().requiredInputs().stream()
                            .anyMatch(input -> "minecraft:stick".equals(input.itemId()) && input.count() == 3),
                    "Outside-village control Fisherman did not expose its nearer stick demand: " + outsideDemand);
            require(helper, plankMatch.isPresent(), "Live recipes did not expose the one-log to four-plank conversion");
            require(helper, VillagerWorkNeeds.canWork(lumberjack)
                            && VillagerWorkSavedData.forServer(server).getOrCreate(lumberjack.getUUID()).activeWork().isEmpty(),
                    "Lumberjack was not eligible for immediate Woodcutter work");
            require(helper, lumberjack.distanceToSqr(Vec3.atCenterOf(station)) <= 16.0D,
                    "Teleported Lumberjack was still outside Woodcutter reach: " + lumberjack.position());
            helper.succeedWhen(() -> {
                // Production evaluates this once each second. Wait across real
                // server ticks instead of assuming that a navigation teleport
                // and a second global runtime scan must settle in the same tick.
                settings.setMode(WorkBackedTradingMode.ENFORCED);
                LumberjackWoodcutterRuntime.tickForGameTest(server);
                require(helper, count(lumberjackInventory, Items.OAK_LOG) == 0
                                && count(lumberjackInventory, Items.OAK_PLANKS) == 6
                                && count(lumberjackInventory, Items.STICK) == 0,
                        "Lumberjack has not yet served its own village before the nearer outside demand; inventory="
                                + lumberjackInventory.snapshot() + ", station=" + level.getBlockState(station)
                                + ", loaded=" + level.isLoaded(station) + ", position=" + lumberjack.position());
                cleanup.run();
            });
            handedOffToTickingAssertion = true;
        } finally {
            if (!handedOffToTickingAssertion) {
                cleanup.run();
            }
        }
    }

    private static BlockCoordinate coordinate(BlockPos position) {
        return new BlockCoordinate(position.getX(), position.getY(), position.getZ());
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing villager profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
