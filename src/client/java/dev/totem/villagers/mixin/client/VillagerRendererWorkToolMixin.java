package dev.totem.villagers.mixin.client;

import dev.totem.villagers.client.render.VillagerPlayerLikeItemLayer;
import dev.totem.villagers.client.render.VillagerWorkAnimationRenderState;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.CrossbowItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the crossed-arms item presentation with articulated, player-style hands. */
@Mixin(VillagerRenderer.class)
abstract class VillagerRendererWorkToolMixin {
    private static ItemStack minerPickaxe;
    private static ItemStack lumberjackAxe;
    private static ItemStack builderShovel;
    private static ItemStack guardSword;
    private static ItemStack farmerHoe;
    private static ItemStack toolsmithShears;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void totemVillagers$addPlayerLikeItemLayer(EntityRendererProvider.Context context,
                                                        CallbackInfo callback) {
        VillagerRenderer renderer = (VillagerRenderer) (Object) this;
        ((LivingEntityRendererItemModelResolverAccessor) (Object) this).totemVillagers$addLayer(
                new VillagerPlayerLikeItemLayer(renderer, context.bakeLayer(VillagerPlayerLikeItemLayer.MODEL_LAYER),
                        context.getResourceManager()));
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void totemVillagers$addWorkTool(Villager villager, VillagerRenderState state,
                                             float tickProgress, CallbackInfo callback) {
        VillagerWorkAnimationRenderState extra = (VillagerWorkAnimationRenderState) state;
        extra.totemVillagers$rightHandItem().clear();
        extra.totemVillagers$leftHandItem().clear();
        float workProgress = villager.getAttackAnim(tickProgress);
        HumanoidArm attackArm = armForHand(villager, villager.swingingArm);
        if (villager.isBaby()) {
            extra.totemVillagers$setPlayerStyleArmState(false, workProgress, attackArm,
                    false, villager.getMainArm(), ItemUseAnimation.NONE, 0.0F, 1.0F);
            return;
        }

        ItemStack rightStack = villager.getItemHeldByArm(HumanoidArm.RIGHT);
        ItemStack leftStack = villager.getItemHeldByArm(HumanoidArm.LEFT);
        if (rightStack.isEmpty() && leftStack.isEmpty()) {
            ItemStack tool = toolFor(villager, workProgress);
            if (tool != null) {
                if (villager.getMainArm() == HumanoidArm.RIGHT) {
                    rightStack = tool;
                } else {
                    leftStack = tool;
                }
            }
        }

        boolean enabled = !rightStack.isEmpty() || !leftStack.isEmpty();
        boolean usingItem = enabled && villager.isUsingItem() && !villager.getUseItem().isEmpty();
        HumanoidArm useArm = usingItem
                ? armForHand(villager, villager.getUsedItemHand())
                : villager.getMainArm();
        ItemUseAnimation useAnimation = usingItem
                ? villager.getUseItem().getUseAnimation()
                : ItemUseAnimation.NONE;
        float useTicks = usingItem ? villager.getTicksUsingItem(tickProgress) : 0.0F;
        float useDuration = usingItem ? useDuration(villager, villager.getUseItem(), useAnimation) : 1.0F;
        extra.totemVillagers$setPlayerStyleArmState(enabled, workProgress, attackArm,
                usingItem, useArm, useAnimation, useTicks, useDuration);
        if (!enabled) {
            return;
        }

        ItemModelResolver resolver = ((LivingEntityRendererItemModelResolverAccessor) (Object) this)
                .totemVillagers$itemModelResolver();
        if (!rightStack.isEmpty()) {
            resolver.updateForLiving(extra.totemVillagers$rightHandItem(), rightStack,
                    ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, villager);
        }
        if (!leftStack.isEmpty()) {
            resolver.updateForLiving(extra.totemVillagers$leftHandItem(), leftStack,
                    ItemDisplayContext.THIRD_PERSON_LEFT_HAND, villager);
        }
        // Prevent the original CrossedArmsItemLayer from drawing the same stack again.
        state.heldItem.clear();
    }

    private static HumanoidArm armForHand(Villager villager, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? villager.getMainArm() : villager.getMainArm().getOpposite();
    }

    private static float useDuration(Villager villager, ItemStack stack, ItemUseAnimation animation) {
        if (animation == ItemUseAnimation.CROSSBOW) {
            return CrossbowItem.getChargeDuration(stack, villager);
        }
        return stack.getUseDuration(villager);
    }

    private static ItemStack toolFor(Villager villager, float workProgress) {
        Identifier professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                villager.getVillagerData().profession().value());
        if (Identifier.withDefaultNamespace("toolsmith").equals(professionId) && workProgress > 0.0F) {
            return toolsmithShears();
        }
        if (TotemVillagerProfessions.MINER_ID.equals(professionId)) {
            return minerPickaxe();
        }
        if (TotemVillagerProfessions.LUMBERJACK_ID.equals(professionId)) {
            return lumberjackAxe();
        }
        if (TotemVillagerProfessions.BUILDER_ID.equals(professionId)) {
            return builderShovel();
        }
        if (TotemVillagerProfessions.GUARD_ID.equals(professionId)) {
            return guardSword();
        }
        return Identifier.withDefaultNamespace("farmer").equals(professionId) ? farmerHoe() : null;
    }

    // Item components bind after renderer classes, so initialise the display stacks lazily.
    private static ItemStack minerPickaxe() {
        if (minerPickaxe == null) {
            minerPickaxe = new ItemStack(Items.IRON_PICKAXE);
        }
        return minerPickaxe;
    }

    private static ItemStack lumberjackAxe() {
        if (lumberjackAxe == null) {
            lumberjackAxe = new ItemStack(Items.IRON_AXE);
        }
        return lumberjackAxe;
    }

    private static ItemStack builderShovel() {
        if (builderShovel == null) {
            builderShovel = new ItemStack(Items.IRON_SHOVEL);
        }
        return builderShovel;
    }

    private static ItemStack guardSword() {
        if (guardSword == null) {
            guardSword = new ItemStack(Items.IRON_SWORD);
        }
        return guardSword;
    }

    private static ItemStack farmerHoe() {
        if (farmerHoe == null) {
            farmerHoe = new ItemStack(Items.IRON_HOE);
        }
        return farmerHoe;
    }

    private static ItemStack toolsmithShears() {
        if (toolsmithShears == null) {
            toolsmithShears = new ItemStack(Items.SHEARS);
        }
        return toolsmithShears;
    }
}
