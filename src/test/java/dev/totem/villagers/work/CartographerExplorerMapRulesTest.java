package dev.totem.villagers.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartographerExplorerMapRulesTest {
    @Test
    void professionTierControlsExplorerMapChanceAndDestinationSet() {
        assertEquals(0, CartographerExplorerMapRules.explorerPercent(1));
        assertEquals(1, CartographerExplorerMapRules.explorerPercent(2));
        assertEquals(2, CartographerExplorerMapRules.explorerPercent(3));
        assertEquals(4, CartographerExplorerMapRules.explorerPercent(4));
        assertEquals(8, CartographerExplorerMapRules.explorerPercent(5));
        assertTrue(CartographerExplorerMapRules.definitionsForLevel(2).stream()
                .allMatch(definition -> definition.minimumVillagerLevel() <= 2));
        assertTrue(CartographerExplorerMapRules.definitionsForLevel(5).stream()
                .anyMatch(definition -> definition.id().equals("woodland_mansion")));
    }

    @Test
    void invalidProfessionTierIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CartographerExplorerMapRules.explorerPercent(0));
        assertThrows(IllegalArgumentException.class, () -> CartographerExplorerMapRules.definitionsForLevel(6));
    }
}
