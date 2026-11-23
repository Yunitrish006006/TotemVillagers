package dev.totem.villagers.builder;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/** Owner-approved blueprint awaiting a material-backed trial in this construction zone. */
public record BuilderProject(UUID zone, UUID owner, String dimension, String template, long anchor, int loadedTicks) {
    public static final int DEMAND_TICKS = 48_000;
    public static final Codec<BuilderProject> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("zone").forGetter(BuilderProject::zone),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(BuilderProject::owner),
            Codec.STRING.fieldOf("dimension").forGetter(BuilderProject::dimension),
            Codec.STRING.fieldOf("template").forGetter(BuilderProject::template),
            Codec.LONG.fieldOf("anchor").forGetter(BuilderProject::anchor),
            Codec.intRange(0, DEMAND_TICKS).fieldOf("loaded_ticks").forGetter(BuilderProject::loadedTicks)
    ).apply(i, BuilderProject::new));

    public BuilderProject {
        if (!VanillaVillageBlueprints.isAllowedTemplateId(template)) throw new IllegalArgumentException("Invalid builder blueprint");
    }

    public BuilderProject observe(int ticks) {
        return new BuilderProject(zone, owner, dimension, template, anchor, Math.min(DEMAND_TICKS, loadedTicks + ticks));
    }
}
