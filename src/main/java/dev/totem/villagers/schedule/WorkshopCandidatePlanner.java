package dev.totem.villagers.schedule;

import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkSource;
import java.util.List;
import java.util.Objects;

/**
 * Converts a verified native work station into scheduler candidates. Material
 * eligibility is checked against the villager's own persistent work inventory
 * immediately before work is scheduled and committed.
 */
public final class WorkshopCandidatePlanner {
    public List<WorkCandidate> candidates(
            WorkOrderCatalog catalog,
            String professionId,
            boolean jobSiteValid
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(professionId, "professionId");
        if (!jobSiteValid) {
            return List.of();
        }
        return catalog.snapshot().values().stream()
                .filter(order -> order.professionId().equals(professionId))
                .filter(order -> order.allowedSources().contains(WorkSource.WORKSHOP))
                .map(order -> new WorkCandidate(order.id(), WorkSource.WORKSHOP, 100))
                .sorted(java.util.Comparator.comparing(WorkCandidate::orderId))
                .toList();
    }
}
