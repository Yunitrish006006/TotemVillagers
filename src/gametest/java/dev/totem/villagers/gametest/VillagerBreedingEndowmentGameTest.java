package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.runtime.VillagerBreedingEndowmentRuntime;
import dev.totem.villagers.runtime.VillagerStarterSupplyRuntime;
import dev.totem.villagers.runtime.VillagerStarterSupplySavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Covers parent-funded birth capital and its no-mint failure behaviour. */
public final class VillagerBreedingEndowmentGameTest {
    @GameTest(maxTicks = 30)
    public void bothParentsFundOneCompleteConservedEndowment(GameTestHelper helper) {
        Villager firstParent = spawnVillager(helper, new BlockPos(2, 2, 2));
        Villager secondParent = spawnVillager(helper, new BlockPos(4, 2, 2));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        Villager child = null;
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerWorkInventory firstInventory = inventories.inventory(firstParent.getUUID());
            VillagerWorkInventory secondInventory = inventories.inventory(secondParent.getUUID());
            seed(firstInventory, 10, 9);
            seed(secondInventory, 10, 9);

            child = breedAndAdd(helper, firstParent, secondParent);
            VillagerWorkInventory childInventory = inventories.inventory(child.getUUID());
            require(helper, count(firstInventory, Items.EMERALD) == 6
                            && count(secondInventory, Items.EMERALD) == 6,
                    "Each parent did not contribute exactly four emeralds");
            require(helper, count(firstInventory, Items.BREAD) == 6
                            && count(secondInventory, Items.BREAD) == 6,
                    "Each parent did not contribute exactly three bread");
            require(helper, count(childInventory, Items.EMERALD) == VillagerStarterSupplyRuntime.STARTING_EMERALDS
                            && count(childInventory, Items.BREAD) == VillagerStarterSupplyRuntime.STARTING_BREAD,
                    "Bred child did not receive the complete combined parent endowment");
            require(helper, total(firstInventory, secondInventory, childInventory, Items.EMERALD) == 20
                            && total(firstInventory, secondInventory, childInventory, Items.BREAD) == 18,
                    "Birth transfer created or destroyed emeralds or bread");

            require(helper, VillagerStarterSupplyRuntime.grantGeneratedVillageBase(child),
                    "A parent-funded child was not resolved by the generated-village ledger");
            require(helper, count(childInventory, Items.EMERALD) == VillagerStarterSupplyRuntime.STARTING_EMERALDS
                            && count(childInventory, Items.BREAD) == VillagerStarterSupplyRuntime.STARTING_BREAD,
                    "Generated-village processing paid a bred child twice");
            VillagerStarterSupplySavedData ledger = VillagerStarterSupplySavedData.forServer(server);
            require(helper, ledger.isBred(child.getUUID()) && ledger.hasBase(child.getUUID()),
                    "Bred and funded provenance was not persisted");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            cleanup(inventories, firstParent, secondParent, child);
        }
    }

    @GameTest(maxTicks = 30)
    public void underfundedParentCausesNoPartialTransferOrLaterWorldGrant(GameTestHelper helper) {
        Villager firstParent = spawnVillager(helper, new BlockPos(2, 2, 5));
        Villager secondParent = spawnVillager(helper, new BlockPos(4, 2, 5));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        Villager child = null;
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerWorkInventory firstInventory = inventories.inventory(firstParent.getUUID());
            VillagerWorkInventory secondInventory = inventories.inventory(secondParent.getUUID());
            seed(firstInventory, VillagerBreedingEndowmentRuntime.PARENT_EMERALDS,
                    VillagerBreedingEndowmentRuntime.PARENT_BREAD);
            seed(secondInventory, VillagerBreedingEndowmentRuntime.PARENT_EMERALDS - 1,
                    VillagerBreedingEndowmentRuntime.PARENT_BREAD);

            child = breedAndAdd(helper, firstParent, secondParent);
            VillagerWorkInventory childInventory = inventories.inventory(child.getUUID());
            require(helper, count(firstInventory, Items.EMERALD) == VillagerBreedingEndowmentRuntime.PARENT_EMERALDS
                            && count(firstInventory, Items.BREAD) == VillagerBreedingEndowmentRuntime.PARENT_BREAD
                            && count(secondInventory, Items.EMERALD) == VillagerBreedingEndowmentRuntime.PARENT_EMERALDS - 1
                            && count(secondInventory, Items.BREAD) == VillagerBreedingEndowmentRuntime.PARENT_BREAD,
                    "An underfunded birth partially debited one or both parents");
            require(helper, isEmpty(childInventory),
                    "An underfunded birth gave the child a partial endowment");
            VillagerStarterSupplySavedData ledger = VillagerStarterSupplySavedData.forServer(server);
            require(helper, ledger.isBred(child.getUUID()) && !ledger.hasBase(child.getUUID()),
                    "Underfunded bred provenance was not persisted independently from payment");

            require(helper, VillagerStarterSupplyRuntime.grantGeneratedVillageBase(child),
                    "Underfunded bred child was not closed against world-generation capital");
            require(helper, isEmpty(childInventory),
                    "Underfunded bred child later received minted world-generation assets");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            cleanup(inventories, firstParent, secondParent, child);
        }
    }

    private static Villager breedAndAdd(GameTestHelper helper, Villager firstParent, Villager secondParent) {
        Villager child = firstParent.getBreedOffspring(helper.getLevel(), secondParent);
        require(helper, child != null, "Vanilla villager breeding did not create a child");
        child.setAge(-24_000);
        child.setPos(firstParent.getX(), firstParent.getY(), firstParent.getZ());
        require(helper, helper.getLevel().addFreshEntity(child), "Vanilla server level rejected the bred child");
        return child;
    }

    private static void seed(VillagerWorkInventory inventory, int emeralds, int bread) {
        if (!inventory.insertAllExact(List.of(new ItemStack(Items.EMERALD, emeralds),
                new ItemStack(Items.BREAD, bread)))) {
            throw new IllegalStateException("Could not seed parent inventory");
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int total(VillagerWorkInventory first, VillagerWorkInventory second,
                             VillagerWorkInventory child, Item item) {
        return count(first, item) + count(second, item) + count(child, item);
    }

    private static boolean isEmpty(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream().allMatch(ItemStack::isEmpty);
    }

    private static void cleanup(VillagerWorkInventorySavedData inventories, Villager firstParent,
                                Villager secondParent, Villager child) {
        for (Villager villager : List.of(firstParent, secondParent)) {
            inventories.drain(villager.getUUID());
            villager.discard();
        }
        if (child != null) {
            inventories.drain(child.getUUID());
            child.discard();
        }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
