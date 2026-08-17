package dev.totem.villagers.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaVillageBlueprintsTest {
    @Test
    void onlyFinalVanillaVillageHouseTemplatesAreAllowed() {
        assertTrue(VanillaVillageBlueprints.isAllowedTemplateId("minecraft:village/plains/houses/plains_small_house_1"));
        assertTrue(VanillaVillageBlueprints.isAllowedTemplateId("minecraft:village/snowy/houses/snowy_small_house_1"));
        assertFalse(VanillaVillageBlueprints.isAllowedTemplateId("totem:village/plains/houses/plains_small_house_1"));
        assertFalse(VanillaVillageBlueprints.isAllowedTemplateId("minecraft:village/plains/streets/corner_01"));
        assertFalse(VanillaVillageBlueprints.isAllowedTemplateId("minecraft:village/plains/zombie/houses/plains_small_house_1"));
        assertFalse(VanillaVillageBlueprints.isAllowedTemplateId("minecraft:village/plains/houses/zombie"));
    }
}
