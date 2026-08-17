package dev.totem.villagers.world.ore;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinerIncidentalOreCatalogTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String STONE_BASE = "minecraft:stone_ore_replaceables";
    private static final String DEEPSLATE_BASE = "minecraft:deepslate_ore_replaceables";
    private static final Path DEFAULT_RULES = Path.of(
            "src/main/resources/data/totem/totem_villagers/incidental_ores");

    @Test
    void triangularAndUniformBandsPreserveVanillaStyleHeightEdges() {
        MinerIncidentalOreBand triangle = new MinerIncidentalOreBand("triangle", -16, 112, 48, 210);
        assertEquals(0, triangle.chanceAt(-16));
        assertEquals(105, triangle.chanceAt(16));
        assertEquals(210, triangle.chanceAt(48));
        assertEquals(105, triangle.chanceAt(80));
        assertEquals(0, triangle.chanceAt(112));

        MinerIncidentalOreBand uniform = new MinerIncidentalOreBand("uniform", -64, 72, 0, 120);
        assertEquals(0, uniform.chanceAt(-65));
        assertEquals(120, uniform.chanceAt(-64));
        assertEquals(120, uniform.chanceAt(72));
        assertEquals(0, uniform.chanceAt(73));
    }

    @Test
    void defaultsUseOnlyVanillaNormalAndDeepslateReplacementFamilies() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        assertEquals(17, catalog.snapshot().size());
        for (MinerIncidentalOreRule rule : catalog.snapshot()) {
            assertTrue(rule.substrateTag().equals(STONE_BASE) || rule.substrateTag().equals(DEEPSLATE_BASE));
            if (rule.substrateTag().equals(DEEPSLATE_BASE)) {
                assertTrue(rule.oreBlock().startsWith("minecraft:deepslate_"), rule.id());
            } else {
                assertFalse(rule.oreBlock().startsWith("minecraft:deepslate_"), rule.id());
            }
        }
    }

    @Test
    void balancedTotalsPreserveDeepProfileAndStrengthenFoundingShaft() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        Predicate<String> stone = STONE_BASE::equals;
        Predicate<String> deep = DEEPSLATE_BASE::equals;
        Predicate<String> ordinaryBiome = ignored -> false;

        assertEquals(587, catalog.totalChance(OVERWORLD, 64, stone, ordinaryBiome));
        assertEquals(613, catalog.totalChance(OVERWORLD, 16, stone, ordinaryBiome));
        assertEquals(378, catalog.totalChance(OVERWORLD, -54, deep, ordinaryBiome));
        assertEquals(0, catalog.totalChance("minecraft:the_nether", 16, stone, ordinaryBiome));
        assertTrue(catalog.totalChance(OVERWORLD, 64, stone, "minecraft:is_badlands"::equals) > 587);
        assertTrue(catalog.totalChance(OVERWORLD, 64, stone, "minecraft:is_mountain"::equals) > 587);
    }

    @Test
    void y56ToolMineralsMatchVillageSimulationBalance() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        Map<String, Integer> chances = new HashMap<>();
        catalog.snapshot().stream().filter(rule -> rule.substrateTag().equals(STONE_BASE))
                .forEach(rule -> chances.merge(rule.oreBlock(), rule.chanceAt(56), Integer::sum));

        int coal = chances.getOrDefault("minecraft:coal_ore", 0);
        int copper = chances.getOrDefault("minecraft:copper_ore", 0);
        int iron = chances.getOrDefault("minecraft:iron_ore", 0);
        assertEquals(210, coal, "Y=56 coal must support four-item furnace batches");
        assertEquals(166, copper, "Copper was reduced after both simulations accumulated a large surplus");
        assertEquals(170, iron, "The founding shaft needs a dedicated iron floor for tools and shears");
        // Copper ore averages 3.5 raw copper; one coal processes four raw items in the village furnace model.
        assertTrue(coal * 8 > copper * 7 + iron * 2,
                "Expected coal capacity no longer covers the weighted copper-plus-iron input");
    }

    @Test
    void everyPossibleRollProducesExactConfiguredBucketsAndAtMostOneOre() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        Map<String, Integer> observed = new HashMap<>();
        int none = 0;
        for (int roll = 0; roll < MinerIncidentalOreCatalog.ROLL_SCALE; roll++) {
            int exactRoll = roll;
            var selected = catalog.select(OVERWORLD, -54, DEEPSLATE_BASE::equals, ignored -> false, () -> exactRoll);
            if (selected.isPresent()) {
                observed.merge(selected.orElseThrow().id(), 1, Integer::sum);
            } else {
                none++;
            }
        }

        int configuredTotal = catalog.snapshot().stream()
                .filter(rule -> rule.substrateTag().equals(DEEPSLATE_BASE) && rule.biomeTag().isBlank())
                .mapToInt(rule -> rule.chanceAt(-54)).sum();
        assertEquals(378, configuredTotal);
        assertEquals(9_622, none);
        assertEquals(configuredTotal, observed.values().stream().mapToInt(Integer::intValue).sum());
        for (MinerIncidentalOreRule rule : catalog.snapshot()) {
            if (rule.substrateTag().equals(DEEPSLATE_BASE) && rule.biomeTag().isBlank()
                    && rule.chanceAt(-54) > 0) {
                assertEquals(rule.chanceAt(-54), observed.getOrDefault(rule.id(), 0), rule.id());
            }
        }
    }

    @Test
    void millionRollSamplingStaysNearTheExactDeepDistribution() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        Random random = new Random(0x5EEDBEEFL);
        int discoveries = 0;
        int diamonds = 0;
        Map<String, Integer> observed = new HashMap<>();
        for (int sample = 0; sample < 1_000_000; sample++) {
            var selected = catalog.select(OVERWORLD, -54, DEEPSLATE_BASE::equals, ignored -> false,
                    () -> random.nextInt(MinerIncidentalOreCatalog.ROLL_SCALE));
            if (selected.isPresent()) {
                discoveries++;
                observed.merge(selected.orElseThrow().oreBlock(), 1, Integer::sum);
                if (selected.orElseThrow().oreBlock().equals("minecraft:deepslate_diamond_ore")) {
                    diamonds++;
                }
            }
        }
        assertTrue(discoveries >= 36_500 && discoveries <= 39_100,
                "Deep total drifted outside the 3.78% profile: " + discoveries);
        assertTrue(diamonds >= 450 && diamonds <= 950,
                "Deep diamond sampling drifted outside its initial rare profile: " + diamonds);
        assertTrue(observed.getOrDefault("minecraft:deepslate_redstone_ore", 0)
                        > observed.getOrDefault("minecraft:deepslate_iron_ore", 0));
        assertTrue(observed.getOrDefault("minecraft:deepslate_iron_ore", 0)
                        > observed.getOrDefault("minecraft:deepslate_gold_ore", 0));
        assertTrue(observed.getOrDefault("minecraft:deepslate_gold_ore", 0)
                        > observed.getOrDefault("minecraft:deepslate_diamond_ore", 0));
    }

    @Test
    void millionRollFoundingShaftSampleFavoursFuelThenIronThenCopperVeins() throws IOException {
        MinerIncidentalOreCatalog catalog = defaults();
        Random random = new Random(0x51A7F00DL);
        Map<String, Integer> observed = new HashMap<>();
        int discoveries = 0;
        for (int sample = 0; sample < 1_000_000; sample++) {
            int y = 48 + sample % 17;
            var selected = catalog.select(OVERWORLD, y, STONE_BASE::equals, ignored -> false,
                    () -> random.nextInt(MinerIncidentalOreCatalog.ROLL_SCALE));
            if (selected.isPresent()) {
                discoveries++;
                observed.merge(selected.orElseThrow().oreBlock(), 1, Integer::sum);
            }
        }

        assertTrue(discoveries >= 58_000 && discoveries <= 60_900,
                "Founding-shaft total drifted outside the 5.94% profile: " + discoveries);
        assertTrue(observed.getOrDefault("minecraft:coal_ore", 0)
                > observed.getOrDefault("minecraft:iron_ore", 0));
        assertTrue(observed.getOrDefault("minecraft:iron_ore", 0)
                > observed.getOrDefault("minecraft:copper_ore", 0));
        assertTrue(observed.getOrDefault("minecraft:copper_ore", 0)
                > observed.getOrDefault("minecraft:lapis_ore", 0));
        assertFalse(observed.containsKey("minecraft:diamond_ore"));
        assertFalse(observed.containsKey("minecraft:gold_ore"));
        assertFalse(observed.containsKey("minecraft:redstone_ore"));
    }

    @Test
    void invalidOrOverlappingConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinerIncidentalOreBand("triangle", 0, 32, 48, 10));
        MinerIncidentalOreRule duplicateA = rule("totem:duplicate", "minecraft:coal_ore", 100);
        MinerIncidentalOreRule duplicateB = rule("totem:duplicate", "minecraft:iron_ore", 100);
        assertThrows(IllegalArgumentException.class,
                () -> new MinerIncidentalOreCatalog(List.of(duplicateA, duplicateB)));

        assertThrows(IllegalArgumentException.class, () -> new MinerIncidentalOreCatalog(List.of(
                rule("totem:a", "minecraft:coal_ore", 6_000),
                rule("totem:b", "minecraft:iron_ore", 6_000))));
    }

    private static MinerIncidentalOreRule rule(String id, String ore, int chance) {
        return new MinerIncidentalOreRule(id, OVERWORLD, STONE_BASE, "", ore,
                List.of(new MinerIncidentalOreBand("uniform", 0, 0, 0, chance)));
    }

    private static MinerIncidentalOreCatalog defaults() throws IOException {
        List<MinerIncidentalOreRule> rules = new ArrayList<>();
        try (Stream<Path> files = Files.list(DEFAULT_RULES)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    rules.add(MinerIncidentalOreRule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
                            .getOrThrow(AssertionError::new));
                }
            }
        }
        return new MinerIncidentalOreCatalog(rules);
    }
}
