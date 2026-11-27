package dev.totem.villagers.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.totem.villagers.runtime.VillageBellSchedule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.blockentity.state.BellRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BellRenderer.class)
abstract class BellScheduleLabelMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BellRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("TAIL"))
    private void showSchedule(BellRenderState state, PoseStack poses, SubmitNodeCollector collector,
                              CameraRenderState camera, CallbackInfo callback) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        var schedule = VillageBellSchedule.current(client.player);
        if (schedule == null || !schedule.bell().dimension().equals(client.level.dimension())
                || !schedule.bell().pos().equals(state.blockPos)
                || !state.blockPos.closerToCenterThan(camera.pos, VillageBellSchedule.RANGE + 1)) return;
        var hit = client.player.pick(VillageBellSchedule.RANGE, camera.cameraEntityPartialTicks, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)
                || block.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !block.getBlockPos().equals(state.blockPos)) return;
        var text = Component.translatable("bell.totem.villager.schedule",
                Component.translatableWithFallback("bell.totem.villager." + schedule.activity(), schedule.activity()));
        collector.submitNameTag(poses, new Vec3(.5, 1.05, .5), 0, text, false, state.lightCoords, camera);
    }
}
