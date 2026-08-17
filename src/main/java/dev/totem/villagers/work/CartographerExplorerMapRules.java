package dev.totem.villagers.work;

import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Tiered explorer-map policy for real Cartographer map work. */
public final class CartographerExplorerMapRules {
    private static final int[] EXPLORER_PERCENT_BY_VILLAGER_LEVEL = {0, 1, 2, 4, 8};
    private static final int VANILLA_SEARCH_RADIUS = 100;
    private static final List<ExplorerMapDefinition> DEFINITIONS = List.of(
            definition("taiga_village", 2, StructureTags.ON_TAIGA_VILLAGE_MAPS, "minecraft:taiga_village",
                    "filled_map.village_taiga", 8, 5),
            definition("snowy_village", 2, StructureTags.ON_SNOWY_VILLAGE_MAPS, "minecraft:snowy_village",
                    "filled_map.village_snowy", 8, 5),
            definition("savanna_village", 2, StructureTags.ON_SAVANNA_VILLAGE_MAPS, "minecraft:savanna_village",
                    "filled_map.village_savanna", 8, 5),
            definition("plains_village", 2, StructureTags.ON_PLAINS_VILLAGE_MAPS, "minecraft:plains_village",
                    "filled_map.village_plains", 8, 5),
            definition("desert_village", 2, StructureTags.ON_DESERT_VILLAGE_MAPS, "minecraft:desert_village",
                    "filled_map.village_desert", 8, 5),
            definition("jungle_temple", 2, StructureTags.ON_JUNGLE_EXPLORER_MAPS, "minecraft:jungle_temple",
                    "filled_map.explorer_jungle", 8, 5),
            definition("swamp_hut", 2, StructureTags.ON_SWAMP_EXPLORER_MAPS, "minecraft:swamp_hut",
                    "filled_map.explorer_swamp", 8, 5),
            definition("ocean_monument", 3, StructureTags.ON_OCEAN_EXPLORER_MAPS, "minecraft:monument",
                    "filled_map.monument", 13, 10),
            definition("trial_chambers", 3, StructureTags.ON_TRIAL_CHAMBERS_MAPS, "minecraft:trial_chambers",
                    "filled_map.trial_chambers", 12, 10),
            definition("woodland_mansion", 5, StructureTags.ON_WOODLAND_EXPLORER_MAPS, "minecraft:mansion",
                    "filled_map.mansion", 14, 30)
    );

    private CartographerExplorerMapRules() {
    }

    public static int explorerPercent(int villagerLevel) {
        return EXPLORER_PERCENT_BY_VILLAGER_LEVEL[index(villagerLevel)];
    }

    public static boolean rollsExplorerMap(RandomSource random, int villagerLevel) {
        Objects.requireNonNull(random, "random");
        int chance = explorerPercent(villagerLevel);
        return chance > 0 && random.nextInt(100) < chance;
    }

    /** Chooses only among destinations unlocked at this Cartographer's current profession tier. */
    public static Optional<ExplorerMapDefinition> chooseExplorerMap(RandomSource random, int villagerLevel) {
        Objects.requireNonNull(random, "random");
        if (!rollsExplorerMap(random, villagerLevel)) {
            return Optional.empty();
        }
        List<ExplorerMapDefinition> eligible = definitionsForLevel(villagerLevel);
        return eligible.isEmpty() ? Optional.empty() : Optional.of(eligible.get(random.nextInt(eligible.size())));
    }

    public static List<ExplorerMapDefinition> definitionsForLevel(int villagerLevel) {
        index(villagerLevel);
        return DEFINITIONS.stream().filter(definition -> villagerLevel >= definition.minimumVillagerLevel()).toList();
    }

    public static boolean isExplorerPrice(int emeraldPrice) {
        return DEFINITIONS.stream().anyMatch(definition -> definition.emeraldPrice() == emeraldPrice);
    }

    public static int searchRadius() {
        return VANILLA_SEARCH_RADIUS;
    }

    private static ExplorerMapDefinition definition(
            String id, int minimumVillagerLevel, TagKey<Structure> destination,
            String decorationId, String nameTranslationKey, int emeraldPrice, int villagerXp
    ) {
        return new ExplorerMapDefinition(id, minimumVillagerLevel, destination, decorationId,
                nameTranslationKey, emeraldPrice, villagerXp);
    }

    private static int index(int villagerLevel) {
        if (villagerLevel < 1 || villagerLevel > EXPLORER_PERCENT_BY_VILLAGER_LEVEL.length) {
            throw new IllegalArgumentException("Villager profession level must be in 1..5: " + villagerLevel);
        }
        return villagerLevel - 1;
    }

    public record ExplorerMapDefinition(
            String id,
            int minimumVillagerLevel,
            TagKey<Structure> destination,
            String decorationId,
            String nameTranslationKey,
            int emeraldPrice,
            int villagerXp
    ) {
    }
}
