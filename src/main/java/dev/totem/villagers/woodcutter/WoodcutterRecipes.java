package dev.totem.villagers.woodcutter;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers Woodcutter work directly from live player crafting recipes.
 *
 * <p>A machine recipe may consume only one homogeneous stack of a tagged wood
 * input. Its exact item count and shaped layout are still checked by the
 * original crafting recipe; this is what makes the station a compact processor
 * rather than an alternate source of free slabs, stairs, or sticks.</p>
 */
public final class WoodcutterRecipes {
    public static final TagKey<Item> INPUTS = TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            Identifier.fromNamespaceAndPath("totem", "woodcutter_inputs"));
    public static final TagKey<Item> OUTPUTS = TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            Identifier.fromNamespaceAndPath("totem", "woodcutter_outputs"));

    private WoodcutterRecipes() {
    }

    public static boolean acceptsInput(ItemStack stack) {
        return !stack.isEmpty() && stack.is(INPUTS);
    }

    /**
     * Only server recipe managers expose full recipes. The menu synchronises its
     * selected output and recipe count to clients, so clients never need to
     * guess from an incomplete local recipe book.
     */
    public static List<Match> matching(Level level, ItemStack stack) {
        if (!acceptsInput(stack) || !(level.recipeAccess() instanceof RecipeManager recipes)) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }
            findUniformInput(recipe, level, stack).ifPresent(uniformInput -> {
                ItemStack output = recipe.assemble(uniformInput.input());
                if (!output.isEmpty() && output.is(OUTPUTS) && noRemainders(recipe, uniformInput.input())) {
                    matches.add(new Match(holder.id().identifier(), uniformInput.ingredientCount(), uniformInput.input(), output.copy()));
                }
            });
        }
        matches.sort(Comparator.comparing(match -> match.id().toString()));
        return List.copyOf(matches);
    }

    private static java.util.Optional<UniformInput> findUniformInput(CraftingRecipe recipe, Level level, ItemStack material) {
        ItemStack one = material.copyWithCount(1);
        for (int mask = 1; mask < (1 << 9); mask++) {
            int ingredientCount = Integer.bitCount(mask);
            if (ingredientCount > material.getCount()) {
                continue;
            }
            List<ItemStack> grid = new ArrayList<>(9);
            for (int slot = 0; slot < 9; slot++) {
                grid.add((mask & (1 << slot)) == 0 ? ItemStack.EMPTY : one.copy());
            }
            CraftingInput input = CraftingInput.of(3, 3, grid);
            if (recipe.matches(input, level)) {
                return java.util.Optional.of(new UniformInput(input, ingredientCount));
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean noRemainders(CraftingRecipe recipe, CraftingInput input) {
        NonNullList<ItemStack> remaining = recipe.getRemainingItems(input);
        return remaining.stream().allMatch(ItemStack::isEmpty);
    }

    public record Match(Identifier id, int inputCount, CraftingInput input, ItemStack output) {
        public Match {
            if (inputCount < 1 || inputCount > 9) {
                throw new IllegalArgumentException("Woodcutter input count must be between 1 and 9");
            }
            output = output.copy();
        }
    }

    private record UniformInput(CraftingInput input, int ingredientCount) {
    }
}
