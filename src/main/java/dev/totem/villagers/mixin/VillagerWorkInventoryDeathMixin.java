package dev.totem.villagers.mixin;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutritionSavedData;
import dev.totem.villagers.runtime.FishermanCampfireFuelSavedData;
import dev.totem.villagers.runtime.MinerFurnaceMaintenanceSavedData;
import dev.totem.villagers.world.ore.MinerOreSafetySavedData;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Work materials remain recoverable if their owner dies. */
@Mixin(Villager.class)
abstract class VillagerWorkInventoryDeathMixin {
    @Inject(method = "die", at = @At("TAIL"))
    private void totemVillagers$dropPersonalWorkMaterials(DamageSource damageSource, CallbackInfo callback) {
        Villager villager = (Villager) (Object) this;
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerWorkInventorySavedData.forServer(level.getServer()).drain(villager.getUUID()).forEach(stack ->
                level.addFreshEntity(new ItemEntity(level, villager.getX(), villager.getY(), villager.getZ(), stack)));
        VillagerNutritionSavedData.forServer(level.getServer()).remove(villager.getUUID());
        FishermanCampfireFuelSavedData.forServer(level.getServer()).remove(villager.getUUID());
        MinerFurnaceMaintenanceSavedData.forServer(level.getServer()).remove(villager.getUUID());
        MinerOreSafetySavedData.forServer(level.getServer()).remove(villager.getUUID());
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        assignments.getAssignment(villager.getUUID())
                .filter(assignment -> TotemVillagerProfessions.MINER_ID.toString().equals(assignment.roleId())
                        || TotemVillagerProfessions.LUMBERJACK_ID.toString().equals(assignment.roleId()))
                .ifPresent(ignored -> assignments.removeAssignment(villager.getUUID()));
    }
}
