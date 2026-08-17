package dev.totem.villagers.work;

import com.mojang.serialization.Codec;

/** The authoritative origin of merchant stock created by a work order. */
public enum WorkSource {
    WORLD("world"),
    WORKSHOP("workshop"),
    /** A bounded, material-backed enchanting-table action. */
    ENCHANTING("enchanting");

    public static final Codec<WorkSource> CODEC = Codec.STRING.xmap(WorkSource::fromId, WorkSource::id);

    private final String id;

    WorkSource(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static WorkSource fromId(String id) {
        for (WorkSource source : values()) {
            if (source.id.equals(id)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown work source: " + id);
    }
}
