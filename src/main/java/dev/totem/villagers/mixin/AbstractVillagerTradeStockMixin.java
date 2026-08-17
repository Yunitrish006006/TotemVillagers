package dev.totem.villagers.mixin;

import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.runtime.VillagerFoodEconomyRuntime;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ensures offer availability and stock debit are decided on the dedicated server. */
@Mixin(AbstractVillager.class)
abstract class AbstractVillagerTradeStockMixin {
    @Inject(method = "getOffers", at = @At("RETURN"))
    private void totemVillagers$gateSellOffers(CallbackInfoReturnable<MerchantOffers> callback) {
        VillagerTradeStockAuthority.refreshOffers((AbstractVillager) (Object) this, callback.getReturnValue());
    }

    @Inject(method = "notifyTrade", at = @At("TAIL"))
    private void totemVillagers$debitProducedStock(MerchantOffer offer, CallbackInfo callback) {
        VillagerTradeStockAuthority.creditAfterSuccessfulPlayerPurchase((AbstractVillager) (Object) this, offer);
        VillagerTradeStockAuthority.debitAfterSuccessfulTrade((AbstractVillager) (Object) this, offer);
        if ((Object) this instanceof net.minecraft.world.entity.npc.villager.Villager villager) {
            VillagerFoodEconomyRuntime.recordPlayerPayment(villager, offer);
        }
    }
}
