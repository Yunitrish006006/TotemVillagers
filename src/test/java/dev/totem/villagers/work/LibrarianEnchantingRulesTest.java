package dev.totem.villagers.work;

import dev.totem.villagers.runtime.VillagerLibrarianEnchantingRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibrarianEnchantingRulesTest {
    @Test
    void professionTiersMapToTheRequestedPlayerEnchantingLevels() {
        assertEquals(6, LibrarianEnchantingRules.enchantingPower(1));
        assertEquals(12, LibrarianEnchantingRules.enchantingPower(2));
        assertEquals(18, LibrarianEnchantingRules.enchantingPower(3));
        assertEquals(24, LibrarianEnchantingRules.enchantingPower(4));
        assertEquals(30, LibrarianEnchantingRules.enchantingPower(5));
        assertEquals(0, LibrarianEnchantingRules.treasurePercent(1));
        assertEquals(8, LibrarianEnchantingRules.treasurePercent(5));
        assertEquals(1, LibrarianEnchantingRules.lapisCost(1));
        assertEquals(3, LibrarianEnchantingRules.lapisCost(5));
    }

    @Test
    void librarianTableOrderConsumesOneBookAndTierAppropriateLapis() {
        WorkOrder expert = VillagerLibrarianEnchantingRuntime.orderForVillagerLevel(4);
        assertEquals("totem:librarian_enchanting_4", expert.id());
        assertEquals(List.of(new ItemAmount("minecraft:book", 1), new ItemAmount("minecraft:lapis_lazuli", 3)),
                expert.requiredInputs());
        assertTrue(expert.allowedSources().contains(WorkSource.ENCHANTING));
    }

    @Test
    void invalidProfessionTierIsRejectedRatherThanSilentlyClamped() {
        assertThrows(IllegalArgumentException.class, () -> LibrarianEnchantingRules.enchantingPower(0));
        assertThrows(IllegalArgumentException.class, () -> LibrarianEnchantingRules.treasurePercent(6));
    }

}
