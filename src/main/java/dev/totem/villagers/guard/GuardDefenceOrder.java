package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.work.ItemAmount;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Data-driven construction bill and ordered visible placements for one managed golem. */
public record GuardDefenceOrder(String id, List<ItemAmount> requiredInputs, List<GuardPlacement> placements) {
    public static final GuardDefenceOrder VANILLA_IRON_GOLEM = new GuardDefenceOrder(
            "totem:iron_golem",
            List.of(new ItemAmount("minecraft:iron_block", 4), new ItemAmount("minecraft:carved_pumpkin", 1)),
            List.of(
                    new GuardPlacement(0, 0, 0, "minecraft:iron_block"),
                    new GuardPlacement(0, 1, 0, "minecraft:iron_block"),
                    new GuardPlacement(-1, 1, 0, "minecraft:iron_block"),
                    new GuardPlacement(1, 1, 0, "minecraft:iron_block"),
                    new GuardPlacement(0, 2, 0, "minecraft:carved_pumpkin")
            ));
    public static final Codec<GuardDefenceOrder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(GuardDefenceOrder::id),
            ItemAmount.CODEC.listOf().fieldOf("required_inputs").forGetter(GuardDefenceOrder::requiredInputs),
            GuardPlacement.CODEC.listOf().fieldOf("placements").forGetter(GuardDefenceOrder::placements)
    ).apply(instance, GuardDefenceOrder::new));

    public GuardDefenceOrder {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("id must be a namespaced identifier");
        }
        requiredInputs = List.copyOf(requiredInputs);
        placements = List.copyOf(placements);
        if (requiredInputs.isEmpty() || placements.isEmpty()) {
            throw new IllegalArgumentException("A defence order needs inputs and placements");
        }
        for (GuardPlacement placement : placements) {
            Objects.requireNonNull(placement, "placement");
            boolean supplied = requiredInputs.stream().anyMatch(input -> input.itemId().equals(placement.blockId()));
            if (!supplied) {
                throw new IllegalArgumentException("Placement is not supplied by defence inputs: " + placement.blockId());
            }
        }
        if (!aggregate(requiredInputs).equals(aggregatePlacements(placements))) {
            throw new IllegalArgumentException("Defence placement blocks must consume exactly the declared inputs");
        }
    }

    public boolean matchesVanillaIronGolemMaterials() {
        return requiredInputs.size() == 2
                && requiredInputs.contains(new ItemAmount("minecraft:iron_block", 4))
                && requiredInputs.contains(new ItemAmount("minecraft:carved_pumpkin", 1));
    }

    private static Map<String, Integer> aggregate(List<ItemAmount> inputs) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        inputs.forEach(input -> totals.merge(input.itemId(), input.count(), Math::addExact));
        return Map.copyOf(totals);
    }

    private static Map<String, Integer> aggregatePlacements(List<GuardPlacement> placements) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        placements.forEach(placement -> totals.merge(placement.blockId(), 1, Math::addExact));
        return Map.copyOf(totals);
    }
}
