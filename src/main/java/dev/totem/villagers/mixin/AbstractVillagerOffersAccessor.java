package dev.totem.villagers.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads already-generated offers without calling getOffers and recursively re-running the trade gate. */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerOffersAccessor {
    @Accessor("offers")
    MerchantOffers totemVillagers$existingOffers();

    @Accessor("offers")
    void totemVillagers$setExistingOffers(MerchantOffers offers);
}
