package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistent quota and reservation ownership for one intentionally managed village. */
public record ManagedVillageState(
        GuardPost post,
        UUID guardVillagerId,
        Set<UUID> managedGolemIds,
        Optional<GuardConstructionState> construction
) {
    private static final Codec<Set<UUID>> UUID_SET_CODEC = net.minecraft.core.UUIDUtil.CODEC.listOf().xmap(Set::copyOf, List::copyOf);
    public static final Codec<ManagedVillageState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GuardPost.CODEC.fieldOf("post").forGetter(ManagedVillageState::post),
            net.minecraft.core.UUIDUtil.CODEC.fieldOf("guard").forGetter(ManagedVillageState::guardVillagerId),
            UUID_SET_CODEC.optionalFieldOf("managed_golems", Set.of()).forGetter(ManagedVillageState::managedGolemIds),
            GuardConstructionState.CODEC.optionalFieldOf("construction").forGetter(ManagedVillageState::construction)
    ).apply(instance, ManagedVillageState::new));

    public ManagedVillageState {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(guardVillagerId, "guardVillagerId");
        managedGolemIds = Set.copyOf(managedGolemIds);
        construction = construction == null ? Optional.empty() : construction;
    }

    public ManagedVillageState withConstruction(Optional<GuardConstructionState> next) {
        return new ManagedVillageState(post, guardVillagerId, managedGolemIds, next);
    }

    public ManagedVillageState withManagedGolems(Set<UUID> next) {
        return new ManagedVillageState(post, guardVillagerId, next, construction);
    }
}
