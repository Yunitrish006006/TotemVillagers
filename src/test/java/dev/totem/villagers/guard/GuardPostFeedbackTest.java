package dev.totem.villagers.guard;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardPostFeedbackTest {
    private static final UUID VILLAGE = UUID.fromString("00000000-0000-0000-0000-000000003001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000003002");
    private static final UUID GUARD = UUID.fromString("00000000-0000-0000-0000-000000003003");

    @Test
    void boundedDemandNeedsResidentsAndAddsOneTargetForEveryFourThreats() {
        assertEquals(0, GuardDefenceDemand.fromCounts(0, 99).targetGolems());
        assertEquals(1, GuardDefenceDemand.fromCounts(1, 0).targetGolems());
        assertEquals(2, GuardDefenceDemand.fromCounts(1, 4).targetGolems());
        assertEquals(3, GuardDefenceDemand.fromCounts(1, 99).targetGolems());
    }

    @Test
    void feedbackKeepsDemandReservationAndVisibleProgressSeparate() {
        ManagedVillageState idle = state(Set.of(), Optional.empty());
        GuardPostFeedback needed = GuardPostFeedback.available(idle, 1, 4, Optional.empty());
        assertEquals(GuardPostFeedback.State.DEFENCE_NEEDED, needed.state());
        assertEquals(2, needed.demand().targetGolems());

        GuardConstructionState reservation = new GuardConstructionState(UUID.randomUUID(), "totem:iron_golem",
                GuardDefenceOrder.VANILLA_IRON_GOLEM.requiredInputs(), 2);
        GuardPostFeedback constructing = GuardPostFeedback.available(state(Set.of(), Optional.of(reservation)), 1, 0,
                Optional.of(new GuardPostFeedback.ConstructionProgress("totem:iron_golem", 2, 5)));
        assertEquals(GuardPostFeedback.State.CONSTRUCTING, constructing.state());
        assertEquals(Optional.of(new GuardPostFeedback.ConstructionProgress("totem:iron_golem", 2, 5)),
                constructing.construction());

        GuardPostFeedback defended = GuardPostFeedback.available(state(Set.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                Optional.empty()), 1, 12, Optional.empty());
        assertEquals(GuardPostFeedback.State.DEFENDED, defended.state());
        assertTrue(defended.post().isPresent());
    }

    private static ManagedVillageState state(Set<UUID> golems, Optional<GuardConstructionState> construction) {
        return new ManagedVillageState(new GuardPost(VILLAGE, OWNER, "minecraft:overworld", 9L), GUARD, golems, construction);
    }
}
