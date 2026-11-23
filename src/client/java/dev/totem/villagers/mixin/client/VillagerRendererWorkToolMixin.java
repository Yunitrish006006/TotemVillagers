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
        extra.totemVillagers$setStatus(statusLabel(villager));
        extra.totemVillagers$rightHandItem().clear();
        extra.totemVillagers$leftHandItem().clear();
        float workProgress = villager.getSwingAnimation(tickProgress);
        HumanoidArm attackArm = armForHand(villager, villager.getCurrentSwing() == null
                ? InteractionHand.MAIN_HAND : villager.getCurrentSwing().hand());
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

    private static net.minecraft.network.chat.Component statusLabel(Villager villager) {
        var display = dev.totem.villagers.runtime.VillagerHeadStatus.display(villager);
        var client = net.minecraft.client.Minecraft.getInstance();
        var player = client.player;
        if (display == null || player == null || client.gui.hud.isHidden() || villager.isBaby()
                || villager.isInvisibleTo(player) || player.distanceToSqr(villager) > 24 * 24
                || !player.hasLineOfSight(villager)) return null;
        var status = display.kind();
        var atlas = status.sprite.startsWith("block/") ? net.minecraft.data.AtlasIds.BLOCKS : net.minecraft.data.AtlasIds.ITEMS;
        var label = net.minecraft.network.chat.Component.object(new net.minecraft.network.chat.contents.objects.AtlasSprite(
                atlas, Identifier.withDefaultNamespace(status.sprite)));
        var towards = villager.getEyePosition().subtract(player.getEyePosition());
        if (towards.lengthSqr() <= 8 * 8 && player.getLookAngle().dot(towards.normalize()) > .97) {
            var text = display.material().map(need -> {
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(need.itemId()));
                var name = new ItemStack(item).getHoverName();
                if (display.pristine()) name = net.minecraft.network.chat.Component.translatable("status.totem.villager.pristine", name);
                if (display.potion().isPresent()) {
                    var id = Identifier.tryParse(display.potion().get());
                    var potion = id == null ? java.util.Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.item.alchemy.Potion>>empty()
                            : client.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POTION).get(id);
                    if (potion.isEmpty()) name = net.minecraft.network.chat.Component.translatable("status.totem.villager.order_variant", name);
                    else {
                        name = net.minecraft.world.item.alchemy.PotionContents.createItemStack(item, potion.get()).getHoverName();
                        var effects = potion.get().value().getEffects();
                        if (!effects.isEmpty()) {
                            var effect = effects.getFirst();
                            var detail = net.minecraft.world.item.alchemy.PotionContents.getPotionDescription(effect.getEffect(), effect.getAmplifier());
                            if (!effect.getEffect().value().isInstantaneous()) detail = detail.append(" ").append(
                                    net.minecraft.world.effect.MobEffectUtil.formatDuration(effect, .25F, 20));
                            name = name.copy().append(" (").append(detail).append(")");
                        }
                    }
                }
                return net.minecraft.network.chat.Component.translatable(status.translation(), need.count(), name);
            }).orElseGet(() -> net.minecraft.network.chat.Component.translatable(status.translation()));
            label.append(" ").append(text);
        }
        return label;
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
