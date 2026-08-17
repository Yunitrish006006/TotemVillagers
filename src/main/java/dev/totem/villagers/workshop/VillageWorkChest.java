package dev.totem.villagers.workshop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-managed links and allow-list for one ordinary container used as a Work Chest. */
public record VillageWorkChest(
        WorkChestKey key,
        UUID ownerId,
        Set<UUID> linkedVillagers,
        Set<Long> linkedJobSitePositions,
        Set<String> acceptedInputIds
) {
    private static final Codec<Set<UUID>> UUID_SET_CODEC = UUIDUtil.CODEC.listOf().xmap(Set::copyOf, List::copyOf);
    private static final Codec<Set<Long>> LONG_SET_CODEC = Codec.LONG.listOf().xmap(Set::copyOf, List::copyOf);
    private static final Codec<Set<String>> ID_SET_CODEC = Codec.STRING.listOf().xmap(Set::copyOf, List::copyOf);
    public static final Codec<VillageWorkChest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            WorkChestKey.CODEC.fieldOf("key").forGetter(VillageWorkChest::key),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(VillageWorkChest::ownerId),
            UUID_SET_CODEC.optionalFieldOf("linked_villagers", Set.of()).forGetter(VillageWorkChest::linkedVillagers),
            LONG_SET_CODEC.optionalFieldOf("linked_job_sites", Set.of()).forGetter(VillageWorkChest::linkedJobSitePositions),
            ID_SET_CODEC.optionalFieldOf("accepted_inputs", Set.of()).forGetter(VillageWorkChest::acceptedInputIds)
    ).apply(instance, VillageWorkChest::new));

    public VillageWorkChest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ownerId, "ownerId");
        linkedVillagers = Set.copyOf(linkedVillagers);
        linkedJobSitePositions = Set.copyOf(linkedJobSitePositions);
        acceptedInputIds = Set.copyOf(acceptedInputIds);
        if (acceptedInputIds.stream().anyMatch(id -> id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            throw new IllegalArgumentException("accepted inputs must be namespaced identifiers");
        }
    }

    public boolean permits(UUID villagerId, WorkOrder order) {
        return linkedVillagers.contains(villagerId)
                && order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)
                && order.requiredInputs().stream().allMatch(input -> acceptedInputIds.contains(input.itemId()));
    }

    public VillageWorkChest withVillager(UUID villagerId, boolean linked) {
        Set<UUID> next = new java.util.LinkedHashSet<>(linkedVillagers);
        if (linked) next.add(villagerId); else next.remove(villagerId);
        return new VillageWorkChest(key, ownerId, next, linkedJobSitePositions, acceptedInputIds);
    }

    public VillageWorkChest withJobSite(long packedPosition, boolean linked) {
        Set<Long> next = new java.util.LinkedHashSet<>(linkedJobSitePositions);
        if (linked) next.add(packedPosition); else next.remove(packedPosition);
        return new VillageWorkChest(key, ownerId, linkedVillagers, next, acceptedInputIds);
    }

    public VillageWorkChest withAcceptedInput(String itemId, boolean accepted) {
        if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("itemId must be a namespaced identifier");
        }
        Set<String> next = new java.util.LinkedHashSet<>(acceptedInputIds);
        if (accepted) next.add(itemId); else next.remove(itemId);
        return new VillageWorkChest(key, ownerId, linkedVillagers, linkedJobSitePositions, next);
    }
}
