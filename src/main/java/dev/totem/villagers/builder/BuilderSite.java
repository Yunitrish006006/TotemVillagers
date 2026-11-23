package dev.totem.villagers.builder;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Objects;
import java.util.UUID;

/** A durable, owner-controlled request for one Builder to erect one vanilla village house. */
public record BuilderSite(
        UUID id,
        UUID ownerId,
        UUID builderVillagerId,
        String templateId,
        String dimensionId,
        long anchorPosition,
        int nextBlockIndex,
        java.util.List<Long> finishingCourse
) {
    public static final Codec<BuilderSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(BuilderSite::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(BuilderSite::ownerId),
            UUIDUtil.CODEC.fieldOf("builder").forGetter(BuilderSite::builderVillagerId),
            // Accept old records, but do not persist the obsolete work-chest link.
            dev.totem.villagers.workshop.WorkChestKey.CODEC.optionalFieldOf("work_chest").forGetter(site -> java.util.Optional.empty()),
            Codec.STRING.fieldOf("template").forGetter(BuilderSite::templateId),
            Codec.STRING.fieldOf("dimension").forGetter(BuilderSite::dimensionId),
            Codec.LONG.fieldOf("anchor").forGetter(BuilderSite::anchorPosition),
            Codec.INT.optionalFieldOf("next_block", 0).forGetter(BuilderSite::nextBlockIndex),
            Codec.LONG.listOf(0, 128).optionalFieldOf("finishing_course", java.util.List.of()).forGetter(BuilderSite::finishingCourse)
    ).apply(instance, (id, ownerId, builderVillagerId, ignoredWorkChest, templateId, dimensionId, anchorPosition, nextBlockIndex, course) ->
            new BuilderSite(id, ownerId, builderVillagerId, templateId, dimensionId, anchorPosition, nextBlockIndex, course)));

    public BuilderSite {
        finishingCourse = java.util.List.copyOf(finishingCourse);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(builderVillagerId, "builderVillagerId");
        if (!VanillaVillageBlueprints.isAllowedTemplateId(templateId)) {
            throw new IllegalArgumentException("Builder sites must use a vanilla village house template");
        }
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("dimensionId must be a namespaced identifier");
        }
        if (nextBlockIndex < 0) {
            throw new IllegalArgumentException("nextBlockIndex must not be negative");
        }
    }

    public BuilderSite(UUID id, UUID owner, UUID builder, String template, String dimension, long anchor, int index) {
        this(id, owner, builder, template, dimension, anchor, index, java.util.List.of());
    }

    public BuilderSite planAccess(net.minecraft.server.level.ServerLevel level, VanillaVillageBlueprints.Blueprint blueprint) {
        if (nextBlockIndex > 1 || blueprint.blocks().stream().skip(nextBlockIndex)
                .anyMatch(b -> !level.isLoaded(b.position()) || !level.getBlockState(b.position()).isAir())) return this;
        return new BuilderSite(id, ownerId, builderVillagerId, templateId, dimensionId, anchorPosition, nextBlockIndex,
                BuilderAccessCourse.plan(level, blueprint));
    }

    public VanillaVillageBlueprints.Blueprint inConstructionOrder(VanillaVillageBlueprints.Blueprint blueprint) {
        return BuilderAccessCourse.order(blueprint, finishingCourse);
    }

    public BuilderSite withNextBlockIndex(int nextIndex) {
        return new BuilderSite(id, ownerId, builderVillagerId, templateId, dimensionId, anchorPosition, nextIndex, finishingCourse);
    }
}
