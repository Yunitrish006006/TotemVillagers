package dev.totem.villagers.mixin;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.worker.ProductionParcel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HarvestFarmland.class)
abstract class HarvestFarmlandMixin {
    @Shadow private BlockPos aboveFarmlandPos;

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V",
            at = @At("HEAD"), cancellable = true)
    private void respectFieldCaretaker(ServerLevel level, Villager worker, long time, CallbackInfo callback) {
        if (aboveFarmlandPos != null
                && WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() == WorkBackedTradingMode.ENFORCED
                && ProductionParcel.get(level.getServer()).heldByAnother(level, aboveFarmlandPos.below(), worker)) {
            aboveFarmlandPos = null;
            callback.cancel();
        }
    }
}
