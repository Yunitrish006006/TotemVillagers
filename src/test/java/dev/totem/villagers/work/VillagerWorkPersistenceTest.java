package dev.totem.villagers.work;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWorkPersistenceTest {
    @Test
    void completeStateRoundTripsThroughSavedDataNbtCodec() {
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000101");
        VillagerWorkState state = new VillagerWorkState(
                VillagerWorkState.CURRENT_SCHEMA_VERSION,
                villager,
                Map.of("minecraft:bread", 5),
                Map.of(),
                Optional.of(new ActiveWork("totem:farmer_bread", WorkSource.WORKSHOP, 120L, 4)),
                Optional.of(new TradeDiagnostic("totem:farmer_bread", WorkSource.WORKSHOP, 4, "awaiting input"))
        );

        DataResult<Tag> encoded = VillagerWorkState.CODEC.encodeStart(NbtOps.INSTANCE, state);
        Tag tag = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().orElseThrow().message()));
        DataResult<VillagerWorkState> decoded = VillagerWorkState.CODEC.parse(NbtOps.INSTANCE, tag);

        assertEquals(state, decoded.result().orElseThrow(() -> new AssertionError(decoded.error().orElseThrow().message())));
    }

    @Test
    void componentVariantStockRoundTripsWithoutCollapsingIntoTheBaseItem() {
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000105");
        StockVariantKey variant = new StockVariantKey("minecraft:leather_helmet", "{minecraft:dyed_color:11546150}");
        VillagerWorkState state = new VillagerWorkState(
                VillagerWorkState.CURRENT_SCHEMA_VERSION, villager, Map.of(), Map.of(variant, 2),
                Optional.empty(), Optional.empty()
        );

        DataResult<Tag> encoded = VillagerWorkState.CODEC.encodeStart(NbtOps.INSTANCE, state);
        Tag tag = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().orElseThrow().message()));
        VillagerWorkState decoded = VillagerWorkState.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow(() -> new AssertionError("could not decode variant stock"));

        assertTrue(decoded.merchantStock().isEmpty());
        assertEquals(Map.of(variant, 2), decoded.variantMerchantStock());
    }

    @Test
    void legacyWorkChestFieldIsReadThenDroppedOnTheNextSave() {
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000106");
        VillagerWorkState clean = VillagerWorkState.empty(villager);
        CompoundTag legacy = (CompoundTag) VillagerWorkState.CODEC.encodeStart(NbtOps.INSTANCE, clean)
                .result().orElseThrow();
        legacy.putInt("schema_version", 2);
        VillageWorkChestLink link = new VillageWorkChestLink("minecraft:overworld", 340L,
                UUID.fromString("00000000-0000-0000-0000-000000000107"), Set.of(villager), Set.of("minecraft:wheat"));
        legacy.put("work_chest", VillageWorkChestLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().orElseThrow());

        VillagerWorkState migrated = VillagerWorkState.CODEC.parse(NbtOps.INSTANCE, legacy).result().orElseThrow();
        CompoundTag saved = (CompoundTag) VillagerWorkState.CODEC.encodeStart(NbtOps.INSTANCE, migrated)
                .result().orElseThrow();

        assertEquals(villager, migrated.villagerId());
        assertTrue(!saved.contains("work_chest"));
    }

    @Test
    void savedDataInitialisesNewVillagersWithNoFreeStock() {
        UUID villager = UUID.fromString("00000000-0000-0000-0000-000000000103");
        VillagerWorkSavedData data = new VillagerWorkSavedData();

        VillagerWorkState state = data.getOrCreate(villager);

        assertEquals(villager, state.villagerId());
        assertTrue(state.merchantStock().isEmpty());
        assertEquals(state, data.get(villager).orElseThrow());
    }

    @Test
    void worldTargetSurvivesTheActiveWorkCodecRoundTrip() {
        ActiveWork active = new ActiveWork("totem:miner_stone", WorkSource.WORLD, 180L, 2,
                Optional.of(new WorldWorkTarget("minecraft:overworld", 12345L)));
        DataResult<Tag> encoded = ActiveWork.CODEC.encodeStart(NbtOps.INSTANCE, active);
        Tag tag = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().orElseThrow().message()));

        assertEquals(active, ActiveWork.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow(() -> new AssertionError("could not decode active work")));
    }

    @Test
    void entityTargetSurvivesTheActiveWorkCodecRoundTrip() {
        UUID sheep = UUID.fromString("00000000-0000-0000-0000-000000000104");
        ActiveWork active = new ActiveWork("totem:shepherd_white_wool", WorkSource.WORLD, 200L, 1,
                Optional.of(WorldWorkTarget.entity("minecraft:overworld", sheep)));
        DataResult<Tag> encoded = ActiveWork.CODEC.encodeStart(NbtOps.INSTANCE, active);
        Tag tag = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().orElseThrow().message()));

        assertEquals(active, ActiveWork.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow(() -> new AssertionError("could not decode entity target")));
    }
}
