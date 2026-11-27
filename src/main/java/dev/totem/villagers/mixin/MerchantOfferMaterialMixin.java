package dev.totem.villagers.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.totem.villagers.trade.ProductionMaterialRow;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantOffer.class)
abstract class MerchantOfferMaterialMixin implements ProductionMaterialRow {
    @Unique private boolean totemVillagers$material;
    public boolean totemVillagers$isMaterial() { return totemVillagers$material; }
    public void totemVillagers$markMaterial() { totemVillagers$material = true; }

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE", target =
            "Lcom/mojang/serialization/codecs/RecordCodecBuilder;create(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"))
    private static Codec<MerchantOffer> totemVillagers$persistMaterial(Codec<MerchantOffer> original) {
        return Codec.mapPair(MapCodec.assumeMapUnsafe(original), Codec.BOOL.optionalFieldOf("totem_villagers_material", false))
                .xmap(pair -> {
                    if (pair.getSecond()) ((ProductionMaterialRow)pair.getFirst()).totemVillagers$markMaterial();
                    return pair.getFirst();
                }, offer -> Pair.of(offer, ((ProductionMaterialRow)offer).totemVillagers$isMaterial())).codec();
    }

    @Inject(method = "<init>(Lnet/minecraft/world/item/trading/MerchantOffer;)V", at = @At("RETURN"))
    private void totemVillagers$copyMarker(MerchantOffer original, CallbackInfo ci) {
        totemVillagers$material = ((ProductionMaterialRow)original).totemVillagers$isMaterial();
    }
}
