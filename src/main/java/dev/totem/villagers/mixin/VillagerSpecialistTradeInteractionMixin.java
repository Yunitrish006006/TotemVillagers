package dev.totem.villagers.mixin;

import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prepares custom-profession offers, then leaves interaction entirely to vanilla. */
@Mixin(Villager.class)
abstract class VillagerSpecialistTradeInteractionMixin {
    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void totemVillagers$prepareVanillaTrade(Player player, InteractionHand hand,
                                                     CallbackInfoReturnable<InteractionResult> callback) {
        if (!player.level().isClientSide()) {
            VillagerTradeStockAuthority.ensureSpecialistTradeMenu((Villager) (Object) this);
        }
    }
}
