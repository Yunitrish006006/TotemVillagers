package dev.totem.villagers.worker;

import dev.totem.villagers.work.WorkSource;

import java.util.Objects;
import java.util.Set;

/** Registry-backed role descriptor; vanilla Shepherd is extended rather than duplicated. */
public record WorkerProfession(
        String id,
        String displayKey,
        boolean vanillaProfession,
        Set<WorkSource> allowedSources
) {
    public WorkerProfession {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("id must be a namespaced identifier");
        }
        Objects.requireNonNull(displayKey, "displayKey");
        allowedSources = Set.copyOf(allowedSources);
        if (allowedSources.isEmpty()) {
            throw new IllegalArgumentException("A worker profession needs an allowed source");
        }
    }
}
