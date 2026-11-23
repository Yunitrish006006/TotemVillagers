package dev.totem.villagers.builder;

import dev.totem.villagers.builder.VanillaVillageBlueprints.Blueprint;
import dev.totem.villagers.builder.VanillaVillageBlueprints.BlueprintBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Leave headroom over a staircase made of permanent blueprint blocks until the roof is in place. */
final class BuilderAccessCourse {
    static List<Long> plan(ServerLevel level, Blueprint blueprint) {
        var blocks = blueprint.blocks();
        int floor = blocks.getFirst().position().getY();
        int rise = blocks.getLast().position().getY() - floor - 1;
        if (rise < 3 || rise > 12) return List.of();
        Map<BlockPos, BlueprintBlock> solid = new HashMap<>();
        for (var block : blocks) {
            if (!level.isLoaded(block.position())) return List.of();
            if (block.state().isCollisionShapeFullBlock(level, block.position())) solid.put(block.position(), block);
        }
        var queue = new ArrayDeque<List<BlockPos>>();
        for (var block : blocks) {
            var p = block.position();
            if (p.getY() != floor || !solid.containsKey(p)) continue;
            for (var direction : Direction.Plane.HORIZONTAL) {
                var approach = p.relative(direction);
                if (!solid.containsKey(approach) && level.isLoaded(approach) && level.getBlockState(approach).isAir()
                        && level.getBlockState(approach.below()).isFaceSturdy(level, approach.below(), Direction.UP)) {
                    queue.add(List.of(p));
                    break;
                }
            }
        }
        for (int explored = 0; !queue.isEmpty() && explored < 8192; explored++) {
            var course = queue.remove();
            if (course.size() == rise) {
                var later = new LinkedHashSet<Long>();
                for (var step : course.reversed()) {
                    for (int dy = 1; dy <= 3; dy++) later.add(step.above(dy).asLong());
                }
                var positions = new HashSet<Long>();
                blocks.forEach(b -> positions.add(b.position().asLong()));
                later.retainAll(positions);
                // Finish the entire roof before filling any lower riser or leaving it to buy more stone.
                var byHeight = later.stream().sorted(Comparator.comparingInt((Long pos) -> BlockPos.of(pos).getY()).reversed()).toList();
                later.clear();
                later.addAll(byHeight);
                // A torch mounted on a late wall follows its support. Keep the same ordering across reloads.
                for (var b : blocks) {
                    if (b.state().getBlock() instanceof net.minecraft.world.level.block.WallTorchBlock
                            && later.contains(b.position().relative(b.state().getValue(net.minecraft.world.level.block.WallTorchBlock.FACING)
                            .getOpposite()).asLong())) {
                        later.remove(b.position().asLong());
                        later.add(b.position().asLong());
                    }
                }
                return List.copyOf(later);
            }
            for (var direction : Direction.Plane.HORIZONTAL) {
                var next = course.getLast().relative(direction).above();
                if (!solid.containsKey(next) || course.stream().anyMatch(p -> p.getX()==next.getX() && p.getZ()==next.getZ())) continue;
                var extended = new ArrayList<>(course);
                extended.add(next);
                queue.add(extended);
            }
        }
        return List.of();
    }

    static Blueprint order(Blueprint blueprint, List<Long> later) {
        if (later.isEmpty()) return blueprint;
        var remaining = new LinkedHashMap<Long, BlueprintBlock>();
        blueprint.blocks().forEach(b -> remaining.put(b.position().asLong(), b));
        var finishing = new ArrayList<BlueprintBlock>();
        for (long position : later) {
            var block = remaining.remove(position);
            if (block != null) finishing.add(block);
        }
        var order = new ArrayList<>(remaining.values());
        order.addAll(finishing);
        return new Blueprint(blueprint.templateId(), order);
    }

    private BuilderAccessCourse() {}
}
