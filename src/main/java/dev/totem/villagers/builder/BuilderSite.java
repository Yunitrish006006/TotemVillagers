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
        int nextBlockIndex
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
            Codec.INT.optionalFieldOf("next_block", 0).forGetter(BuilderSite::nextBlockIndex)
    ).apply(instance, (id, ownerId, builderVillagerId, ignoredWorkChest, templateId, dimensionId, anchorPosition, nextBlockIndex) ->
            new BuilderSite(id, ownerId, builderVillagerId, templateId, dimensionId, anchorPosition, nextBlockIndex)));

    public BuilderSite {
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

    public BuilderSite withNextBlockIndex(int nextIndex) {
        return new BuilderSite(id, ownerId, builderVillagerId, templateId, dimensionId, anchorPosition, nextIndex);
    }
}
