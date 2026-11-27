package dev.totem.villagers.world;

import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worker.WorkZone;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stone on an exposed terrain surface before features and player edits. A mined or replaced cell loses its record. */
public final class NaturalStoneFaces {
    private static final AttachmentType<List<BlockPos>> ROCK = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:natural_stone_faces"), BlockPos.CODEC.listOf(0, 256));

    public static void register() {}

    public static void recordSurface(ChunkAccess chunk) {
        var faces = new ArrayList<BlockPos>();
        var origin = chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var top = new BlockPos(origin.getMinBlockX() + x, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z),
                    origin.getMinBlockZ() + z);
            if (chunk.getBlockState(top).is(Blocks.STONE)) {
                faces.add(top);
                continue;
            }
            // At most one face per column. Only inspect a shallow band at a height discontinuity.
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                var side = top.relative(dir);
                if (!chunk.getPos().contains(side)) continue;
                int floor = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, side.getX() & 15, side.getZ() & 15);
                if (floor >= top.getY() - 1 || floor < top.getY() - 8) continue;
                var face = new BlockPos(top.getX(), floor + 1, top.getZ());
                if (chunk.getBlockState(face).is(Blocks.STONE)) {
                    faces.add(face);
                    break;
                }
            }
        }
        if (!faces.isEmpty()) ((AttachmentTarget)chunk).setAttached(ROCK, List.copyOf(faces));
    }

    public static List<BlockPos> indexed(ChunkAccess chunk) {
        return ((AttachmentTarget)chunk).getAttachedOrElse(ROCK, List.of());
    }

    public static boolean wildFace(ServerLevel level, Villager worker, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !indexed(chunk).contains(pos) || !chunk.getBlockState(pos).is(Blocks.STONE)
                || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, pos)) return false;
        if (!WorldWorkPermissions.clearOfStructures(level, pos)) return false;
        for (var d : Direction.values()) {
            var side = pos.relative(d);
            if (!level.isLoaded(side)) return false;
            var neighbor = level.getBlockState(side);
            if (!neighbor.getFluidState().isEmpty() || neighbor.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                    || !neighbor.isAir() && !neighbor.is(Blocks.STONE) && !neighbor.is(net.minecraft.tags.BlockTags.DIRT)
                    && !neighbor.is(Blocks.DEEPSLATE)) return false;
        }
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(pos.above())).isEmpty()) return false;
        return true;
    }

    public static void forget(ChunkAccess chunk, BlockPos pos) {
        var data = (AttachmentTarget)chunk;
        var old = data.getAttached(ROCK);
        if (old == null || !old.contains(pos)) return;
        var remaining = new ArrayList<>(old);
        remaining.remove(pos);
        if (remaining.isEmpty()) data.removeAttached(ROCK);
        else data.setAttached(ROCK, List.copyOf(remaining));
    }

    public static List<BlockPos> nearby(ServerLevel level, BlockPos home, BlockPos worker) {
        var faces = new ArrayList<BlockPos>();
        for (int x = (home.getX()-16)>>4; x <= (home.getX()+16)>>4; x++)
            for (int z = (home.getZ()-16)>>4; z <= (home.getZ()+16)>>4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) for (var p : ((AttachmentTarget)chunk).getAttachedOrElse(ROCK, List.of()))
                    if (p.distSqr(home) <= 256) faces.add(p);
            }
        faces.sort(Comparator.comparingDouble(worker::distSqr));
        return faces;
    }

    public static List<BlockPos> furnaces(ServerLevel level, BlockPos home, BlockPos worker) {
        var sites = new ArrayList<BlockPos>();
        for (int x = (home.getX()-18)>>4; x <= (home.getX()+18)>>4; x++)
            for (int z = (home.getZ()-18)>>4; z <= (home.getZ()+18)>>4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) for (var pos : chunk.getBlockEntities().keySet())
                    if (pos.distSqr(home) <= 18*18 && chunk.getBlockState(pos).is(Blocks.FURNACE)) sites.add(pos);
            }
        sites.sort(Comparator.comparingDouble(worker::distSqr));
        return sites;
    }

    public static boolean exhausted(ServerLevel level, WorkZone zone) {
        var low = zone.minimum();
        var high = zone.maximum();
        // Defer release when any part of the quarry is unloaded.
        for (int x = low.x() >> 4; x <= high.x() >> 4; x++) for (int z = low.z() >> 4; z <= high.z() >> 4; z++) {
            var chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) return false;
            for (var p : ((AttachmentTarget)chunk).getAttachedOrElse(ROCK, List.of()))
                if (p.getX() >= low.x() && p.getX() <= high.x() && p.getZ() >= low.z() && p.getZ() <= high.z()
                        && p.getY() >= low.y() && p.getY() <= high.y() && chunk.getBlockState(p).is(Blocks.STONE)) return false;
        }
        return true;
    }

    public static boolean usable(ServerLevel level, Villager worker, WorkZone zone, BlockPos pos) {
        if (!zone.ownerId().equals(worker.getUUID()) || !level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.STONE)
                || !level.getBlockState(pos.above()).isAir() || !level.canSeeSky(pos.above())
                || !WorldWorkPermissions.mayWork(level, worker, pos)) return false;
        var chunk = level.getChunkSource().getChunkNow(pos.getX()>>4, pos.getZ()>>4);
        if (chunk == null || !((AttachmentTarget)chunk).getAttachedOrElse(ROCK, List.of()).contains(pos)
                || WorkerAssignmentSavedData.forServer(level.getServer()).overlapsOtherZone(zone, pos)
                || ProductionParcel.get(level.getServer()).heldByAnother(level, pos, worker)
                || level.structureManager().getStructureWithPieceAt(pos, holder -> true).isValid()) return false;
        for (var direction : Direction.Plane.HORIZONTAL) {
            var side = pos.relative(direction);
            if (!level.isLoaded(side)) return false;
            var state = level.getBlockState(side);
            if (!state.isAir() && !state.is(Blocks.STONE) && !state.is(net.minecraft.tags.BlockTags.DIRT)
                    && !state.canBeReplaced()) return false;
        }
        return level.noCollision(new net.minecraft.world.phys.AABB(pos).move(0, 1, 0))
                && level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        new net.minecraft.world.phys.AABB(pos).move(0, 1, 0)).isEmpty();
    }

    private NaturalStoneFaces() {}
}
