package dev.totem.villagers.mixin.client;

import dev.totem.villagers.client.render.VillagerWorkAnimationRenderState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerModel.class)
abstract class VillagerModelWorkAnimationMixin {
    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void totemVillagers$animateWorkingHands(VillagerRenderState state, CallbackInfo callback) {
        ModelPart arms = ((VillagerModel) (Object) this).root().getChild("arms");
        arms.visible = !((VillagerWorkAnimationRenderState) state).totemVillagers$usesPlayerStyleArms();
    }
}
