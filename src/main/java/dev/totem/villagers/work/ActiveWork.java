package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;
import java.util.Optional;

/** Server-side progress record for one cancellable work action. */
public record ActiveWork(String orderId, WorkSource source, long startedAtTick, int elapsedTicks, Optional<WorldWorkTarget> worldTarget) {
    public static final Codec<ActiveWork> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("order").forGetter(ActiveWork::orderId),
            WorkSource.CODEC.fieldOf("source").forGetter(ActiveWork::source),
            Codec.LONG.fieldOf("started_at_tick").forGetter(ActiveWork::startedAtTick),
            Codec.INT.fieldOf("elapsed_ticks").forGetter(ActiveWork::elapsedTicks),
            WorldWorkTarget.CODEC.optionalFieldOf("world_target").forGetter(ActiveWork::worldTarget)
    ).apply(instance, ActiveWork::new));

    public ActiveWork(String orderId, WorkSource source, long startedAtTick, int elapsedTicks) {
        this(orderId, source, startedAtTick, elapsedTicks, Optional.empty());
    }

    public ActiveWork {
        if (orderId == null || !orderId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("orderId must be a namespaced identifier");
        }
        Objects.requireNonNull(source, "source");
        if (startedAtTick < 0 || elapsedTicks < 0) {
            throw new IllegalArgumentException("work ticks cannot be negative");
        }
        worldTarget = worldTarget == null ? Optional.empty() : worldTarget;
        if ((source == WorkSource.WORLD || source == WorkSource.ENCHANTING) && worldTarget.isEmpty()) {
            throw new IllegalArgumentException("Targeted work requires a persistent target");
        }
        if (source != WorkSource.WORLD && source != WorkSource.ENCHANTING && worldTarget.isPresent()) {
            throw new IllegalArgumentException("Only targeted work may carry a world target");
        }
    }
}
