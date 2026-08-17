package dev.totem.villagers.schedule;

import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;

import java.util.Optional;

/** A bounded, already reachable and protection-approved source considered this tick. */
public record WorkCandidate(String orderId, WorkSource source, int priority, Optional<WorldWorkTarget> worldTarget) {
    public WorkCandidate(String orderId, WorkSource source, int priority) {
        this(orderId, source, priority, Optional.empty());
    }

    public WorkCandidate {
        if (orderId == null || !orderId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("orderId must be a namespaced identifier");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority cannot be negative");
        }
        worldTarget = worldTarget == null ? Optional.empty() : worldTarget;
        if ((source == WorkSource.WORLD || source == WorkSource.ENCHANTING) && worldTarget.isEmpty()) {
            throw new IllegalArgumentException("Targeted work candidates require a persistent target");
        }
        if (source != WorkSource.WORLD && source != WorkSource.ENCHANTING && worldTarget.isPresent()) {
            throw new IllegalArgumentException("Only targeted work candidates may carry a target");
        }
    }
}
