package dev.totem.villagers.client.render;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemUseAnimation;

/** Extra per-frame state for the villager's player-style arms and held items. */
public interface VillagerWorkAnimationRenderState {
    ItemStackRenderState totemVillagers$rightHandItem();

    ItemStackRenderState totemVillagers$leftHandItem();

    boolean totemVillagers$usesPlayerStyleArms();

    float totemVillagers$workAnimationProgress();

    HumanoidArm totemVillagers$attackArm();

    boolean totemVillagers$isUsingItem();

    HumanoidArm totemVillagers$useArm();

    ItemUseAnimation totemVillagers$useAnimation();

    float totemVillagers$useTicks();

    float totemVillagers$useDuration();

    void totemVillagers$setPlayerStyleArmState(boolean enabled, float workProgress,
                                                HumanoidArm attackArm, boolean usingItem,
                                                HumanoidArm useArm, ItemUseAnimation useAnimation,
                                                float useTicks, float useDuration);
}
