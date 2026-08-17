package dev.totem.villagers.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeSnapshotPayloadTest {
    @Test
    void roundTripsTheServerOwnedOfferState() {
        TradeSnapshotPayload expected = new TradeSnapshotPayload(7,
                UUID.fromString("00000000-0000-0000-0000-000000000701"),
                List.of(new TradeSnapshotPayload.Offer(2, "minecraft:bread", 3, 6,
                        "workshop", 14, 40, "awaiting_stock",
                        List.of(new TradeSnapshotPayload.RecipeInput("minecraft:wheat", 3)))),
                List.of(new TradeSnapshotPayload.WorkInventorySlot(0, "minecraft:wheat", 32),
                        new TradeSnapshotPayload.WorkInventorySlot(26, "minecraft:bread", 3)),
                List.of(new TradeSnapshotPayload.ReservedMaterial("minecraft:iron_block", 4)),
                java.util.Optional.of(new TradeSnapshotPayload.WorkZoneStatus("totem:miner", "outside",
                        "00000000-0000-0000-0000-000000000702",
                        java.util.Optional.of(new TradeSnapshotPayload.WorkZoneBoundary("minecraft:overworld",
                                -8, 60, -8, 8, 80, 8)))),
                java.util.Optional.of(new TradeSnapshotPayload.GuardPostStatus("constructing", 1, 2, 4,
                        java.util.Optional.of(new TradeSnapshotPayload.GuardPostLocation(
                                "00000000-0000-0000-0000-000000000703", "minecraft:overworld", 4, 64, 4)),
                        java.util.Optional.of(new TradeSnapshotPayload.GuardConstructionProgress(
                                "totem:iron_golem", 2, 5)))));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TradeSnapshotPayload.CODEC.encode(buffer, expected);
            assertEquals(expected, TradeSnapshotPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNegativeCountsAndOversizedOfferLists() {
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.Offer(
                0, "minecraft:bread", -1, 1, "", 0, 0, ""));
        List<TradeSnapshotPayload.Offer> entries = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> new TradeSnapshotPayload.Offer(index, "minecraft:bread", 0, 1, "", 0, 0, ""))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload(
                1, UUID.randomUUID(), entries));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.WorkInventorySlot(
                27, "minecraft:wheat", 1));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.ReservedMaterial(
                "minecraft:iron_block", 0));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.WorkZoneStatus(
                "totem:miner", "unknown", "", java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.WorkZoneBoundary(
                "minecraft:overworld", 8, 60, 8, 0, 80, 0));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.GuardPostStatus(
                "constructing", 0, 1, 0, java.util.Optional.empty(), java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TradeSnapshotPayload.GuardConstructionProgress(
                "totem:iron_golem", -1, 5));
    }
}
