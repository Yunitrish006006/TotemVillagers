package dev.totem.villagers.mixin.client;

import dev.totem.villagers.client.TradeSnapshotClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears retained diagnostics when the corresponding merchant container closes. */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenTradeSnapshotMixin {
    @Shadow
    public abstract AbstractContainerMenu getMenu();

    @Inject(method = "removed", at = @At("TAIL"))
    private void totemVillagers$forgetClosedMerchantMenu(CallbackInfo callback) {
        if (getMenu() instanceof MerchantMenu merchantMenu) {
            TradeSnapshotClient.forget(merchantMenu.containerId);
        }
    }
}
