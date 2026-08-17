package dev.totem.villagers.mixin.client;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Provides the renderer's item model resolver to the Villager renderer extension. */
@Mixin(LivingEntityRenderer.class)
interface LivingEntityRendererItemModelResolverAccessor {
    @Accessor("itemModelResolver")
    ItemModelResolver totemVillagers$itemModelResolver();

    @Invoker("addLayer")
    boolean totemVillagers$addLayer(RenderLayer<?, ?> layer);
}
