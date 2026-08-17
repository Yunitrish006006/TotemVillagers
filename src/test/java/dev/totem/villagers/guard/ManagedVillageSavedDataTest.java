package dev.totem.villagers.guard;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedVillageSavedDataTest {
    @Test
    void ownerCanUpdateOneGuardVillageButAnotherOwnerCannotClaimIt() {
        UUID village = UUID.fromString("00000000-0000-0000-0000-000000000901");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000902");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000903");
        UUID guard = UUID.fromString("00000000-0000-0000-0000-000000000904");
        ManagedVillageSavedData data = new ManagedVillageSavedData();
        ManagedVillageState initial = state(village, owner, guard, 12L);

        assertTrue(data.registerOrUpdate(initial, owner));
        assertEquals(initial, data.getByGuard(guard).orElseThrow());
        assertTrue(data.registerOrUpdate(state(village, owner, guard, 18L), owner));
        assertEquals(18L, data.get(village).orElseThrow().post().packedConstructionPad());
        assertFalse(data.registerOrUpdate(state(village, other, guard, 18L), other));
    }

    private static ManagedVillageState state(UUID village, UUID owner, UUID guard, long pad) {
        return new ManagedVillageState(new GuardPost(village, owner,
                "minecraft:overworld", pad), guard, Set.of(), Optional.empty());
    }
}
