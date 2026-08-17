package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;

/** Server-owned explanation rendered when a sell offer is unavailable or in progress. */
public record TradeDiagnostic(String orderId, WorkSource source, int progressTicks, String blockedReason) {
    public static final Codec<TradeDiagnostic> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("order").forGetter(TradeDiagnostic::orderId),
            WorkSource.CODEC.fieldOf("source").forGetter(TradeDiagnostic::source),
            Codec.INT.fieldOf("progress_ticks").forGetter(TradeDiagnostic::progressTicks),
            Codec.STRING.optionalFieldOf("blocked_reason", "").forGetter(TradeDiagnostic::blockedReason)
    ).apply(instance, TradeDiagnostic::new));

    public TradeDiagnostic {
        if (orderId == null || !orderId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("orderId must be a namespaced identifier");
        }
        Objects.requireNonNull(source, "source");
        if (progressTicks < 0) {
            throw new IllegalArgumentException("progressTicks cannot be negative");
        }
        blockedReason = blockedReason == null ? "" : blockedReason;
    }
}
