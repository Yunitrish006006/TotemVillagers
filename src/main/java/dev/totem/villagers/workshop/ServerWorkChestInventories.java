package dev.totem.villagers.workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Resolves only a loaded block container and recovers a rare cancelled reservation as a visible item drop. */
public final class ServerWorkChestInventories {
    private ServerWorkChestInventories() {
    }

    public static ContainerWorkChestInventory forRegisteredChest(ServerLevel level, WorkChestKey key) {
        if (!level.dimension().identifier().toString().equals(key.dimensionId())) {
            throw new IllegalArgumentException("Work Chest belongs to another dimension");
        }
        BlockPos pos = BlockPos.of(key.packedBlockPosition());
        return new ContainerWorkChestInventory(key,
                () -> containerAt(level, pos),
                stack -> level.addFreshEntity(new ItemEntity(level, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, stack)));
    }

    public static Container containerAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }
}
