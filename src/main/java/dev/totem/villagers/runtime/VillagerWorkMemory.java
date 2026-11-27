package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded observations, saved with the observer. Paths and failed searches are transient. */
public final class VillagerWorkMemory {
    record Sighting(String kind, GlobalPos site, Optional<UUID> animal, long time) {
        static final Codec<Sighting> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("kind").forGetter(Sighting::kind),
                GlobalPos.CODEC.fieldOf("site").forGetter(Sighting::site),
                UUIDUtil.CODEC.optionalFieldOf("animal").forGetter(Sighting::animal),
                Codec.LONG.fieldOf("seen").forGetter(Sighting::time)).apply(i, Sighting::new));
    }

    private static final AttachmentType<dev.totem.villagers.work.ActiveWork> DEFERRED = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:deferred_work"), dev.totem.villagers.work.ActiveWork.CODEC);

    public static dev.totem.villagers.work.ActiveWork deferred(Villager worker) {
        return ((AttachmentTarget) worker).getAttached(DEFERRED);
    }

    static void defer(Villager worker, dev.totem.villagers.work.ActiveWork job) {
        ((AttachmentTarget) worker).setAttached(DEFERRED, job);
    }

    static void clearDeferred(Villager worker) {
        ((AttachmentTarget) worker).removeAttached(DEFERRED);
    }

    private static final int LIMIT = 24;
    private static final AttachmentType<List<Sighting>> SITES = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:work_sightings"), Sighting.CODEC.listOf(0, LIMIT));
    private static final AttachmentType<UUID> SHEPHERD = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:shepherd"), UUIDUtil.CODEC);

    public static void register() {}
    private VillagerWorkMemory() {}

    static List<Sighting> sites(Villager v) {
        return ((AttachmentTarget) v).getAttachedOrElse(SITES, List.of());
    }

    static void remember(Villager v, Sighting seen) {
        var sites = new ArrayList<>(sites(v));
        for (var previous : List.copyOf(sites)) {
            if (seen.animal.isPresent() ? seen.animal.equals(previous.animal) : seen.site.equals(previous.site) && previous.animal.isEmpty()) {
                if (previous.site.equals(seen.site) && previous.kind.equals(seen.kind) && seen.time - previous.time < 100) return;
                sites.remove(previous);
            }
        }
        sites.addFirst(seen);
        if (sites.size() > LIMIT) sites.removeLast();
        ((AttachmentTarget) v).setAttached(SITES, List.copyOf(sites));
    }

    static boolean seesSite(net.minecraft.server.level.ServerLevel level, Villager worker, net.minecraft.core.BlockPos pos) {
        var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        if (worker.distanceToSqr(center) > 32 * 32 || !level.isLoaded(pos)) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(worker.getEyePosition(), center,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    static void observeBlock(net.minecraft.server.level.ServerLevel level, Villager worker, net.minecraft.core.BlockPos pos) {
        if (!seesSite(level, worker, pos)) return;
        remember(worker, new Sighting(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(pos).getBlock()).toString(), GlobalPos.of(level.dimension(), pos), Optional.empty(), level.getGameTime()));
    }

    static void forget(Villager v, Sighting invalid) {
        ((AttachmentTarget) v).setAttached(SITES, sites(v).stream().filter(s -> !s.equals(invalid)).toList());
    }

    public static boolean canTend(Villager shepherd, Sheep sheep) {
        var owner = ((AttachmentTarget) sheep).getAttached(SHEPHERD);
        return owner == null || owner.equals(shepherd.getUUID());
    }

    public static void claimAfterShearing(Villager shepherd, Sheep sheep) {
        ((AttachmentTarget) sheep).setAttached(SHEPHERD, shepherd.getUUID());
        observe(shepherd, sheep);
    }

    public static boolean observe(Villager shepherd, Sheep sheep) {
        if (!canTend(shepherd, sheep) || !shepherd.hasLineOfSight(sheep)) return false;
        if (((AttachmentTarget) sheep).getAttached(SHEPHERD) == null) return true;
        remember(shepherd, new Sighting("sheep", GlobalPos.of(sheep.level().dimension(), sheep.blockPosition()),
                Optional.of(sheep.getUUID()), sheep.level().getGameTime()));
        return true;
    }
}
