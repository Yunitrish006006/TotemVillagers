package dev.totem.villagers.workshop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Dimension-specific identity for a registered Village Work Chest. */
public record WorkChestKey(String dimensionId, long packedBlockPosition) {
    public static final Codec<WorkChestKey> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(WorkChestKey::dimensionId),
            Codec.LONG.fieldOf("position").forGetter(WorkChestKey::packedBlockPosition)
    ).apply(instance, WorkChestKey::new));

    public WorkChestKey {
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
    }
}
