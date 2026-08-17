package dev.totem.villagers.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.HumanoidArm;

/** Draws articulated villager arms and items using the same hand transforms as humanoid renderers. */
public final class VillagerPlayerLikeItemLayer extends RenderLayer<VillagerRenderState, VillagerModel> {
    public static final ModelLayerLocation MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_player_like_arms"), "main");
    private static final Identifier BASE_TEXTURE = Identifier.withDefaultNamespace(
            "textures/entity/villager/villager.png");

    private final VillagerPlayerLikeArmsModel armsModel;
    private final ResourceManager resourceManager;

    public VillagerPlayerLikeItemLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent,
                                        ModelPart root, ResourceManager resourceManager) {
        super(parent);
        this.armsModel = new VillagerPlayerLikeArmsModel(root);
        this.resourceManager = resourceManager;
    }

    public static LayerDefinition createLayerDefinition() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("right_arm", arm(false), PartPose.offset(-6.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", arm(true), PartPose.offset(6.0F, 2.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static CubeListBuilder arm(boolean mirror) {
        return CubeListBuilder.create()
                .mirror(mirror)
                .texOffs(44, 22).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 8.0F, 4.0F)
                .texOffs(40, 38).addBox(-2.0F, 6.0F, -2.0F, 4.0F, 4.0F, 4.0F);
    }

    @Override
    public void submit(PoseStack poses, SubmitNodeCollector collector, int light,
                       VillagerRenderState state, float yRot, float xRot) {
        VillagerWorkAnimationRenderState extra = (VillagerWorkAnimationRenderState) state;
        if (!extra.totemVillagers$usesPlayerStyleArms() || state.isInvisible || state.isBaby) {
            return;
        }

        armsModel.setupAnim(state);
        renderColoredCutoutModel(armsModel, BASE_TEXTURE, poses, collector, light, state, -1, 0);
        if (state.villagerData != null) {
            renderOverlay("type", state.villagerData.type(), poses, collector, light, state, 1);
            renderOverlay("profession", state.villagerData.profession(), poses, collector, light, state, 2);
        }
        submitItem(extra.totemVillagers$rightHandItem(), HumanoidArm.RIGHT, poses, collector, light, state);
        submitItem(extra.totemVillagers$leftHandItem(), HumanoidArm.LEFT, poses, collector, light, state);
    }

    private void renderOverlay(String kind, Holder<?> holder, PoseStack poses,
                               SubmitNodeCollector collector, int light,
                               VillagerRenderState state, int order) {
        holder.unwrapKey().map(ResourceKey::identifier).map(id -> overlay(kind, id)).ifPresent(texture -> {
            if (resourceManager.getResource(texture).isPresent()) {
                renderColoredCutoutModel(armsModel, texture, poses, collector, light, state, -1, order);
            }
        });
    }

    private void submitItem(ItemStackRenderState item, HumanoidArm arm, PoseStack poses,
                            SubmitNodeCollector collector, int light, VillagerRenderState state) {
        if (item.isEmpty()) {
            return;
        }
        poses.pushPose();
        armsModel.translateToHand(arm, poses);
        poses.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poses.mulPose(Axis.YP.rotationDegrees(180.0F));
        poses.translate((arm == HumanoidArm.LEFT ? -1.0F : 1.0F) / 16.0F,
                2.0F / 16.0F, -10.0F / 16.0F);
        item.submit(poses, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poses.popPose();
    }

    private static Identifier overlay(String kind, Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(),
                "textures/entity/villager/" + kind + "/" + id.getPath() + ".png");
    }
}
