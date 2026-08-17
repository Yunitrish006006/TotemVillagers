package dev.totem.villagers.work;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VillagerWorkStateTest {
    @Test
    void stateIsVersionedAndDefensivelyCopiesStock() {
        VillagerWorkState state = VillagerWorkState.empty(UUID.fromString("00000000-0000-0000-0000-000000000101"));

        assertEquals(VillagerWorkState.CURRENT_SCHEMA_VERSION, state.schemaVersion());
        assertThrows(UnsupportedOperationException.class, () -> state.merchantStock().put("minecraft:bread", 1));
    }

    @Test
    void invalidPersistedStockIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VillagerWorkState(
                1, UUID.randomUUID(), Map.of("minecraft:bread", 0), null, null, null
        ));
    }
}
