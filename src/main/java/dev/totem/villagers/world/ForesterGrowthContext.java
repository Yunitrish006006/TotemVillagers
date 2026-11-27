package dev.totem.villagers.world;

import net.minecraft.core.GlobalPos;

/** TreeGrower temporarily removes the sapling before invoking TreeFeature. */
public final class ForesterGrowthContext {
    public static final ThreadLocal<GlobalPos> SITE = new ThreadLocal<>();
    private ForesterGrowthContext() {}
}
