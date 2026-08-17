package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable one-shot ledger preventing chunk loads from repeating starter supplies. */
public final class VillagerStarterSupplySavedData extends SavedData {
    static final Codec<VillagerStarterSupplySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.listOf().optionalFieldOf("base_supplied", List.of()).forGetter(VillagerStarterSupplySavedData::baseEntries),
            UUIDUtil.CODEC.listOf().optionalFieldOf("profession_supplied", List.of()).forGetter(VillagerStarterSupplySavedData::professionEntries),
            UUIDUtil.CODEC.listOf().optionalFieldOf("bred_villagers", List.of()).forGetter(VillagerStarterSupplySavedData::bredEntries)
    ).apply(instance, VillagerStarterSupplySavedData::new));
    public static final SavedDataType<VillagerStarterSupplySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_starter_supplies"),
            VillagerStarterSupplySavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Set<UUID> baseSupplied = new LinkedHashSet<>();
    private final Set<UUID> professionSupplied = new LinkedHashSet<>();
    private final Set<UUID> bredVillagers = new LinkedHashSet<>();

    public VillagerStarterSupplySavedData() {
    }

    private VillagerStarterSupplySavedData(List<UUID> baseEntries, List<UUID> professionEntries,
                                           List<UUID> bredEntries) {
        baseSupplied.addAll(baseEntries);
        professionSupplied.addAll(professionEntries);
        bredVillagers.addAll(bredEntries);
    }

    public static VillagerStarterSupplySavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized boolean hasBase(UUID villager) {
        return baseSupplied.contains(villager);
    }

    public synchronized boolean hasProfessionKit(UUID villager) {
        return professionSupplied.contains(villager);
    }

    public synchronized boolean isBred(UUID villager) {
        return bredVillagers.contains(villager);
    }

    public synchronized void markBase(UUID villager) {
        if (baseSupplied.add(villager)) {
            setDirty();
        }
    }

    public synchronized void markProfessionKit(UUID villager) {
        if (professionSupplied.add(villager)) {
            setDirty();
        }
    }

    public synchronized void markBred(UUID villager) {
        if (bredVillagers.add(villager)) {
            setDirty();
        }
    }

    private synchronized List<UUID> baseEntries() {
        return List.copyOf(baseSupplied);
    }

    private synchronized List<UUID> professionEntries() {
        return List.copyOf(professionSupplied);
    }

    private synchronized List<UUID> bredEntries() {
        return List.copyOf(bredVillagers);
    }
}
