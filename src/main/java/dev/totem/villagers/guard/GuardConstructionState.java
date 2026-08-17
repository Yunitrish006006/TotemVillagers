package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.work.ItemAmount;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reserved materials remain held until the entire construct commits or cancellation returns the unplaced inputs. */
public record GuardConstructionState(UUID reservationId, String orderId, List<ItemAmount> reservedInputs, int placedSteps) {
    public static final Codec<GuardConstructionState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            net.minecraft.core.UUIDUtil.CODEC.fieldOf("reservation").forGetter(GuardConstructionState::reservationId),
            Codec.STRING.fieldOf("order").forGetter(GuardConstructionState::orderId),
            ItemAmount.CODEC.listOf().fieldOf("reserved_inputs").forGetter(GuardConstructionState::reservedInputs),
            Codec.INT.optionalFieldOf("placed_steps", 0).forGetter(GuardConstructionState::placedSteps)
    ).apply(instance, GuardConstructionState::new));

    public GuardConstructionState {
        Objects.requireNonNull(reservationId, "reservationId");
        if (orderId == null || !orderId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("orderId must be a namespaced identifier");
        }
        reservedInputs = List.copyOf(reservedInputs);
        if (reservedInputs.isEmpty() || placedSteps < 0) {
            throw new IllegalArgumentException("Guard reservation must have materials and a non-negative step count");
        }
    }

    public GuardConstructionState withPlacedSteps(int nextSteps) {
        return new GuardConstructionState(reservationId, orderId, reservedInputs, nextSteps);
    }
}
