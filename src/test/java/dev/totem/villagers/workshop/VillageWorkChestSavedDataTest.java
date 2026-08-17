package dev.totem.villagers.workshop;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageWorkChestSavedDataTest {
    @Test
    void onlyOwnerCanChangeLinksOrReplaceRegistration() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000303");
        WorkChestKey key = new WorkChestKey("minecraft:overworld", 5L);
        VillageWorkChest chest = new VillageWorkChest(key, owner, Set.of(), Set.of(), Set.of("minecraft:wheat"));
        VillageWorkChestSavedData data = new VillageWorkChestSavedData();

        assertTrue(data.register(chest, owner));
        assertFalse(data.linkVillager(key, other, villager, true));
        assertTrue(data.linkVillager(key, owner, villager, true));
        assertTrue(data.get(key).orElseThrow().linkedVillagers().contains(villager));
    }
}
