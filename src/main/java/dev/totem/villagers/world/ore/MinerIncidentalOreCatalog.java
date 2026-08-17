package dev.totem.villagers.world.ore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** Immutable weighted catalogue; one mining action consumes one roll and can select at most one ore. */
public final class MinerIncidentalOreCatalog {
    public static final int ROLL_SCALE = 10_000;

    private final List<MinerIncidentalOreRule> rules;

    public MinerIncidentalOreCatalog(Collection<MinerIncidentalOreRule> rules) {
        Objects.requireNonNull(rules, "rules");
        List<MinerIncidentalOreRule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparing(MinerIncidentalOreRule::id));
        Set<String> ids = new HashSet<>();
        for (MinerIncidentalOreRule rule : ordered) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate incidental-ore rule id: " + rule.id());
            }
        }
        validateConservativeChanceCeilings(ordered);
        this.rules = List.copyOf(ordered);
    }

    /**
     * Selects zero or one eligible rule. Predicates receive tag identifiers so this model remains independently
     * testable while production asks Minecraft's live block and biome tags.
     */
    public Optional<MinerIncidentalOreRule> select(
            String dimension,
            int y,
            Predicate<String> substrateMatches,
            Predicate<String> biomeMatches,
            IntSupplier roll
    ) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(substrateMatches, "substrateMatches");
        Objects.requireNonNull(biomeMatches, "biomeMatches");
        Objects.requireNonNull(roll, "roll");
        List<WeightedRule> eligible = eligible(dimension, y, substrateMatches, biomeMatches);
        int total = eligible.stream().mapToInt(WeightedRule::chance).sum();
        if (total == 0) {
            return Optional.empty();
        }
        if (total > ROLL_SCALE) {
            throw new IllegalStateException("Eligible incidental-ore rules exceed 100% at Y=" + y + ": " + total);
        }
        int selectedRoll = roll.getAsInt();
        if (selectedRoll < 0 || selectedRoll >= ROLL_SCALE) {
            throw new IllegalArgumentException("Incidental-ore roll must be between 0 and 9999");
        }
        int cumulative = 0;
        for (WeightedRule weighted : eligible) {
            cumulative += weighted.chance();
            if (selectedRoll < cumulative) {
                return Optional.of(weighted.rule());
            }
        }
        return Optional.empty();
    }

    public int totalChance(
            String dimension,
            int y,
            Predicate<String> substrateMatches,
            Predicate<String> biomeMatches
    ) {
        return eligible(dimension, y, substrateMatches, biomeMatches).stream()
                .mapToInt(WeightedRule::chance).sum();
    }

    public List<MinerIncidentalOreRule> snapshot() {
        return rules;
    }

    /**
     * Biome-tagged profiles are summed conservatively as though every tag overlapped. A bad data pack is rejected
     * during reload instead of waiting until a Miner happens to hit the invalid height and crashing that work pass.
     */
    private static void validateConservativeChanceCeilings(List<MinerIncidentalOreRule> rules) {
        Map<RuleGroup, List<MinerIncidentalOreRule>> grouped = new LinkedHashMap<>();
        for (MinerIncidentalOreRule rule : rules) {
            grouped.computeIfAbsent(new RuleGroup(rule.dimension(), rule.substrateTag()), ignored -> new ArrayList<>())
                    .add(rule);
        }
        for (Map.Entry<RuleGroup, List<MinerIncidentalOreRule>> entry : grouped.entrySet()) {
            for (int y = -4_096; y <= 4_096; y++) {
                int exactY = y;
                int total = entry.getValue().stream().mapToInt(rule -> rule.chanceAt(exactY)).sum();
                if (total > ROLL_SCALE) {
                    throw new IllegalArgumentException("Incidental-ore rules exceed 100% for "
                            + entry.getKey().dimension() + " / " + entry.getKey().substrateTag()
                            + " at Y=" + y + ": " + total);
                }
            }
        }
    }

    private List<WeightedRule> eligible(
            String dimension,
            int y,
            Predicate<String> substrateMatches,
            Predicate<String> biomeMatches
    ) {
        return rules.stream()
                .filter(rule -> dimension.equals(rule.dimension()))
                .filter(rule -> substrateMatches.test(rule.substrateTag()))
                .filter(rule -> rule.biomeTag().isBlank() || biomeMatches.test(rule.biomeTag()))
                .map(rule -> new WeightedRule(rule, rule.chanceAt(y)))
                .filter(weighted -> weighted.chance() > 0)
                .toList();
    }

    private record WeightedRule(MinerIncidentalOreRule rule, int chance) {
    }

    private record RuleGroup(String dimension, String substrateTag) {
    }
}
