package dev.totem.villagers.mixin;

import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Totem specialist professions deliberately have no vanilla POI predicate.
 * Prevent vanilla ResetProfession from erasing a career while its durable
 * worker assignment still exists.
 */
@Mixin(Villager.class)
abstract class VillagerSpecialistProfessionMixin {
    private static final Set<String> TOTEM_SPECIALISTS = Set.of(
            TotemVillagerProfessions.MINER_ID.toString(),
            TotemVillagerProfessions.LUMBERJACK_ID.toString(),
            TotemVillagerProfessions.BUILDER_ID.toString(),
            TotemVillagerProfessions.GUARD_ID.toString()
    );

    @Inject(method = "finalizeSpawn", at = @At("RETURN"))
    private void rememberStructureBirth(net.minecraft.world.level.ServerLevelAccessor level, net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.EntitySpawnReason reason, net.minecraft.world.entity.SpawnGroupData group,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.entity.SpawnGroupData> callback) {
        if (reason == net.minecraft.world.entity.EntitySpawnReason.STRUCTURE) {
            Villager resident = (Villager) (Object) this;
            dev.totem.villagers.runtime.VillagerCareerHistory.recordStructureBirth(resident,
                    net.minecraft.core.GlobalPos.of(level.getLevel().dimension(), resident.blockPosition()));
        }
    }

    @Inject(method = "setVillagerData", at = @At("TAIL"))
    private void forgetPreviousSmithStation(VillagerData nextData, CallbackInfo callback) {
        Villager worker = (Villager)(Object)this;
        if (worker.level() instanceof ServerLevel && !nextData.profession().is(VillagerProfession.TOOLSMITH) && !nextData.profession().is(VillagerProfession.LIBRARIAN))
            dev.totem.villagers.runtime.GuardEquipmentOrders.forgetSmith(worker);
    }

    @Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
    private void totemVillagers$preserveAssignedSpecialist(VillagerData nextData, CallbackInfo callback) {
        Villager villager = (Villager) (Object) this;
        if (!(villager.level() instanceof ServerLevel level) || !nextData.profession().is(VillagerProfession.NONE)) {
            return;
        }
        Identifier currentKey = BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().profession().value());
        if (currentKey == null || !TOTEM_SPECIALISTS.contains(currentKey.toString())) {
            return;
        }
        WorkerAssignmentSavedData.forServer(level.getServer()).getAssignment(villager.getUUID())
                .filter(assignment -> currentKey.toString().equals(assignment.roleId()))
                .ifPresent(ignored -> callback.cancel());
    }
}
