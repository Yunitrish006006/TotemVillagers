package dev.totem.villagers.config;

/** Durable per-world control plane. Merchant stock is retained when mode changes. */
public record WorkBackedTradingSettings(int schemaVersion, WorkBackedTradingMode mode) {
    /** Schema 2 makes the work-backed village economy the default. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public WorkBackedTradingSettings {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported work-backed settings schema: " + schemaVersion);
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
    }

    public static WorkBackedTradingSettings defaults() {
        return new WorkBackedTradingSettings(CURRENT_SCHEMA_VERSION, WorkBackedTradingMode.ENFORCED);
    }
}
