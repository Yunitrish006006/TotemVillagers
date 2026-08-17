package dev.totem.villagers.world;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

/**
 * Obtains a real result from Minecraft's fishing loot table and only credits a
 * Fisherman order after that catch passes the vanilla Campfire recipe. No fish
 * item is invented from the order identifier.
 */
public final class FishermanWorldWorkAction {
    private static final double FISH_REACH_SQUARED = 16.0D;

    public boolean complete(ServerLevel level, Villager fisherman, BlockPos water, WorkOrder order) {
        return attempt(level, fisherman, water, order).orderOutput().isPresent();
    }

    /**
     * Resolves exactly one vanilla cast. A matching fish is converted through the current Campfire recipe; useful
     * bycatch remains available to the owning runtime while economically irrelevant clutter is counted and ignored.
     */
    public FishingAttempt attempt(ServerLevel level, Villager fisherman, BlockPos water, WorkOrder order) {
        if (!validCast(level, fisherman, water, order)) {
            return FishingAttempt.EMPTY;
        }
        List<ItemStack> rolled = catches(level, fisherman, water);
        ItemStack orderOutput = ItemStack.EMPTY;
        List<ItemStack> bycatch = new ArrayList<>();
        int ignoredBycatch = 0;
        for (ItemStack caught : rolled) {
            ItemStack cooked = cookedCatch(level, caught, order);
            if (orderOutput.isEmpty() && !cooked.isEmpty()) {
                orderOutput = cooked;
            } else {
                ItemStack useful = cookedFoodCatch(level, caught);
                if (useful.isEmpty()) {
                    useful = caught;
                }
                if (retainableBycatch(useful)) {
                    bycatch.add(useful.copy());
                } else if (!caught.isEmpty()) {
                    ignoredBycatch += caught.getCount();
                }
            }
        }
        if (orderOutput.isEmpty() && bycatch.isEmpty() && ignoredBycatch == 0) {
            return FishingAttempt.EMPTY;
        }
        level.sendParticles(ParticleTypes.FISHING, water.getX() + .5D, water.getY() + 1.0D, water.getZ() + .5D,
                6, .2D, .05D, .2D, .01D);
        fisherman.playWorkSound();
        return new FishingAttempt(Optional.ofNullable(orderOutput.isEmpty() ? null : orderOutput.copy()),
                List.copyOf(bycatch), ignoredBycatch);
    }

    /**
     * Captures one actual fishing-loot fish in the supplied empty bucket.  The
     * caller owns the bucket reservation, so a failed catch never consumes it.
     */
    public Optional<ItemStack> captureBucketedFish(ServerLevel level, Villager fisherman, BlockPos water, WorkOrder order) {
        if (!"minecraft:fisherman".equals(order.professionId())
                || fisherman.distanceToSqr(Vec3.atCenterOf(water)) > FISH_REACH_SQUARED
                || !WorldWorkPermissions.mayWork(level, fisherman, water)) {
            return Optional.empty();
        }
        Optional<ItemStack> captured = catches(level, fisherman, water).stream()
                .map(caught -> bucketForCatch(caught, order))
                .flatMap(Optional::stream)
                .findFirst();
        if (captured.isEmpty()) {
            return Optional.empty();
        }
        level.sendParticles(ParticleTypes.FISHING, water.getX() + .5D, water.getY() + 1.0D, water.getZ() + .5D,
                6, .2D, .05D, .2D, .01D);
        fisherman.playWorkSound();
        return captured;
    }

    /** Maps a real fish catch to the only currently work-backed fish-bucket trade. */
    public static Optional<ItemStack> bucketForCatch(ItemStack caught, WorkOrder order) {
        if (caught.is(Items.COD) && caught.getCount() == 1
                && "minecraft:cod_bucket".equals(order.output().itemId()) && order.output().count() == 1) {
            return Optional.of(new ItemStack(Items.COD_BUCKET));
        }
        return Optional.empty();
    }

    /** Uses a one-cast vanilla fishing loot context with the villager as the acting entity. */
    public static List<ItemStack> catches(ServerLevel level, Villager fisherman, BlockPos water) {
        LootParams parameters = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD))
                .withParameter(LootContextParams.THIS_ENTITY, fisherman)
                .withLuck(0.0F)
                .create(LootContextParamSets.FISHING);
        LootTable fishing = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        return List.copyOf(fishing.getRandomItems(parameters));
    }

    /** Rechecks the exact campfire recipe and expected order output for one real catch. */
    public static boolean matchesCookedCatch(ServerLevel level, ItemStack caught, WorkOrder order) {
        return !cookedCatch(level, caught, order).isEmpty();
    }

    /** Keeps only food, reusable rods, or pristine inputs consumed by a currently loaded villager work order. */
    public static boolean retainableBycatch(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.FISHING_ROD) || VillagerNutrition.nutrition(stack) > 0) {
            return true;
        }
        if (stack.isDamageableItem() && stack.isDamaged()) {
            return false;
        }
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId != null && WorkOrderDefinitions.catalog().snapshot().values().stream()
                .flatMap(workOrder -> workOrder.requiredInputs().stream())
                .anyMatch(input -> input.itemId().equals(itemId.toString()));
    }

    private static ItemStack cookedCatch(ServerLevel level, ItemStack caught, WorkOrder order) {
        if (caught.isEmpty() || caught.getCount() != 1) {
            return ItemStack.EMPTY;
        }
        SingleRecipeInput input = new SingleRecipeInput(caught.copy());
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return ItemStack.EMPTY;
        }
        ItemStack cooked = recipe.value().assemble(input);
        return order.matchesOutput(cooked, level.registryAccess()) ? cooked.copy() : ItemStack.EMPTY;
    }

    /** A salmon caught during a cod order (or vice versa) is still cooked instead of accumulating as raw waste. */
    private static ItemStack cookedFoodCatch(ServerLevel level, ItemStack caught) {
        if (caught.isEmpty() || caught.getCount() != 1) {
            return ItemStack.EMPTY;
        }
        SingleRecipeInput input = new SingleRecipeInput(caught.copy());
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return ItemStack.EMPTY;
        }
        ItemStack cooked = recipe.value().assemble(input);
        return VillagerNutrition.nutrition(cooked) > 0 ? cooked.copy() : ItemStack.EMPTY;
    }

    private static boolean validCast(ServerLevel level, Villager fisherman, BlockPos water, WorkOrder order) {
        return "minecraft:fisherman".equals(order.professionId())
                && fisherman.distanceToSqr(Vec3.atCenterOf(water)) <= FISH_REACH_SQUARED
                && WorldWorkPermissions.mayWork(level, fisherman, water);
    }

    public record FishingAttempt(Optional<ItemStack> orderOutput, List<ItemStack> bycatch, int ignoredBycatch) {
        private static final FishingAttempt EMPTY = new FishingAttempt(Optional.empty(), List.of(), 0);

        public FishingAttempt {
            if (ignoredBycatch < 0) {
                throw new IllegalArgumentException("ignoredBycatch cannot be negative");
            }
            orderOutput = orderOutput.map(ItemStack::copy);
            bycatch = bycatch.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        }

        public boolean caughtAnything() {
            return orderOutput.isPresent() || !bycatch.isEmpty() || ignoredBycatch > 0;
        }
    }
}
