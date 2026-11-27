package dev.totem.villagers.mixin;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WalkNodeEvaluator.class)
abstract class FarmerComposterPathMixin {
    @Inject(method = "getPathTypeOfMob", at = @At("HEAD"), cancellable = true)
    private void avoidAssignedComposter(PathfindingContext context, int x, int y, int z, Mob mob,
                                       CallbackInfoReturnable<PathType> callback) {
        if (!(mob instanceof Villager farmer) || !(mob.level() instanceof ServerLevel level)) return;
        var site = farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (site == null || x != site.pos().getX() || z != site.pos().getZ()
                || y < site.pos().getY() || y > site.pos().getY() + 1 || !site.dimension().equals(level.dimension())) return;
        if (context.getBlockState(site.pos()).is(Blocks.COMPOSTER)
                && WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() == WorkBackedTradingMode.ENFORCED)
            callback.setReturnValue(PathType.BLOCKED);
    }
}
