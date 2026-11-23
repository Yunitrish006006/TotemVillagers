package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.GlobalPos;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable specialist assignment. A missing Zone means world work is never considered. */
public record WorkerAssignment(UUID villagerId, String roleId, Optional<UUID> workZoneId, Optional<UUID> managedVillageId,
                               boolean automaticallyAssigned, Optional<GlobalPos> home) {
    public static final Codec<WorkerAssignment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(WorkerAssignment::villagerId),
            Codec.STRING.fieldOf("role").forGetter(WorkerAssignment::roleId),
            UUIDUtil.CODEC.optionalFieldOf("work_zone").forGetter(WorkerAssignment::workZoneId),
            UUIDUtil.CODEC.optionalFieldOf("managed_village").forGetter(WorkerAssignment::managedVillageId),
            Codec.BOOL.optionalFieldOf("automatically_assigned", false).forGetter(WorkerAssignment::automaticallyAssigned),
            Codec.lazyInitialized(() -> GlobalPos.CODEC).optionalFieldOf("resource_home").forGetter(WorkerAssignment::home)
    ).apply(instance, WorkerAssignment::new));

    /** Existing saves and operator assignments retain manual control. */
    public WorkerAssignment(UUID villagerId, String roleId, Optional<UUID> workZoneId, Optional<UUID> managedVillageId) {
        this(villagerId, roleId, workZoneId, managedVillageId, false);
    }

    public WorkerAssignment(UUID villagerId, String roleId, Optional<UUID> workZoneId, Optional<UUID> managedVillageId, boolean automatic) {
        this(villagerId, roleId, workZoneId, managedVillageId, automatic, Optional.empty());
    }

    public WorkerAssignment {
        Objects.requireNonNull(villagerId, "villagerId");
        WorkerProfessionRegistry.require(roleId);
        workZoneId = workZoneId == null ? Optional.empty() : workZoneId;
        managedVillageId = managedVillageId == null ? Optional.empty() : managedVillageId;
        home = home == null ? Optional.empty() : home;
        if (WorkerProfessionRegistry.GUARD.id().equals(roleId) && workZoneId.isPresent()) {
            throw new IllegalArgumentException("Guard does not use a generic Work Zone");
        }
        if (!WorkerProfessionRegistry.GUARD.id().equals(roleId) && managedVillageId.isPresent()) {
            throw new IllegalArgumentException("Only Guard uses a managed village assignment");
        }
    }

    public WorkerAssignment withWorkZone(Optional<UUID> nextZone) {
        return new WorkerAssignment(villagerId, roleId, nextZone, managedVillageId);
    }

    public WorkerAssignment withManagedVillage(Optional<UUID> nextVillage) {
        return new WorkerAssignment(villagerId, roleId, workZoneId, nextVillage);
    }
}
