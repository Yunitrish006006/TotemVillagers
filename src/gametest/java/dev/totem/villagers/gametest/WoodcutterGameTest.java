package dev.totem.villagers.gametest;

import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.woodcutter.LumberjackWoodcutterAction;
import dev.totem.villagers.woodcutter.WoodcutterMenu;
import dev.totem.villagers.woodcutter.WoodcutterRecipes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Verifies that the Woodcutter consumes the true live crafting input, not a static conversion table. */
public final class WoodcutterGameTest {
    @GameTest(maxTicks = 20)
    public void woodcutterUsesLiveCraftingRecipeAndExactMaterialCount(net.minecraft.gametest.framework.GameTestHelper helper) {
        BlockPos station = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.setBlock(new BlockPos(3, 2, 3), TotemVillagerBlocks.WOODCUTTER);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.teleportTo(station.getX() + 0.5D, station.getY() + 0.5D, station.getZ() + 0.5D);
            Inventory inventory = player.getInventory();
            WoodcutterMenu menu = new WoodcutterMenu(41, inventory,
                    ContainerLevelAccess.create(helper.getLevel(), station));
            Slot input = menu.getSlot(WoodcutterMenu.INPUT_SLOT);
            input.set(new ItemStack(Items.OAK_PLANKS, 3));

            int slabIndex = indexOf(helper, WoodcutterRecipes.matching(helper.getLevel(), new ItemStack(Items.OAK_PLANKS, 3)),
                    Items.OAK_SLAB);
            require(helper, slabIndex >= 0, "Live crafting recipes did not provide oak slabs to the Woodcutter");
            require(helper, menu.clickMenuButton(player, slabIndex), "Woodcutter rejected its live oak-slab recipe selection");
            require(helper, menu.requiredInputCount() == 3, "Woodcutter did not retain the crafting recipe's three-plank cost");
            require(helper, menu.getSlot(WoodcutterMenu.RESULT_SLOT).getItem().is(Items.OAK_SLAB),
                    "Woodcutter result was not the selected live recipe output");

            ItemStack output = menu.getSlot(WoodcutterMenu.RESULT_SLOT).remove(64);
            menu.getSlot(WoodcutterMenu.RESULT_SLOT).onTake(player, output);
            require(helper, output.getCount() == 6, "Woodcutter did not preserve the vanilla oak-slab result count");
            require(helper, input.getItem().isEmpty(), "Woodcutter did not consume exactly three oak planks");
            require(helper, menu.getSlot(WoodcutterMenu.RESULT_SLOT).getItem().isEmpty(),
                    "Woodcutter left a result after its live recipe no longer had materials");
            require(helper, menu.stillValid(player), "Woodcutter menu was not linked to its physical station");
            helper.succeed();
        } finally {
            player.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void lumberjackUsesPhysicalWoodcutterForAtomicLiveRecipeExchange(net.minecraft.gametest.framework.GameTestHelper helper) {
        BlockPos station = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.setBlock(new BlockPos(3, 2, 3), TotemVillagerBlocks.WOODCUTTER);
        Villager lumberjack = spawnVillager(helper, new BlockPos(1, 2, 3));
        try {
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(lumberjack.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.OAK_LOG)),
                    "Could not seed the Lumberjack's personal material inventory");
            WoodcutterRecipes.Match planks = WoodcutterRecipes.matching(helper.getLevel(), new ItemStack(Items.OAK_LOG))
                    .stream()
                    .filter(match -> match.output().is(Items.OAK_PLANKS))
                    .findFirst()
                    .orElseThrow(() -> helper.assertionException("Live crafting recipes did not provide oak planks"));

            require(helper, new LumberjackWoodcutterAction().complete(helper.getLevel(), lumberjack, station, planks, inventory),
                    "Lumberjack could not complete the live recipe at the physical Woodcutter");
            require(helper, count(inventory.snapshot(), Items.OAK_LOG) == 0,
                    "Lumberjack Woodcutter processing left its consumed oak log behind");
            require(helper, count(inventory.snapshot(), Items.OAK_PLANKS) == 4,
                    "Lumberjack Woodcutter processing did not preserve the live player recipe output count");
            helper.succeed();
        } finally {
            lumberjack.discard();
        }
    }

    private static int indexOf(net.minecraft.gametest.framework.GameTestHelper helper,
                               java.util.List<WoodcutterRecipes.Match> matches, net.minecraft.world.item.Item item) {
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).output().is(item)) {
                return index;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(net.minecraft.gametest.framework.GameTestHelper helper, BlockPos position) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
    }

    private static int count(java.util.List<ItemStack> stacks, net.minecraft.world.item.Item item) {
        return stacks.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void require(net.minecraft.gametest.framework.GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
