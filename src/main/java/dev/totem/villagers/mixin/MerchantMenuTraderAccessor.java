package dev.totem.villagers.mixin;

import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Identifies the authoritative merchant behind an open vanilla trading menu. */
@Mixin(MerchantMenu.class)
public interface MerchantMenuTraderAccessor {
    @Accessor("trader")
    Merchant totemVillagers$trader();
}
