package dev.totem.villagers.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Records actual feature placements. Never guess provenance from a building's shape. */
public final class NaturalWorkTrees {
    public record Tree(Block log, List<BlockPos> logs, BlockPos leaf) {
        static final Codec<Tree> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("log").forGetter(Tree::log),
                BlockPos.CODEC.listOf(2, 48).fieldOf("logs").forGetter(Tree::logs),
                BlockPos.CODEC.fieldOf("leaf").forGetter(Tree::leaf)).apply(i, Tree::new));
        public BlockPos base() { return logs.getFirst(); }
    }

    private static final AttachmentType<List<Tree>> TREES = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:work_trees"), Tree.CODEC.listOf(0, 24));
    private static final AttachmentType<List<BlockPos>> PLANTED = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:forester_saplings"), BlockPos.CODEC.listOf(0, 24));
    public static void planted(ChunkAccess chunk, BlockPos site) {
        var target = (AttachmentTarget)chunk;
        var old = target.getAttachedOrElse(PLANTED, List.of());
        if (old.size() >= 24 || old.contains(site)) return;
        var next = new ArrayList<>(old);
        next.add(site.immutable());
        target.setAttached(PLANTED, List.copyOf(next));
    }

    public static boolean isPlanted(ChunkAccess chunk, BlockPos site) {
        return ((AttachmentTarget)chunk).getAttachedOrElse(PLANTED, List.of()).contains(site);
    }

    public static void forgetSeed(ChunkAccess chunk, BlockPos site) {
        if (isPlanted(chunk, site)) {
            var t = (AttachmentTarget)chunk;
            t.setAttached(PLANTED, t.getAttachedOrElse(PLANTED, List.of()).stream().filter(p -> !p.equals(site)).toList());
        }
    }

    public static void register() {}
    private NaturalWorkTrees() {}

    public static List<Tree> trees(ChunkAccess chunk) {
        var data = (AttachmentTarget)chunk;
        var existing = data.getAttached(TREES);
        if (existing != null) return existing;
        var legacy = NaturalOakTimber.legacyTrees(chunk);
        if (!legacy.isEmpty()) data.setAttached(TREES, legacy);
        return legacy;
    }

    public static void record(ChunkAccess chunk, Set<BlockPos> placed, Set<BlockPos> foliage) {
        if (placed.size() > 50 || foliage.isEmpty()) return;
        var logs = new ArrayList<BlockPos>();
        Block species = null;
        for (var pos : placed) {
            // Keep all provenance and invalidation local to the owning chunk.
            if (!chunk.getPos().contains(pos)) return;
            var state = chunk.getBlockState(pos);
            if (!state.is(Blocks.OAK_LOG) && !state.is(Blocks.MANGROVE_LOG)) continue;
            if (species != null && species != state.getBlock()) return;
            species = state.getBlock();
            logs.add(pos.immutable());
        }
        if (logs.size() < 2 || logs.size() > 48) return;
        logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingLong(BlockPos::asLong));
        var top = logs.getLast();
        var leaves = species == Blocks.OAK_LOG ? Blocks.OAK_LEAVES : Blocks.MANGROVE_LEAVES;
        var leaf = foliage.stream().filter(chunk.getPos()::contains).filter(p -> chunk.getBlockState(p).is(leaves))
                .min(Comparator.<BlockPos>comparingInt(p -> {
                    var fruit = chunk.getBlockState(p.below());
                    return fruit.is(Blocks.MANGROVE_PROPAGULE)
                            && fruit.getValue(net.minecraft.world.level.block.MangrovePropaguleBlock.HANGING)
                            && fruit.getValue(net.minecraft.world.level.block.MangrovePropaguleBlock.AGE) == 4 ? 0 : 1;
                }).thenComparingDouble(top::distSqr)).orElse(null);
        if (leaf != null) add(chunk, new Tree(species, List.copyOf(logs), leaf.immutable()));
    }

    static void add(ChunkAccess chunk, Tree tree) {
        var old = trees(chunk);
        if (old.size() >= 24 || old.contains(tree)) return;
        var next = new ArrayList<>(old);
        next.add(tree);
        ((AttachmentTarget)chunk).setAttached(TREES, List.copyOf(next));
    }

    public static void forget(ChunkAccess chunk, BlockPos pos) {
        var old = ((AttachmentTarget)chunk).getAttached(TREES);
        if (old == null || old.stream().noneMatch(t -> t.leaf.equals(pos) || t.logs.contains(pos))) return;
        ((AttachmentTarget)chunk).setAttached(TREES, old.stream()
                .filter(t -> !t.leaf.equals(pos) && !t.logs.contains(pos)).toList());
    }

    public static Tree at(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : trees(chunk).stream().filter(t -> t.base().equals(pos)).findFirst().orElse(null);
    }
}
