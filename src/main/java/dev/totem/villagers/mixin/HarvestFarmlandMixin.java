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
                && (ProductionParcel.get(level.getServer()).heldByAnother(level, aboveFarmlandPos.below(), worker)
                    || hasWorkHoeForCrop(level, worker, aboveFarmlandPos)
                    || dev.totem.villagers.work.VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID())
                        .flatMap(state -> state.activeWork())
                        .filter(work -> work.source() == dev.totem.villagers.work.WorkSource.WORLD)
                        .flatMap(work -> work.worldTarget())
                        .filter(target -> target.dimensionId().equals(level.dimension().identifier().toString()))
                        .flatMap(target -> target.packedBlockPosition())
                        .filter(pos -> pos == aboveFarmlandPos.asLong()).isPresent())) {
            aboveFarmlandPos = null;
            callback.cancel();
        }
    }

    private static boolean hasWorkHoeForCrop(ServerLevel level, Villager worker, BlockPos crop) {
        var state = level.getBlockState(crop);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.CropBlock plant) || !plant.isMaxAge(state)
                || !dev.totem.villagers.world.FarmerWorldWorkAction.growsFood(level, crop)) return false;
        var site = worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        return site.filter(s -> s.dimension().equals(level.dimension()) && s.pos().distSqr(crop) <= 24 * 24
                && level.isLoaded(s.pos()) && level.getBlockState(s.pos()).is(net.minecraft.world.level.block.Blocks.COMPOSTER)).isPresent()
                && dev.totem.villagers.world.FarmerWorldWorkAction.bestHoe(dev.totem.villagers.inventory.VillagerWorkInventorySavedData
                        .forServer(level.getServer()).inventory(worker.getUUID())).isPresent();
    }
}
