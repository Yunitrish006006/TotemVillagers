package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;
import java.util.UUID;

/** Explicit construction pad and village boundary; it never affects an unconfigured village. */
public record GuardPost(UUID villageId, UUID ownerId, String dimensionId, long packedConstructionPad) {
    public static final Codec<GuardPost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            net.minecraft.core.UUIDUtil.CODEC.fieldOf("village").forGetter(GuardPost::villageId),
            net.minecraft.core.UUIDUtil.CODEC.fieldOf("owner").forGetter(GuardPost::ownerId),
            Codec.STRING.optionalFieldOf("dimension").forGetter(post -> java.util.Optional.of(post.dimensionId())),
            // Consume v1 records; new post data has no shared-container dependency.
            dev.totem.villagers.workshop.WorkChestKey.CODEC.optionalFieldOf("work_chest").forGetter(post -> java.util.Optional.empty()),
            Codec.LONG.fieldOf("construction_pad").forGetter(GuardPost::packedConstructionPad)
    ).apply(instance, (villageId, ownerId, dimensionId, ignoredWorkChest, packedConstructionPad) ->
            new GuardPost(villageId, ownerId,
                    dimensionId.orElseGet(() -> ignoredWorkChest.map(dev.totem.villagers.workshop.WorkChestKey::dimensionId)
                            .orElse("minecraft:overworld")), packedConstructionPad)));

    public GuardPost {
        Objects.requireNonNull(villageId, "villageId");
        Objects.requireNonNull(ownerId, "ownerId");
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
    }
}
