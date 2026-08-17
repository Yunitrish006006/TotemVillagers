package dev.totem.villagers.trade;

import java.util.Map;

/** Converts persisted server diagnostics into stable, localisable client reason keys. */
public final class TradeSnapshotReason {
    private static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("unmapped sell offer", "unmapped"),
            Map.entry("awaiting work stock", "awaiting_stock"),
            Map.entry("no permitted source", "no_source"),
            Map.entry("inputs unavailable", "inputs_unavailable"),
            Map.entry("input no longer accepted", "input_not_accepted"),
            Map.entry("work chest link removed", "work_chest_unlinked"),
            Map.entry("work chest cannot return crafting remainder", "return_unavailable"),
            Map.entry("job site recipe rejected", "job_site_rejected"),
            Map.entry("travelling to job site", "travelling"),
            Map.entry("job site changed", "job_site_changed"),
            Map.entry("source changed", "source_changed"),
            Map.entry("world target changed", "target_changed"),
            Map.entry("world target rejected", "target_rejected"),
            Map.entry("flock target rejected", "target_rejected"),
            Map.entry("fishing catch rejected", "target_rejected"),
            Map.entry("order removed", "order_removed"),
            Map.entry("profession changed", "profession_changed"),
            Map.entry("danger", "danger"),
            Map.entry("sleep", "sleep"),
            Map.entry("raid", "raid"),
            Map.entry("villager removed", "villager_removed"),
            Map.entry("chunk unloaded", "chunk_unloaded")
    );

    private TradeSnapshotReason() {
    }

    public static String codeFor(String persistedReason) {
        if (persistedReason == null || persistedReason.isBlank()) {
            return "";
        }
        int separator = persistedReason.lastIndexOf(": ");
        String suffix = separator < 0 ? persistedReason : persistedReason.substring(separator + 2);
        return CODES.getOrDefault(suffix, "blocked");
    }
}
