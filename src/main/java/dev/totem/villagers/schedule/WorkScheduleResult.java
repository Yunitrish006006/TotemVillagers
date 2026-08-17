package dev.totem.villagers.schedule;

import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;

import java.util.Optional;

/** The scheduler never commits work itself; ready work must pass a second server-side commit check. */
public record WorkScheduleResult(VillagerWorkState state, Optional<WorkOrder> readyToCommit) {
}
