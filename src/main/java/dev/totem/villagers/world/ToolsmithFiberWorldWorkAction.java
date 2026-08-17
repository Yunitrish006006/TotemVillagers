package dev.totem.villagers.world;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Clips one real fibre plant with real shears and credits only its live loot-table drops. */
public final class ToolsmithFiberWorldWorkAction {
    private static final double WORK_REACH_SQUARED = 4.0D * 4.0D;

    public boolean complete(ServerLevel level, Villager toolsmith, BlockPos target,
                            TagKey<Block> eligiblePlants, TagKey<Item> plantFibres,
                            VillagerWorkInventory inventory) {
        if (!isToolsmith(toolsmith) || !level.isLoaded(target)
                || toolsmith.distanceToSqr(Vec3.atCenterOf(target)) > WORK_REACH_SQUARED
                || !level.getBlockState(target).is(eligiblePlants)
                || !WorldWorkPermissions.mayWork(level, toolsmith, target)) {
            return false;
        }
        BlockState original = level.getBlockState(target);
        boolean renewableVineClipping = original.is(Blocks.VINE)
                && level.isLoaded(target.above()) && level.getBlockState(target.above()).is(Blocks.VINE);
        List<BlockPos> harvested = harvestedPositions(level, toolsmith, target, original);
        if (harvested.isEmpty()) {
            return false;
        }
        ItemStack shears = bestShears(inventory.snapshot()).orElse(null);
        if (shears == null) {
            return false;
        }
        List<ItemStack> drops = Block.getDrops(original, level, target, null, toolsmith, shears).stream()
                .filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        if (drops.stream().noneMatch(stack -> stack.is(plantFibres))) {
            return false;
        }
        var reservation = inventory.reserveExactMatchingItem(shears).orElse(null);
        if (reservation == null) {
            return false;
        }
        List<ItemStack> returned = new ArrayList<>(drops);
        ItemStack wornShears = wearOnce(shears);
        if (!wornShears.isEmpty()) {
            returned.add(wornShears);
        }
        if (!inventory.canInsertAllExact(returned)) {
            reservation.rollback();
            return false;
        }
        Map<BlockPos, BlockState> captured = new LinkedHashMap<>();
        harvested.forEach(position -> captured.put(position, level.getBlockState(position)));
        for (BlockPos position : harvested) {
            if (!level.getBlockState(position).isAir()
                    && !level.destroyBlock(position, false, toolsmith, 512)) {
                restore(level, captured);
                reservation.rollback();
                return false;
            }
        }
        if (renewableVineClipping) {
            level.setBlock(target, original, 3);
            if (!level.getBlockState(target).equals(original)) {
                restore(level, captured);
                reservation.rollback();
                return false;
            }
        }
        if (!reservation.commitWithReturns(returned)) {
            restore(level, captured);
            reservation.rollback();
            return false;
        }
        toolsmith.swing(InteractionHand.MAIN_HAND);
        toolsmith.playWorkSound();
        return true;
    }

    /** Rejects an upper half as a target and captures both halves so tall plants cannot be duplicated or orphaned. */
    private static List<BlockPos> harvestedPositions(ServerLevel level, Villager toolsmith,
                                                     BlockPos target, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return List.of(target);
        }
        if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
            return List.of();
        }
        BlockPos upper = target.above();
        if (!level.isLoaded(upper) || !level.getBlockState(upper).is(state.getBlock())
                || !level.getBlockState(upper).hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || level.getBlockState(upper).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER
                || !WorldWorkPermissions.mayWork(level, toolsmith, upper)) {
            return List.of();
        }
        return List.of(target, upper);
    }

    private static Optional<ItemStack> bestShears(List<ItemStack> inventory) {
        return inventory.stream().filter(stack -> stack.is(Items.SHEARS))
                .map(stack -> stack.copyWithCount(1))
                .min(Comparator.comparingInt(ItemStack::getDamageValue));
    }

    /** One successfully cut plant consumes one durability; the broken shears are not returned. */
    private static ItemStack wearOnce(ItemStack shears) {
        ItemStack worn = shears.copy();
        if (!worn.isDamageableItem()) {
            return worn;
        }
        if (worn.getDamageValue() + 1 >= worn.getMaxDamage()) {
            return ItemStack.EMPTY;
        }
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn;
    }

    private static boolean isToolsmith(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:toolsmith".equals(id.toString());
    }

    private static void restore(ServerLevel level, Map<BlockPos, BlockState> captured) {
        captured.forEach((position, state) -> level.setBlock(position, state, 3));
    }
}
