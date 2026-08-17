package dev.totem.villagers.world;

import dev.totem.villagers.work.WorkOrder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/** Server-side shearing whose produced wool is inserted into the worker's physical inventory. */
public final class ShepherdWorldWorkAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    public boolean complete(ServerLevel level, Villager shepherd, Sheep sheep, WorkOrder order) {
        DyeColor outputColour = outputColour(order).orElse(null);
        if (!"minecraft:shepherd".equals(order.professionId())
                || outputColour == null
                || !sheep.isAlive()
                || sheep.getColor() != outputColour
                || !sheep.readyForShearing()
                || shepherd.distanceToSqr(sheep) > WORK_REACH_SQUARED
                || !WorldWorkPermissions.mayWork(level, shepherd, sheep.blockPosition())) {
            return false;
        }
        if (!order.matchesOutput(new ItemStack(Blocks.WOOL.pick(outputColour).asItem(), order.output().count()), level.registryAccess())) {
            return false;
        }
        // setSheared performs the real flock-state transition without creating a second physical wool drop.
        sheep.setSheared(true);
        shepherd.playWorkSound();
        return true;
    }

    /** Resolves only canonical vanilla coloured-wool outputs; arbitrary items cannot shear a flock. */
    public static Optional<DyeColor> outputColour(WorkOrder order) {
        if (!"minecraft:shepherd".equals(order.professionId())) {
            return Optional.empty();
        }
        return DyeColor.VALUES.stream()
                .filter(colour -> ("minecraft:" + colour.getName() + "_wool").equals(order.output().itemId()))
                .findFirst();
    }
}
