package dev.totem.villagers.needs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent player-style nutrition, deliberately separate from vanilla breeding food. */
public final class VillagerNutritionSavedData extends SavedData {
    private static final Codec<NutritionEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(NutritionEntry::villager),
            Codec.INT.fieldOf("food_level").forGetter(entry -> entry.state().foodLevel()),
            // The optional values migrate the old food-level-only format without invalidating existing worlds.
            Codec.FLOAT.optionalFieldOf("saturation_level", VillagerNutrition.DEFAULT_SATURATION_LEVEL)
                    .forGetter(entry -> entry.state().saturationLevel()),
            Codec.FLOAT.optionalFieldOf("exhaustion_level", 0.0F)
                    .forGetter(entry -> entry.state().exhaustionLevel()),
            Codec.INT.optionalFieldOf("tick_timer", 0).forGetter(entry -> entry.state().tickTimer())
    ).apply(instance, (villager, food, saturation, exhaustion, timer) ->
            new NutritionEntry(villager, NutritionState.sanitized(food, saturation, exhaustion, timer))));
    private static final Codec<VillagerNutritionSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRY_CODEC.listOf().optionalFieldOf("villagers", List.of()).forGetter(VillagerNutritionSavedData::entries)
    ).apply(instance, VillagerNutritionSavedData::new));
    public static final SavedDataType<VillagerNutritionSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_nutrition"),
            VillagerNutritionSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, NutritionState> nutrition = new LinkedHashMap<>();

    public VillagerNutritionSavedData() {
    }

    private VillagerNutritionSavedData(List<NutritionEntry> entries) {
        entries.forEach(entry -> nutrition.putIfAbsent(entry.villager(), entry.state()));
    }

    public static VillagerNutritionSavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized NutritionState state(UUID villager) {
        return nutrition.getOrDefault(villager, NutritionState.initial());
    }

    public synchronized int foodLevel(UUID villager) {
        return state(villager).foodLevel();
    }

    public synchronized void setState(UUID villager, NutritionState state) {
        NutritionState next = NutritionState.sanitized(state.foodLevel(), state.saturationLevel(),
                state.exhaustionLevel(), state.tickTimer());
        NutritionState previous = nutrition.put(villager, next);
        if (!next.equals(previous)) {
            setDirty();
        }
    }

    public synchronized void setFoodLevel(UUID villager, int foodLevel) {
        NutritionState current = state(villager);
        setState(villager, current.withFoodLevel(foodLevel));
    }

    public synchronized void reset(UUID villager) {
        setState(villager, NutritionState.initial());
    }

    public synchronized void remove(UUID villager) {
        if (nutrition.remove(villager) != null) {
            setDirty();
        }
    }

    private synchronized List<NutritionEntry> entries() {
        return nutrition.entrySet().stream().map(entry -> new NutritionEntry(entry.getKey(), entry.getValue())).toList();
    }

    public record NutritionState(int foodLevel, float saturationLevel, float exhaustionLevel, int tickTimer) {
        public static NutritionState initial() {
            return new NutritionState(VillagerNutrition.MAX_FOOD_LEVEL,
                    VillagerNutrition.DEFAULT_SATURATION_LEVEL, 0.0F, 0);
        }

        public static NutritionState sanitized(int food, float saturation, float exhaustion, int timer) {
            int safeFood = Math.max(0, Math.min(VillagerNutrition.MAX_FOOD_LEVEL, food));
            float safeSaturation = Math.max(0.0F, Math.min(VillagerNutrition.MAX_FOOD_LEVEL, saturation));
            float safeExhaustion = Math.max(0.0F, Math.min(VillagerNutrition.MAX_EXHAUSTION_LEVEL, exhaustion));
            return new NutritionState(safeFood, safeSaturation, safeExhaustion, Math.max(0, timer));
        }

        public NutritionState withFoodLevel(int food) {
            return sanitized(food, saturationLevel, exhaustionLevel, tickTimer);
        }
    }

    private record NutritionEntry(UUID villager, NutritionState state) {
    }
}
