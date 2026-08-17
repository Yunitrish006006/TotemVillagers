package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Explicit, dimension-bound link between a protected work chest and its workers. */
public record VillageWorkChestLink(
        String dimensionId,
        long packedBlockPosition,
        UUID ownerId,
        Set<UUID> linkedVillagers,
        Set<String> acceptedInputIds
) {
    private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.CODEC.listOf()
            .xmap(Set::copyOf, List::copyOf);
    private static final Codec<Set<String>> IDENTIFIER_SET_CODEC = Codec.STRING.listOf()
            .xmap(Set::copyOf, List::copyOf);

    public static final Codec<VillageWorkChestLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(VillageWorkChestLink::dimensionId),
            Codec.LONG.fieldOf("position").forGetter(VillageWorkChestLink::packedBlockPosition),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(VillageWorkChestLink::ownerId),
            UUID_SET_CODEC.optionalFieldOf("linked_villagers", Set.of()).forGetter(VillageWorkChestLink::linkedVillagers),
            IDENTIFIER_SET_CODEC.optionalFieldOf("accepted_inputs", Set.of()).forGetter(VillageWorkChestLink::acceptedInputIds)
    ).apply(instance, VillageWorkChestLink::new));

    public VillageWorkChestLink {
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
        Objects.requireNonNull(ownerId, "ownerId");
        linkedVillagers = Set.copyOf(linkedVillagers);
        acceptedInputIds = Set.copyOf(acceptedInputIds);
        if (acceptedInputIds.stream().anyMatch(id -> !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            throw new IllegalArgumentException("accepted inputs must be namespaced identifiers");
        }
    }
}
