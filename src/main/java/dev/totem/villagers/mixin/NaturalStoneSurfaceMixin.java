package dev.totem.villagers.mixin;

import dev.totem.villagers.world.NaturalStoneFaces;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
abstract class NaturalStoneSurfaceMixin {
    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void totemVillagers$recordRock(ChunkAccess chunk, net.minecraft.world.level.levelgen.NoiseChunk noise,
            net.minecraft.world.level.levelgen.RandomState random, net.minecraft.world.level.biome.BiomeManager biomes,
            java.util.Set<?> possibleBiomes, net.minecraft.world.level.levelgen.material.rule.MaterialRule rule, CallbackInfo ci) {
        NaturalStoneFaces.recordSurface(chunk);
    }
}
