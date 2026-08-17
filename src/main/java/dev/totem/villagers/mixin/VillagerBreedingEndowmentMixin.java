package dev.totem.villagers.mixin;

import dev.totem.villagers.runtime.VillagerBreedingEndowmentRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers both parents until vanilla successfully adds their child to the server level. */
@Mixin(Villager.class)
abstract class VillagerBreedingEndowmentMixin {
    @Inject(
            method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
            at = @At("RETURN"))
    private void totemVillagers$rememberParentFundedEndowment(ServerLevel level, AgeableMob partner,
                                                              CallbackInfoReturnable<Villager> callback) {
        Villager child = callback.getReturnValue();
        if (partner instanceof Villager secondParent && child != null) {
            VillagerBreedingEndowmentRuntime.rememberBirth((Villager) (Object) this, secondParent, child);
        }
    }
}
