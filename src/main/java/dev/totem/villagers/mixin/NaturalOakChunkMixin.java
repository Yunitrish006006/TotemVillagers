package dev.totem.villagers.mixin;

import dev.totem.villagers.world.NaturalOakTimber;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LevelChunk.class, ProtoChunk.class})
abstract class NaturalOakChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void totemVillagers$forgetEditedTrunk(BlockPos pos, BlockState state, int flags,
                                                   CallbackInfoReturnable<BlockState> result) {
        BlockState old = result.getReturnValue();
        if (old != null && old.is(Blocks.STONE) && !state.is(Blocks.STONE))
            dev.totem.villagers.world.NaturalStoneFaces.forget((ChunkAccess)(Object)this, pos);
        if (old != null && (old.is(Blocks.SMITHING_TABLE) || old.is(Blocks.LECTERN)) && !state.is(old.getBlock()))
            dev.totem.villagers.runtime.GuardEquipmentOrders.removedTable((ChunkAccess)(Object)this, pos);
        if (old != null && old != state && old.is(Blocks.OAK_LOG)) {
            NaturalOakTimber.forgetChangedLog((ChunkAccess) (Object) this, pos);
        } else if (old != null && old.is(Blocks.OAK_LEAVES) && (!state.is(Blocks.OAK_LEAVES)
                || old.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                    != state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT))) {
            NaturalOakTimber.forgetChangedCrown((ChunkAccess) (Object) this, pos);
        }
    }
}
