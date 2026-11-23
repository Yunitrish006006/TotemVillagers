package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Each step consumes an exact live crafting grid and keeps its output and recipe remainders together. */
final class VillageWoodCrafting {
    static final int FIBRES_PER_STRING = 3;
    static final TagKey<Item> PLANT_FIBRES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("totem", "plant_fibers"));

    private VillageWoodCrafting() {}

    static boolean craftPlanks(ServerLevel level, VillagerWorkInventory inventory) {
        return inventory.snapshot().stream().filter(stack -> stack.is(ItemTags.LOGS))
                .anyMatch(log -> craft(level, inventory, 1, 1, List.of(log.copyWithCount(1)),
                        output -> output.is(ItemTags.PLANKS)));
    }

    static boolean makesPlanks(ServerLevel level, ItemStack log, ItemStack needed) {
        if (!log.is(ItemTags.LOGS)) return false;
        var grid = CraftingInput.of(1, 1, List.of(log.copyWithCount(1)));
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .filter(recipe -> ItemStack.isSameItemSameComponents(recipe.value().assemble(grid), needed)).isPresent();
    }

    static boolean craftPlanks(ServerLevel level, VillagerWorkInventory inventory, ItemStack needed) {
        return inventory.snapshot().stream().filter(stack -> makesPlanks(level, stack, needed))
                .anyMatch(log -> craft(level, inventory, 1, 1, List.of(log.copyWithCount(1)),
                        output -> ItemStack.isSameItemSameComponents(output, needed)));
    }

    static boolean craftSticks(ServerLevel level, VillagerWorkInventory inventory) {
        List<ItemStack> planks = planks(inventory, 2);
        return planks.size() == 2 && craft(level, inventory, 1, 2, planks, output -> output.is(Items.STICK));
    }

    static List<ItemStack> plantFibreInputs(VillagerWorkInventory inventory) {
        List<ItemStack> inputs = new ArrayList<>(FIBRES_PER_STRING);
        for (ItemStack stack : inventory.snapshot()) {
            if (stack.isEmpty() || !stack.is(PLANT_FIBRES)) {
                continue;
            }
            int accepted = Math.min(stack.getCount(), FIBRES_PER_STRING - inputs.size());
            for (int index = 0; index < accepted; index++) {
                inputs.add(stack.copyWithCount(1));
            }
            if (inputs.size() == FIBRES_PER_STRING) {
                return List.copyOf(inputs);
            }
        }
        return List.copyOf(inputs);
    }

    static boolean craftPlantFibreString(ServerLevel level, VillagerWorkInventory inventory, List<ItemStack> inputs) {
        return inputs.size() == FIBRES_PER_STRING && inputs.stream().allMatch(stack -> stack.is(PLANT_FIBRES))
                && craft(level, inventory, 3, 1, inputs, output -> output.is(Items.STRING));
    }

    static boolean craftBread(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack wheat = new ItemStack(Items.WHEAT);
        return craft(level, inventory, 3, 1, List.of(wheat, wheat.copy(), wheat.copy()),
                output -> output.is(Items.BREAD));
    }

    static boolean craftRod(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack string = new ItemStack(Items.STRING);
        return craft(level, inventory, 3, 3, List.of(ItemStack.EMPTY, ItemStack.EMPTY, stick,
                ItemStack.EMPTY, stick.copy(), string, stick.copy(), ItemStack.EMPTY, string.copy()),
                output -> output.is(Items.FISHING_ROD));
    }

    static boolean prepareHoe(ServerLevel level, VillagerWorkInventory inventory) {
        List<ItemStack> planks = planks(inventory, 2);
        if (planks.size() < 2) {
            return craftPlanks(level, inventory);
        }
        if (inventory.countMatchingItem(new ItemStack(Items.STICK)) < 2) {
            return craftSticks(level, inventory);
        }
        ItemStack stick = new ItemStack(Items.STICK);
        return craft(level, inventory, 2, 3, List.of(planks.getFirst(), planks.getLast(),
                ItemStack.EMPTY, stick, ItemStack.EMPTY, stick.copy()), output -> output.is(Items.WOODEN_HOE));
    }

    static boolean preparePickaxe(ServerLevel level, VillagerWorkInventory inventory) {
        var boards = planks(inventory, 3);
        if (boards.size() < 3) return craftPlanks(level, inventory);
        if (inventory.countMatchingItem(new ItemStack(Items.STICK)) < 2) return craftSticks(level, inventory);
        ItemStack stick = new ItemStack(Items.STICK);
        return craft(level, inventory, 3, 3, List.of(boards.get(0), boards.get(1), boards.get(2),
                ItemStack.EMPTY, stick, ItemStack.EMPTY, ItemStack.EMPTY, stick.copy(), ItemStack.EMPTY),
                output -> output.is(Items.WOODEN_PICKAXE));
    }

    static boolean prepareCraftingTable(ServerLevel level, VillagerWorkInventory inventory) {
        List<ItemStack> materials = planks(inventory, 4);
        if (materials.size() < 4) return craftPlanks(level, inventory);
        return craft(level, inventory, 2, 2, materials, output -> output.is(Items.CRAFTING_TABLE));
    }

    private static List<ItemStack> planks(VillagerWorkInventory inventory, int needed) {
        List<ItemStack> planks = new ArrayList<>(needed);
        for (ItemStack stack : inventory.snapshot()) {
            if (stack.is(ItemTags.PLANKS)) {
                for (int i = 0; i < stack.getCount() && planks.size() < needed; i++) {
                    planks.add(stack.copyWithCount(1));
                }
                if (planks.size() == needed) break;
            }
        }
        return planks;
    }

    private static boolean craft(ServerLevel level, VillagerWorkInventory inventory, int width, int height,
                                 List<ItemStack> slots, Predicate<ItemStack> accepts) {
        CraftingInput input = CraftingInput.of(width, height, slots);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null) return false;
        ItemStack output = recipe.value().assemble(input);
        if (output.isEmpty() || !accepts.test(output)) return false;
        List<ItemStack> returns = new ArrayList<>();
        returns.add(output.copy());
        recipe.value().getRemainingItems(input).stream().filter(stack -> !stack.isEmpty()).forEach(returns::add);
        var reservation = inventory.reserveExactMatching(slots.stream().filter(stack -> !stack.isEmpty()).toList())
                .orElse(null);
        if (reservation == null) return false;
        if (reservation.commitWithReturns(returns)) return true;
        reservation.rollback();
        return false;
    }
}
