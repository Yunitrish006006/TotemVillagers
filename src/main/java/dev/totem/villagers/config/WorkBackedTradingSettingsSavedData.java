package dev.totem.villagers.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Objects;

/** The mode is world data rather than a global file, so backup and rollback are scoped correctly. */
public final class WorkBackedTradingSettingsSavedData extends SavedData {
    public static final Codec<WorkBackedTradingSettingsSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", WorkBackedTradingSettings.CURRENT_SCHEMA_VERSION)
                    .forGetter(WorkBackedTradingSettingsSavedData::schemaVersion),
            WorkBackedTradingMode.CODEC.optionalFieldOf("mode", WorkBackedTradingMode.ENFORCED)
                    .forGetter(WorkBackedTradingSettingsSavedData::mode)
    ).apply(instance, WorkBackedTradingSettingsSavedData::fromPersisted));
    public static final SavedDataType<WorkBackedTradingSettingsSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "work_backed_trading_settings"),
            WorkBackedTradingSettingsSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int schemaVersion;
    private WorkBackedTradingMode mode;

    public WorkBackedTradingSettingsSavedData() {
        this(WorkBackedTradingSettings.CURRENT_SCHEMA_VERSION, WorkBackedTradingMode.ENFORCED);
    }

    /** Decodes older saved worlds and applies the one-time schema-2 auto-start migration. */
    static WorkBackedTradingSettingsSavedData fromPersisted(int schemaVersion, WorkBackedTradingMode mode) {
        return new WorkBackedTradingSettingsSavedData(schemaVersion, mode);
    }

    private WorkBackedTradingSettingsSavedData(int schemaVersion, WorkBackedTradingMode mode) {
        WorkBackedTradingMode persistedMode = Objects.requireNonNull(mode, "mode");
        boolean migrateLegacyDefault = schemaVersion < WorkBackedTradingSettings.CURRENT_SCHEMA_VERSION
                && persistedMode == WorkBackedTradingMode.DISABLED;
        this.schemaVersion = Math.max(schemaVersion, WorkBackedTradingSettings.CURRENT_SCHEMA_VERSION);
        this.mode = migrateLegacyDefault ? WorkBackedTradingMode.ENFORCED : persistedMode;
        if (migrateLegacyDefault) {
            setDirty();
        }
    }

    public static WorkBackedTradingSettingsSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized WorkBackedTradingSettings settings() {
        return new WorkBackedTradingSettings(schemaVersion, mode);
    }

    public synchronized void setMode(WorkBackedTradingMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (this.mode != mode) {
            this.mode = mode;
            setDirty();
        }
    }

    private int schemaVersion() {
        return schemaVersion;
    }

    private synchronized WorkBackedTradingMode mode() {
        return mode;
    }
}
