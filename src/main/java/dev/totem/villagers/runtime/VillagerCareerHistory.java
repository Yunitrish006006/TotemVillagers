package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/** A player trade locks the career. Automatic appointments consume three loaded days of cooldown. */
public final class VillagerCareerHistory {
    private static final int COOLDOWN_TICKS = 72_000;
    private static final AttachmentType<Boolean> TRADED = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:player_traded"), Codec.BOOL);
    private static final AttachmentType<Integer> REMAINING = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:career_cooldown"), Codec.intRange(1, COOLDOWN_TICKS));

    private static final AttachmentType<net.minecraft.core.GlobalPos> GENERATED_AT = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:structure_birthplace"), net.minecraft.core.GlobalPos.CODEC);

    private VillagerCareerHistory() {}

    public static void recordStructureBirth(Villager resident, net.minecraft.core.GlobalPos birthplace) {
        ((AttachmentTarget) resident).setAttached(GENERATED_AT, birthplace);
    }

    static boolean generatedIn(Villager resident, dev.totem.villagers.worldgen.GeneratedVillageState village) {
        var birth = ((AttachmentTarget) resident).getAttached(GENERATED_AT);
        if (birth == null || !birth.dimension().identifier().toString().equals(village.dimensionId())) return false;
        var p = birth.pos();
        return p.getX() >= village.minimum().x() && p.getX() <= village.maximum().x()
                && p.getY() >= village.minimum().y() && p.getY() <= village.maximum().y()
                && p.getZ() >= village.minimum().z() && p.getZ() <= village.maximum().z();
    }

    static void register() {}

    public static void traded(Villager worker) {
        if (worker.level() instanceof ServerLevel) ((AttachmentTarget) worker).setAttached(TRADED, true);
    }

    static boolean mayChange(Villager worker) {
        var data = (AttachmentTarget) worker;
        return !worker.isBaby() && !worker.isTrading() && worker.getVillagerXp() == 0 && worker.getVillagerData().level() == 1
                && !Boolean.TRUE.equals(data.getAttached(TRADED)) && !data.hasAttached(REMAINING);
    }

    static void appointed(Villager worker) {
        ((AttachmentTarget) worker).setAttached(REMAINING, COOLDOWN_TICKS);
    }

    static void observe(Villager worker, int ticks) {
        var data = (AttachmentTarget) worker;
        var remaining = data.getAttached(REMAINING);
        if (remaining == null) return;
        if (remaining <= ticks) data.removeAttached(REMAINING);
        else data.setAttached(REMAINING, remaining - ticks);
    }
}
