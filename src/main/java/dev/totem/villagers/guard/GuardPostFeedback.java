package dev.totem.villagers.guard;

import dev.totem.villagers.runtime.LoadedVillagerCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only, server-side Guard Post status for the player and administrator
 * views. It deliberately reads only an already loaded post chunk and never
 * starts construction or loads a dimension/chunk to produce feedback.
 */
public record GuardPostFeedback(
        State state,
        int managedGolems,
        GuardDefenceDemand demand,
        Optional<GuardPost> post,
        Optional<ConstructionProgress> construction
) {
    public static final int VILLAGE_RADIUS = 48;
    public static final int THREAT_RADIUS = 32;

    public enum State {
        UNREGISTERED("unregistered"),
        POST_UNAVAILABLE("post_unavailable"),
        DEFENCE_NEEDED("defence_needed"),
        DEFENDED("defended"),
        CONSTRUCTING("constructing");

        private final String id;

        State(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** A zero total means the persisted data-pack order is currently unavailable. */
    public record ConstructionProgress(String orderId, int placedSteps, int totalSteps) {
        public ConstructionProgress {
            if (orderId == null || orderId.isBlank() || placedSteps < 0 || totalSteps < 0) {
                throw new IllegalArgumentException("Invalid Guard construction progress");
            }
        }
    }

    public GuardPostFeedback {
        Objects.requireNonNull(state, "state");
        if (managedGolems < 0) {
            throw new IllegalArgumentException("managedGolems cannot be negative");
        }
        Objects.requireNonNull(demand, "demand");
        post = post == null ? Optional.empty() : post;
        construction = construction == null ? Optional.empty() : construction;
        if (post.isEmpty() && (construction.isPresent() || state != State.UNREGISTERED)) {
            throw new IllegalArgumentException("Registered Guard Post feedback requires a post");
        }
    }

    public static GuardPostFeedback unregistered() {
        return new GuardPostFeedback(State.UNREGISTERED, 0, GuardDefenceDemand.fromCounts(0, 0),
                Optional.empty(), Optional.empty());
    }

    public static GuardPostFeedback evaluate(MinecraftServer server, ManagedVillageState village) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(village, "village");
        GuardPost post = village.post();
        ServerLevel level = findLevel(server, post.dimensionId());
        BlockPos pad = BlockPos.of(post.packedConstructionPad());
        if (level == null || !level.isLoaded(pad)) {
            return new GuardPostFeedback(State.POST_UNAVAILABLE, village.managedGolemIds().size(),
                    GuardDefenceDemand.fromCounts(0, 0), Optional.of(post), progress(village));
        }
        int residents = (int) LoadedVillagerCache.loaded(level).stream()
                .filter(villager -> villager.isAlive()
                        && villager.distanceToSqr(Vec3.atCenterOf(pad)) <= (double) VILLAGE_RADIUS * VILLAGE_RADIUS)
                .count();
        int threats = level.getEntities(EntityTypeTest.forClass(Monster.class), monster -> monster.isAlive()
                && monster.distanceToSqr(Vec3.atCenterOf(pad)) <= (double) THREAT_RADIUS * THREAT_RADIUS).size();
        return available(village, residents, threats, progress(village));
    }

    static GuardPostFeedback available(ManagedVillageState village, int residents, int threats,
                                       Optional<ConstructionProgress> progress) {
        Objects.requireNonNull(village, "village");
        Objects.requireNonNull(progress, "progress");
        GuardDefenceDemand demand = GuardDefenceDemand.fromCounts(residents, threats);
        State state = progress.isPresent() ? State.CONSTRUCTING
                : village.managedGolemIds().size() >= demand.targetGolems() ? State.DEFENDED : State.DEFENCE_NEEDED;
        return new GuardPostFeedback(state, village.managedGolemIds().size(), demand, Optional.of(village.post()), progress);
    }

    private static Optional<ConstructionProgress> progress(ManagedVillageState village) {
        return village.construction().map(construction -> new ConstructionProgress(construction.orderId(), construction.placedSteps(),
                GuardDefenceOrderDefinitions.get(construction.orderId()).map(order -> order.placements().size()).orElse(0)));
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }
}
