package dev.totem.villagers.mixin;

import dev.totem.villagers.trade.TradeSnapshotSender;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Couples a vanilla merchant-offer update to the matching server-owned work snapshot. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerTradeSnapshotMixin {
    @Inject(method = "sendMerchantOffers", at = @At("TAIL"))
    private void totemVillagers$sendTradeSnapshot(
            int containerId,
            MerchantOffers offers,
            int villagerLevel,
            int villagerXp,
            boolean showProgress,
            boolean canRestock,
            CallbackInfo callback
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.containerMenu instanceof MerchantMenu merchantMenu
                && merchantMenu.containerId == containerId
                && ((MerchantMenuTraderAccessor) merchantMenu).totemVillagers$trader() instanceof Villager villager) {
            TradeSnapshotSender.send(player, villager, offers, containerId);
        }
    }
}
