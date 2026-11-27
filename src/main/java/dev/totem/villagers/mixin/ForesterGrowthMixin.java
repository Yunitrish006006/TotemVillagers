package dev.totem.villagers.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.totem.villagers.world.ForesterGrowthContext;
import dev.totem.villagers.world.NaturalWorkTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TreeGrower.class)
abstract class ForesterGrowthMixin {
    @WrapMethod(method = "growTree")
    private boolean totemVillagers$trackRegrowth(ServerLevel level, ChunkGenerator generator, BlockPos site,
                                                 BlockState state, RandomSource random, Operation<Boolean> original) {
        var chunk = level.getChunkAt(site);
        if (!NaturalWorkTrees.isPlanted(chunk, site)) return original.call(level, generator, site, state, random);
        var previous = ForesterGrowthContext.SITE.get();
        ForesterGrowthContext.SITE.set(GlobalPos.of(level.dimension(), site));
        try {
            return original.call(level, generator, site, state, random);
        } finally {
            if (previous == null) ForesterGrowthContext.SITE.remove();
            else ForesterGrowthContext.SITE.set(previous);
            if (level.getBlockState(site).is(state.getBlock())) NaturalWorkTrees.planted(chunk, site);
        }
    }
}
