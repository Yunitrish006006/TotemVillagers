package dev.totem.villagers.gametest;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.inventory.WorkInventory;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Exercises workshop commits against the saved 27-slot villager inventory. */
public final class PersonalInventoryWorkshopGameTest {
    @GameTest(maxTicks = 40)
    public void validWorkshopCommitConsumesOnlyPersonalMaterialsAndCreditsStock(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        Villager farmer = spawnFarmer(helper, relativeJobSite.above());
        try {
            VillagerWorkInventory inventory = inventory(helper, farmer);
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not seed the Farmer's personal work inventory");
            MerchantStock stock = new MerchantStock();
            WorkOrder bread = WorkOrderDefinitions.catalog().require("totem:farmer_bread");

            WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, bread, stock,
                    new RecipeBackedWorkshopAction(helper.getLevel(), farmer, jobSite, bread));

            require(helper, result == WorkshopCommitResult.COMPLETED, "Personal-inventory workshop commit failed: " + result);
            require(helper, count(inventory, Items.WHEAT) == 0,
                    "Workshop commit did not consume the Farmer's exact personal wheat inputs");
            require(helper, stock.available("minecraft:bread") == bread.output().count(),
                    "Workshop commit did not credit the exact vanilla bread output");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void invalidWorkshopLeavesPersonalMaterialsAndStockUntouched(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.DIRT);
        Villager farmer = spawnFarmer(helper, relativeJobSite.above());
        try {
            VillagerWorkInventory inventory = inventory(helper, farmer);
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT, 9)),
                    "Could not seed the Farmer's personal work inventory");
            MerchantStock stock = new MerchantStock();
            WorkOrder bread = WorkOrderDefinitions.catalog().require("totem:farmer_bread");

            WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, bread, stock,
                    new RecipeBackedWorkshopAction(helper.getLevel(), farmer, jobSite, bread));

            require(helper, result == WorkshopCommitResult.JOB_SITE_REJECTED,
                    "Invalid workshop unexpectedly completed: " + result);
            require(helper, count(inventory, Items.WHEAT) == 9,
                    "Invalid workshop commit did not restore the Farmer's personal materials");
            require(helper, stock.available("minecraft:bread") == 0,
                    "Invalid workshop commit credited merchant stock");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void withdrawalReturnsOnlyVisibleMaterialsAndLeavesReservationsProtected(GameTestHelper helper) {
        Villager farmer = spawnFarmer(helper, new BlockPos(3, 2, 3));
        try {
            VillagerWorkInventory inventory = inventory(helper, farmer);
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT, 12)),
                    "Could not seed the visible withdrawal stack");
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_INGOT, 3)),
                    "Could not seed the reserved material stack");
            WorkInventory.Reservation reservation = inventory.reserveExact(
                    List.of(new ItemAmount("minecraft:iron_ingot", 3))).orElseThrow(() ->
                    helper.assertionException("Could not reserve the protected material stack"));

            ItemStack withdrawn = inventory.takeFirstStack().orElseThrow(() ->
                    helper.assertionException("Could not withdraw the visible material stack"));
            require(helper, withdrawn.is(Items.WHEAT) && withdrawn.getCount() == 12,
                    "Withdrawal did not return the first complete visible stack: " + withdrawn);
            require(helper, count(inventory, Items.WHEAT) == 0,
                    "Withdrawn materials remained in the work inventory");
            require(helper, count(inventory, Items.IRON_INGOT) == 0,
                    "Reserved materials were visible to withdrawal");

            reservation.rollback();
            require(helper, count(inventory, Items.IRON_INGOT) == 3,
                    "Protected materials did not return after the reservation rolled back");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    private static VillagerWorkInventory inventory(GameTestHelper helper, Villager villager) {
        return VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer()).inventory(villager.getUUID());
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnFarmer(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession farmer = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        if (farmer == null) {
            throw new IllegalStateException("Missing minecraft:farmer profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(farmer)));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
