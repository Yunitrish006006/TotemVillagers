package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/** Durable identity of the one world block or entity reserved by an active work action. */
public record WorldWorkTarget(String dimensionId, Optional<Long> packedBlockPosition, Optional<UUID> entityId) {
    public static final Codec<WorldWorkTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(WorldWorkTarget::dimensionId),
            Codec.LONG.optionalFieldOf("position").forGetter(WorldWorkTarget::packedBlockPosition),
            UUIDUtil.CODEC.optionalFieldOf("entity").forGetter(WorldWorkTarget::entityId)
    ).apply(instance, WorldWorkTarget::new));

    public WorldWorkTarget(String dimensionId, long packedBlockPosition) {
        this(dimensionId, Optional.of(packedBlockPosition), Optional.empty());
    }

    public static WorldWorkTarget entity(String dimensionId, UUID entityId) {
        return new WorldWorkTarget(dimensionId, Optional.empty(), Optional.of(entityId));
    }

    public WorldWorkTarget {
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
        packedBlockPosition = packedBlockPosition == null ? Optional.empty() : packedBlockPosition;
        entityId = entityId == null ? Optional.empty() : entityId;
        if (packedBlockPosition.isPresent() == entityId.isPresent()) {
            throw new IllegalArgumentException("World work target must identify exactly one block or entity");
        }
    }
}
