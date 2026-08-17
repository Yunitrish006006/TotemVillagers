package dev.totem.villagers.mixin.client;

import dev.totem.villagers.client.render.VillagerWorkAnimationRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VillagerRenderState.class)
abstract class VillagerRenderStateWorkAnimationMixin implements VillagerWorkAnimationRenderState {
    @Unique
    private final ItemStackRenderState totemVillagers$rightHandItem = new ItemStackRenderState();
    @Unique
    private final ItemStackRenderState totemVillagers$leftHandItem = new ItemStackRenderState();
    @Unique
    private boolean totemVillagers$usesPlayerStyleArms;
    @Unique
    private float totemVillagers$workAnimationProgress;
    @Unique
    private HumanoidArm totemVillagers$attackArm = HumanoidArm.RIGHT;
    @Unique
    private boolean totemVillagers$isUsingItem;
    @Unique
    private HumanoidArm totemVillagers$useArm = HumanoidArm.RIGHT;
    @Unique
    private ItemUseAnimation totemVillagers$useAnimation = ItemUseAnimation.NONE;
    @Unique
    private float totemVillagers$useTicks;
    @Unique
    private float totemVillagers$useDuration = 1.0F;

    @Override
    public ItemStackRenderState totemVillagers$rightHandItem() {
        return totemVillagers$rightHandItem;
    }

    @Override
    public ItemStackRenderState totemVillagers$leftHandItem() {
        return totemVillagers$leftHandItem;
    }

    @Override
    public boolean totemVillagers$usesPlayerStyleArms() {
        return totemVillagers$usesPlayerStyleArms;
    }

    @Override
    public float totemVillagers$workAnimationProgress() {
        return totemVillagers$workAnimationProgress;
    }

    @Override
    public HumanoidArm totemVillagers$attackArm() {
        return totemVillagers$attackArm;
    }

    @Override
    public boolean totemVillagers$isUsingItem() {
        return totemVillagers$isUsingItem;
    }

    @Override
    public HumanoidArm totemVillagers$useArm() {
        return totemVillagers$useArm;
    }

    @Override
    public ItemUseAnimation totemVillagers$useAnimation() {
        return totemVillagers$useAnimation;
    }

    @Override
    public float totemVillagers$useTicks() {
        return totemVillagers$useTicks;
    }

    @Override
    public float totemVillagers$useDuration() {
        return totemVillagers$useDuration;
    }

    @Override
    public void totemVillagers$setPlayerStyleArmState(boolean enabled, float workProgress,
                                                       HumanoidArm attackArm, boolean usingItem,
                                                       HumanoidArm useArm, ItemUseAnimation useAnimation,
                                                       float useTicks, float useDuration) {
        totemVillagers$usesPlayerStyleArms = enabled;
        totemVillagers$workAnimationProgress = workProgress;
        totemVillagers$attackArm = attackArm;
        totemVillagers$isUsingItem = usingItem;
        totemVillagers$useArm = useArm;
        totemVillagers$useAnimation = useAnimation;
        totemVillagers$useTicks = useTicks;
        totemVillagers$useDuration = Math.max(1.0F, useDuration);
    }
}
