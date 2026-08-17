package dev.totem.villagers.config;

import com.mojang.serialization.Codec;

/** Per-world rollout state; new worlds start with the work-backed economy enabled. */
public enum WorkBackedTradingMode {
    DISABLED("disabled"),
    ENFORCED("enforced"),
    VANILLA_ROLLBACK("vanilla_rollback");

    public static final Codec<WorkBackedTradingMode> CODEC = Codec.STRING.xmap(WorkBackedTradingMode::fromId, WorkBackedTradingMode::id);
    private final String id;

    WorkBackedTradingMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean enforcesWorkBackedTrading() {
        return this == ENFORCED;
    }

    public static WorkBackedTradingMode fromId(String id) {
        for (WorkBackedTradingMode mode : values()) {
            if (mode.id.equals(id)) return mode;
        }
        throw new IllegalArgumentException("Unknown work-backed trading mode: " + id);
    }
}
