package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Objects;
import java.util.UUID;

/** A named persistent specialist-work boundary, separate from live entity state. */
public record WorkZoneRecord(UUID id, String roleId, WorkZone zone) {
    public static final Codec<WorkZoneRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(WorkZoneRecord::id),
            Codec.STRING.fieldOf("role").forGetter(WorkZoneRecord::roleId),
            WorkZone.CODEC.fieldOf("zone").forGetter(WorkZoneRecord::zone)
    ).apply(instance, WorkZoneRecord::new));

    public WorkZoneRecord {
        Objects.requireNonNull(id, "id");
        if (!WorkerProfessionRegistry.MINER.id().equals(roleId)
                && !WorkerProfessionRegistry.LUMBERJACK.id().equals(roleId)
                && !WorkerProfessionRegistry.BUILDER.id().equals(roleId)) {
            throw new IllegalArgumentException("Only Miner, Lumberjack, and Builder work zones are supported");
        }
        Objects.requireNonNull(zone, "zone");
    }
}
