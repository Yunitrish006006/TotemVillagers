package dev.totem.villagers.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.totem.villagers.world.NaturalOakTimber;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(TreeFeature.class)
abstract class NaturalOakFeatureMixin {
    @Inject(method = "place", at = @At("TAIL"))
    private void totemVillagers$recordTrunk(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                            BlockPos origin, CallbackInfoReturnable<Boolean> result,
                                            @Local(ordinal = 1) Set<BlockPos> trunk, @Local(ordinal = 2) Set<BlockPos> foliage) {
        if (!result.getReturnValue()) return;
        if (level instanceof WorldGenRegion region) {
            var chunk = region.getChunk(origin.getX() >> 4, origin.getZ() >> 4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, false);
            if (chunk != null) {
                dev.totem.villagers.world.NaturalWorkTrees.record(chunk, trunk, foliage);
                NaturalOakTimber.record(chunk, origin, trunk, foliage);
            }
        } else if (level instanceof net.minecraft.server.level.ServerLevel live && net.minecraft.core.GlobalPos.of(live.dimension(), origin).equals(dev.totem.villagers.world.ForesterGrowthContext.SITE.get())) {
            dev.totem.villagers.world.NaturalWorkTrees.record(live.getChunkAt(origin), trunk, foliage);
        } else if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(serverLevel.getServer())
                    .isPersonalForestRoot(serverLevel.dimension().identifier().toString(), origin)) {
            NaturalOakTimber.record(serverLevel.getChunkAt(origin), origin, trunk, foliage);
        }
    }
}
