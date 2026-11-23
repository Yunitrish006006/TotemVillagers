package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Objects;
import java.util.UUID;

/** A bounded, owner-configured permission boundary for autonomous world work. */
public record WorkZone(
        UUID ownerId,
        String dimensionId,
        BlockCoordinate minimum,
        BlockCoordinate maximum,
        boolean naturalTimber,
        boolean naturalQuarry
) {
    public static final Codec<WorkZone> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(WorkZone::ownerId),
            Codec.STRING.fieldOf("dimension").forGetter(WorkZone::dimensionId),
            BlockCoordinate.CODEC.fieldOf("minimum").forGetter(WorkZone::minimum),
            BlockCoordinate.CODEC.fieldOf("maximum").forGetter(WorkZone::maximum),
            Codec.BOOL.optionalFieldOf("natural_timber", false).forGetter(WorkZone::naturalTimber),
            Codec.BOOL.optionalFieldOf("natural_quarry", false).forGetter(WorkZone::naturalQuarry)
    ).apply(instance, WorkZone::new));

    public WorkZone(UUID ownerId, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum) {
        this(ownerId, dimensionId, minimum, maximum, false, false);
    }

    public WorkZone(UUID ownerId, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum, boolean naturalTimber) {
        this(ownerId, dimensionId, minimum, maximum, naturalTimber, false);
    }

    public boolean personal() { return naturalTimber || naturalQuarry; }

    public WorkZone {
        Objects.requireNonNull(ownerId, "ownerId");
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("WorkZone minimum must not exceed maximum");
        }
    }

    public boolean contains(String candidateDimension, BlockCoordinate position) {
        Objects.requireNonNull(position, "position");
        return dimensionId.equals(candidateDimension)
                && position.x() >= minimum.x() && position.x() <= maximum.x()
                && position.y() >= minimum.y() && position.y() <= maximum.y()
                && position.z() >= minimum.z() && position.z() <= maximum.z();
    }
}
