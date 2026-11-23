package dev.totem.villagers.client;

import dev.totem.villagers.client.render.VillagerWorkAnimationRenderState;
import dev.totem.villagers.runtime.VillagerHeadStatus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public final class HeadStatusTrackingClient implements ClientModInitializer {
    private boolean observed;
    private boolean cleared;

    @Override
    public void onInitializeClient() {
        String viewer = System.getProperty("totem.villagers.trackingViewer");
        if (viewer == null) return;
        boolean near = viewer.equals("Near");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (cleared || client.player == null || client.level == null) return;
            var workers = client.level.getEntitiesOfClass(Villager.class, new AABB(-1, 100, 3, 2, 103, 6));
            if (workers.size() != 1) return;
            var worker = workers.getFirst();
            double range = client.player.distanceToSqr(worker);
            if (near ? range < 9 || range > 36 : range < 100 || range > 144) return;
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, worker.getEyePosition());
            var state = client.getEntityRenderDispatcher().getRenderer(worker).createRenderState(worker, 1);
            var label = ((VillagerWorkAnimationRenderState)state).totemVillagers$status();
            var display = VillagerHeadStatus.display(worker);
            if (!observed && display != null && display.kind() == VillagerHeadStatus.Kind.MATERIAL
                    && display.material().filter(m -> m.itemId().equals("minecraft:glowstone_dust") && m.count() == 2).isPresent()
                    && label != null) {
                var text = Component.translatable(display.kind().translation(), 2, new ItemStack(Items.GLOWSTONE_DUST).getHoverName());
                if (near ? label.getString().contains(text.getString()) : label.getSiblings().isEmpty()) {
                    observed = true;
                    com.mojang.logging.LogUtils.getLogger().info("Tracking {} observed {}", viewer, worker.getUUID());
                }
            } else if (observed && display == null && label == null) {
                cleared = true;
                com.mojang.logging.LogUtils.getLogger().info("Tracking {} cleared {}", viewer, worker.getUUID());
            }
        });
    }
}
