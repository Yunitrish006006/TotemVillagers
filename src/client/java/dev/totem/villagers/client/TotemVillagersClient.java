package dev.totem.villagers.client;

import dev.totem.villagers.client.render.VillagerPlayerLikeItemLayer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.api.ClientModInitializer;

/** Client entry point for read-only, server-authoritative villager trade feedback. */
public final class TotemVillagersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(VillagerPlayerLikeItemLayer.MODEL_LAYER,
                VillagerPlayerLikeItemLayer::createLayerDefinition);
        WoodcutterScreenRegistration.register();
    }
}
