package dev.totem.villagers.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.totem.villagers.runtime.PastureSupplies;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
abstract class PastureLootMixin {
    @ModifyArg(method = "dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;ZLnet/minecraft/resources/ResourceKey;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;ZLnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V"), index = 4)
    private Consumer<ItemStack> totemVillagers$collectPastureDrops(Consumer<ItemStack> drop,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) DamageSource cause,
            @Local(argsOnly = true) boolean playerCredit) {
        return PastureSupplies.deathDrops(level, (LivingEntity)(Object)this, cause, playerCredit, drop);
    }
}
