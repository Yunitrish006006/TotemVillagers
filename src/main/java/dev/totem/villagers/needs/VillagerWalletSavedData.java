package dev.totem.villagers.needs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** World-persistent village currency; no trade or food purchase is funded for free. */
public final class VillagerWalletSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<VillagerWalletSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(VillagerWalletSavedData::dataVersion),
            VillagerWallet.CODEC.listOf().optionalFieldOf("wallets", List.of()).forGetter(VillagerWalletSavedData::walletList)
    ).apply(instance, VillagerWalletSavedData::new));
    public static final SavedDataType<VillagerWalletSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_wallets"),
            VillagerWalletSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, Integer> balances = new LinkedHashMap<>();

    public VillagerWalletSavedData() {
        this(DATA_VERSION, List.of());
    }

    private VillagerWalletSavedData(int dataVersion, List<VillagerWallet> persistedWallets) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedWallets.forEach(wallet -> balances.putIfAbsent(wallet.villagerId(), wallet.emeralds()));
    }

    public static VillagerWalletSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized int balance(UUID villagerId) {
        return balances.getOrDefault(villagerId, 0);
    }

    public synchronized void credit(UUID villagerId, int emeralds) {
        Objects.requireNonNull(villagerId, "villagerId");
        if (emeralds < 1) {
            throw new IllegalArgumentException("emerald credit must be positive");
        }
        int next = Math.addExact(balance(villagerId), emeralds);
        balances.put(villagerId, next);
        setDirty();
    }

    public synchronized boolean spend(UUID villagerId, int emeralds) {
        Objects.requireNonNull(villagerId, "villagerId");
        if (emeralds < 1) {
            throw new IllegalArgumentException("emerald spend must be positive");
        }
        int before = balance(villagerId);
        if (before < emeralds) {
            return false;
        }
        int next = before - emeralds;
        if (next == 0) {
            balances.remove(villagerId);
        } else {
            balances.put(villagerId, next);
        }
        setDirty();
        return true;
    }

    public synchronized Map<UUID, Integer> snapshot() {
        return Map.copyOf(balances);
    }

    /** Removes a migrated legacy wallet after its emeralds become physical items. */
    public synchronized void clear(UUID villagerId) {
        if (balances.remove(villagerId) != null) {
            setDirty();
        }
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<VillagerWallet> walletList() {
        return balances.entrySet().stream().map(entry -> new VillagerWallet(entry.getKey(), entry.getValue())).toList();
    }
}
