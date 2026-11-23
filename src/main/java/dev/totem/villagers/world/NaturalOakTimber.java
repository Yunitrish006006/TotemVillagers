package dev.totem.villagers.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Positive provenance for simple oaks created by terrain generation. Old or altered trees are ineligible. */
public final class NaturalOakTimber {
    private static final int LIMIT = 16;
    private record Trunk(BlockPos base, int height, boolean naturalCrown) {
        static final Codec<Trunk> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("base").forGetter(Trunk::base),
                Codec.intRange(4, 7).fieldOf("height").forGetter(Trunk::height),
                Codec.BOOL.optionalFieldOf("natural_crown", false).forGetter(Trunk::naturalCrown)
        ).apply(i, Trunk::new));

        boolean contains(BlockPos pos) {
            return pos.getX() == base.getX() && pos.getZ() == base.getZ()
                    && pos.getY() >= base.getY() && pos.getY() < base.getY() + height;
        }
    }

    private static final AttachmentType<List<Trunk>> TRUNKS = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:natural_oaks"), Trunk.CODEC.listOf(0, LIMIT));

    private NaturalOakTimber() {}

    public static void register() {
        // Initialize the attachment type on startup, before chunk decoding or parallel feature placement.
    }

    public static void record(ChunkAccess chunk, BlockPos base, Set<BlockPos> placed, Set<BlockPos> foliage) {
        // StraightTrunkPlacer includes the soil it prepares below the first log in this placement set.
        int height = placed.size() - (placed.contains(base.below()) ? 1 : 0);
        if (height < 4 || height > 7 || chunk.getPos().x() != (base.getX() >> 4)
                || chunk.getPos().z() != (base.getZ() >> 4)) return;
        for (int y = 0; y < height; y++) {
            BlockPos log = base.above(y);
            if (!placed.contains(log) || !chunk.getBlockState(log).is(Blocks.OAK_LOG)) return;
        }
        var crown = base.above(height);
        add(chunk, new Trunk(base.immutable(), height, foliage.contains(crown) && chunk.getBlockState(crown).is(Blocks.OAK_LEAVES)));
    }

    private static void add(ChunkAccess chunk, Trunk trunk) {
        var target = (AttachmentTarget) chunk;
        var old = target.getAttachedOrElse(TRUNKS, List.of());
        if (old.size() >= LIMIT || old.contains(trunk)) return;
        var next = new ArrayList<>(old);
        next.add(trunk);
        target.setAttached(TRUNKS, List.copyOf(next));
    }

    public static void forgetChangedLog(ChunkAccess chunk, BlockPos pos) {
        var target = (AttachmentTarget) chunk;
        var old = target.getAttached(TRUNKS);
        if (old == null || old.stream().noneMatch(trunk -> trunk.contains(pos))) return;
        var next = old.stream().filter(trunk -> !trunk.contains(pos)).toList();
        if (next.isEmpty()) target.removeAttached(TRUNKS);
        else target.setAttached(TRUNKS, next);
    }

    public static void forgetChangedCrown(ChunkAccess chunk, BlockPos pos) {
        var target = (AttachmentTarget) chunk;
        var old = target.getAttached(TRUNKS);
        if (old == null) return;
        for (int i = 0; i < old.size(); i++) {
            var tree = old.get(i);
            if (tree.naturalCrown() && tree.base().above(tree.height()).equals(pos)) {
                var next = new ArrayList<>(old);
                next.set(i, new Trunk(tree.base(), tree.height(), false));
                target.setAttached(TRUNKS, List.copyOf(next));
                return;
            }
        }
    }

    public static boolean gatherNear(ServerLevel level, Villager worker, BlockPos anchor, VillagerWorkInventory inventory) {
        return nearby(level, worker, anchor).filter(tree -> clearToFell(level, worker, tree)).limit(3)
                .anyMatch(tree -> WorldWorkNavigation.isWithinReach(worker, tree.base())
                        ? fell(level, worker, tree, inventory) : WorldWorkNavigation.moveToReach(level, worker, tree.base(), .5));
    }

    public static java.util.Optional<BlockPos> firstForestPlot(ServerLevel level, Villager worker, BlockPos home,
                                                                java.util.function.Predicate<BlockPos> usable) {
        return nearby(level, worker, home).filter(Trunk::naturalCrown).filter(tree -> clearToFell(level, worker, tree))
                .map(Trunk::base).filter(usable).filter(p -> WorldWorkNavigation.canReach(level, worker, p)).findFirst();
    }

    private static java.util.stream.Stream<Trunk> nearby(ServerLevel level, Villager worker, BlockPos anchor) {
        List<Trunk> nearby = new ArrayList<>();
        int radius = 16;
        for (int x = (anchor.getX() - radius) >> 4; x <= (anchor.getX() + radius) >> 4; x++) {
            for (int z = (anchor.getZ() - radius) >> 4; z <= (anchor.getZ() + radius) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) nearby.addAll(((AttachmentTarget) chunk).getAttachedOrElse(TRUNKS, List.of()));
            }
        }
        return nearby.stream().filter(tree -> tree.base().distSqr(anchor) <= radius * radius)
                .sorted(Comparator.comparingDouble(tree -> tree.base().distSqr(worker.blockPosition())));
    }

    public static boolean viableForestRoot(ServerLevel level, BlockPos root) {
        if (!level.isLoaded(root) || level.getBlockState(root).is(Blocks.OAK_SAPLING)) return true;
        var chunk = level.getChunkSource().getChunkNow(root.getX() >> 4, root.getZ() >> 4);
        return chunk == null || ((AttachmentTarget) chunk).getAttachedOrElse(TRUNKS, List.of()).stream()
                .anyMatch(tree -> tree.base().equals(root) && tree.naturalCrown());
    }

    static boolean recordedTree(ServerLevel level, BlockPos base, int height) {
        var chunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
        return chunk != null && ((AttachmentTarget)chunk).getAttachedOrElse(TRUNKS, List.of()).stream()
                .anyMatch(t -> t.base().equals(base) && t.height() == height && t.naturalCrown());
    }

    private static boolean clearToFell(ServerLevel level, Villager worker, Trunk tree) {
        if (!level.getBlockState(tree.base().above(tree.height())).is(Blocks.OAK_LEAVES)) return false;
        for (int y = 0; y < tree.height(); y++) {
            BlockPos pos = tree.base().above(y);
            if (!level.getBlockState(pos).is(Blocks.OAK_LOG) || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, pos)) return false;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos beside = pos.relative(dir);
                if (!level.isLoaded(beside)) return false;
                BlockState neighbor = level.getBlockState(beside);
                if (!neighbor.isAir() && !neighbor.is(BlockTags.LEAVES) && !neighbor.canBeReplaced()) return false;
            }
        }
        return true;
    }

    private static boolean fell(ServerLevel level, Villager worker, Trunk tree, VillagerWorkInventory inventory) {
        List<BlockState> states = new ArrayList<>();
        List<ItemStack> drops = new ArrayList<>();
        for (int y = 0; y < tree.height(); y++) {
            BlockPos pos = tree.base().above(y);
            BlockState state = level.getBlockState(pos);
            states.add(state);
            drops.addAll(Block.getDrops(state, level, pos, null, worker, ItemStack.EMPTY));
        }
        if (drops.isEmpty() || !inventory.canInsertAllExact(drops)) return false;
        var crown = tree.base().above(tree.height());
        if (tree.naturalCrown() && WorldWorkPermissions.mayUseHouseholdLand(level, worker, crown)) {
            var leaf = level.getBlockState(crown);
            if (leaf.is(Blocks.OAK_LEAVES)) {
                var withCrown = new ArrayList<>(drops);
                var foliageDrops = Block.getDrops(leaf, level, crown, null, worker, ItemStack.EMPTY);
                withCrown.addAll(foliageDrops);
                if (inventory.canInsertAllExact(withCrown)) {
                    LumberjackWorldWorkAction.addClipping(inventory, leaf, foliageDrops, withCrown);
                    drops = withCrown;
                    states.add(leaf);
                }
            }
        }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
        int removed = 0;
        while (removed < states.size() && level.setBlock(tree.base().above(removed), Blocks.AIR.defaultBlockState(), flags)) {
            removed++;
        }
        if (removed != states.size() || !inventory.insertAllExact(drops)) {
            boolean restored = true;
            for (int y = 0; y < removed; y++) restored &= level.setBlock(tree.base().above(y), states.get(y), flags);
            if (restored) add(level.getChunkAt(tree.base()), tree);
            return false;
        }
        for (int y = 0; y < states.size(); y++) {
            BlockPos pos = tree.base().above(y);
            level.updateNeighborsAt(pos, states.get(y).getBlock());
            Blocks.AIR.defaultBlockState().updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
        worker.playWorkSound();
        return true;
    }
}
