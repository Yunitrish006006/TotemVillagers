package dev.totem.villagers.mixin;

import dev.totem.villagers.runtime.VillageWorkshopApprenticeship;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AssignProfessionFromJobSite.class)
abstract class AssignProfessionFromJobSiteMixin {
    @Inject(method = "lambda$create$2", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;erase()V"), cancellable = true)
    private static void deferHouseholdSmith(BehaviorBuilder.Instance<?> instance, MemoryAccessor<?, ?> potential,
            MemoryAccessor<?, ?> assigned, ServerLevel level, Villager worker, long time, CallbackInfoReturnable<Boolean> result) {
        if (VillageWorkshopApprenticeship.reservesStationForTrial(level, worker)) {
            worker.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
            worker.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
            result.setReturnValue(true);
        }
    }
}
