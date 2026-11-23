package dev.totem.villagers.world;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.SwingAnimation;
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
        ItemStack shears = ShearUse.best(inventory.snapshot()).orElse(null);
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
        ItemStack wornShears = ShearUse.wearOnce(shears);
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
        toolsmith.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        toolsmith.playWorkSound();
        return true;
    }

    /** Hand-gather one grass plant when no shears are available. Both halves share a single credit. */
    public boolean gatherGrass(ServerLevel level, Villager worker, BlockPos target, TagKey<Item> fibres,
                               VillagerWorkInventory inventory) {
        if (!level.isLoaded(target) || worker.distanceToSqr(Vec3.atCenterOf(target)) > WORK_REACH_SQUARED
                || !WorldWorkPermissions.mayWork(level, worker, target)) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        if (!state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.TALL_GRASS)) {
            return false;
        }
        ItemStack clipping = new ItemStack(state.getBlock().asItem());
        if (!clipping.is(fibres)) {
            return false;
        }
        List<BlockPos> positions = harvestedPositions(level, worker, target, state);
        if (positions.isEmpty()) {
            return false;
        }
        List<ItemStack> returns = new ArrayList<>(Block.getDrops(state, level, target, null, worker, ItemStack.EMPTY));
        if (returns.stream().noneMatch(stack -> stack.is(clipping.getItem()))) {
            returns.add(clipping);
        }
        if (!inventory.canInsertAllExact(returns)) {
            return false;
        }
        Map<BlockPos, BlockState> captured = new LinkedHashMap<>();
        positions.forEach(pos -> captured.put(pos, level.getBlockState(pos)));
        // Remove the upper half first without neighbour drops, then notify once the plant is gone.
        for (BlockPos pos : positions.reversed()) {
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS)) {
                restore(level, captured);
                return false;
            }
        }
        if (!inventory.insertAllExact(returns)) {
            restore(level, captured);
            return false;
        }
        positions.forEach(pos -> level.updateNeighborsAt(pos, state.getBlock()));
        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
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



    private static boolean isToolsmith(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:toolsmith".equals(id.toString());
    }

    private static void restore(ServerLevel level, Map<BlockPos, BlockState> captured) {
        captured.forEach((position, state) -> level.setBlock(position, state, 3));
    }
}
