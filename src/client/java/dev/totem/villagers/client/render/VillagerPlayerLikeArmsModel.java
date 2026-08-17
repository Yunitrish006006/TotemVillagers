package dev.totem.villagers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemUseAnimation;

/** A separate pair of villager arms that can articulate like a player's arms. */
final class VillagerPlayerLikeArmsModel extends EntityModel<VillagerRenderState> {
    private static final float DEG_TO_RAD = Mth.PI / 180.0F;

    private final ModelPart rightArm;
    private final ModelPart leftArm;

    VillagerPlayerLikeArmsModel(ModelPart root) {
        super(root);
        rightArm = root.getChild("right_arm");
        leftArm = root.getChild("left_arm");
    }

    @Override
    public void setupAnim(VillagerRenderState state) {
        super.setupAnim(state);
        VillagerWorkAnimationRenderState extra = (VillagerWorkAnimationRenderState) state;
        float walk = state.walkAnimationPos * 0.6662F;
        float speed = Math.min(state.walkAnimationSpeed, 1.0F);
        rightArm.xRot = Mth.cos(walk + Mth.PI) * 0.7F * speed;
        leftArm.xRot = Mth.cos(walk) * 0.7F * speed;

        if (!extra.totemVillagers$rightHandItem().isEmpty()) {
            rightArm.xRot = rightArm.xRot * 0.5F - Mth.PI / 10.0F;
        }
        if (!extra.totemVillagers$leftHandItem().isEmpty()) {
            leftArm.xRot = leftArm.xRot * 0.5F - Mth.PI / 10.0F;
        }

        if (extra.totemVillagers$isUsingItem()) {
            applyUsePose(state, extra);
        } else if (extra.totemVillagers$workAnimationProgress() > 0.0F) {
            applyWorkSwing(extra.totemVillagers$attackArm(), extra.totemVillagers$workAnimationProgress());
        }
    }

    void translateToHand(HumanoidArm arm, PoseStack poses) {
        root.translateAndRotate(poses);
        arm(arm).translateAndRotate(poses);
    }

    private void applyWorkSwing(HumanoidArm arm, float progress) {
        ModelPart workingArm = arm(arm);
        float side = side(arm);
        float stroke = Mth.sin(progress * Mth.PI);
        float followThrough = Mth.sin((1.0F - (1.0F - progress) * (1.0F - progress)) * Mth.PI);
        workingArm.xRot -= stroke * 1.2F + followThrough * 0.65F;
        workingArm.yRot += side * (0.12F + stroke * 0.25F);
        workingArm.zRot += side * stroke * 0.22F;
    }

    private void applyUsePose(VillagerRenderState state, VillagerWorkAnimationRenderState extra) {
        HumanoidArm useArm = extra.totemVillagers$useArm();
        ModelPart used = arm(useArm);
        ModelPart other = arm(useArm.getOpposite());
        float side = side(useArm);
        float headX = state.xRot * DEG_TO_RAD;
        float headY = state.yRot * DEG_TO_RAD;
        float useTicks = extra.totemVillagers$useTicks();
        ItemUseAnimation animation = extra.totemVillagers$useAnimation();

        if (animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK) {
            used.xRot = -1.32F + headX * 0.35F + Mth.cos(useTicks * 0.55F) * 0.08F;
            used.yRot = headY + side * 0.28F;
            used.zRot = side * 0.08F;
        } else if (animation == ItemUseAnimation.BLOCK) {
            used.xRot = -0.95F + headX * 0.45F;
            used.yRot = headY + side * 0.58F;
            used.zRot = side * 0.12F;
        } else if (animation == ItemUseAnimation.BOW) {
            used.xRot = -Mth.HALF_PI + headX;
            used.yRot = headY + side * 0.10F;
            other.xRot = -Mth.HALF_PI + headX;
            other.yRot = headY - side * 0.48F;
            other.zRot = -side * 0.12F;
        } else if (animation == ItemUseAnimation.CROSSBOW) {
            float charge = Mth.clamp(useTicks / extra.totemVillagers$useDuration(), 0.0F, 1.0F);
            used.xRot = -1.25F;
            used.yRot = side * (0.75F - charge * 0.45F);
            other.xRot = -1.45F;
            other.yRot = -side * (0.75F - charge * 0.25F);
        } else if (animation == ItemUseAnimation.TRIDENT || animation == ItemUseAnimation.SPEAR) {
            used.xRot = headX - Mth.PI;
            used.yRot = headY;
        } else if (animation == ItemUseAnimation.SPYGLASS) {
            used.xRot = Mth.clamp(headX - 1.92F, -2.4F, 3.3F);
            used.yRot = headY + side * 0.26F;
        } else if (animation == ItemUseAnimation.TOOT_HORN) {
            used.xRot = Mth.clamp(headX, -1.2F, 1.2F) - 1.48F;
            used.yRot = headY + side * 0.52F;
        } else if (animation == ItemUseAnimation.BRUSH) {
            used.xRot = -0.85F + Mth.sin(useTicks * 0.6F) * 0.25F;
            used.yRot = headY + side * 0.25F;
        } else if (animation == ItemUseAnimation.BUNDLE) {
            used.xRot = -1.05F;
            used.yRot = side * 0.32F;
            other.xRot = -0.75F;
            other.yRot = -side * 0.22F;
        }
    }

    private ModelPart arm(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? rightArm : leftArm;
    }

    private static float side(HumanoidArm arm) {
        return arm == HumanoidArm.RIGHT ? -1.0F : 1.0F;
    }
}
