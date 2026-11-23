package dev.totem.villagers.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.villagers.client.render.VillagerWorkAnimationRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
abstract class VillagerStatusLabelMixin {
    @Inject(method = "submit", at = @At("TAIL"))
    private void totemVillagers$submitStatus(EntityRenderState state, PoseStack poses, SubmitNodeCollector collector,
                                             CameraRenderState camera, CallbackInfo callback) {
        if (!(state instanceof VillagerWorkAnimationRenderState villager) || villager.totemVillagers$status() == null) return;
        double height = state.boundingBoxHeight + (state.nameTag == null ? 0.1 : 0.45);
        collector.submitNameTag(poses, new Vec3(0, height, 0), 0, villager.totemVillagers$status(),
                false, state.lightCoords, camera);
    }
}
