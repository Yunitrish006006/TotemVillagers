package dev.totem.villagers.runtime;

import java.util.Objects;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Only the viewer receives the schedule sampled at the bell they are looking at. */
public record VillageBellSchedule(GlobalPos bell, String activity) {
    public static final int RANGE = 12;
    private static final AttachmentType<VillageBellSchedule> DISPLAY = AttachmentRegistry.<VillageBellSchedule>builder()
            .syncWith(StreamCodec.composite(GlobalPos.STREAM_CODEC, VillageBellSchedule::bell,
                    ByteBufCodecs.stringUtf8(64), VillageBellSchedule::activity, VillageBellSchedule::new), AttachmentSyncPredicate.targetOnly())
            .buildAndRegister(Identifier.parse("totem-villagers:bell_schedule"));

    public static VillageBellSchedule current(Player viewer) {
        return ((AttachmentTarget) viewer).getAttached(DISPLAY);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerList().getPlayers()) {
                if (Math.floorMod(server.getTickCount() + player.getId(), 10) != 0) continue;
                var level = player.level();
                var hit = player.pick(RANGE, 1, false);
                VillageBellSchedule next = null;
                if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                        && level.isLoaded(block.getBlockPos()) && level.getBlockState(block.getBlockPos()).is(Blocks.BELL)) {
                    var pos = block.getBlockPos();
                    var attributes = level.environmentAttributes();
                    next = new VillageBellSchedule(GlobalPos.of(level.dimension(), pos),
                            attributes.getValue(EnvironmentAttributes.VILLAGER_ACTIVITY, pos).getName());
                }
                if (Objects.equals(next, current(player))) continue;
                var data = (AttachmentTarget) player;
                if (next == null) data.removeAttached(DISPLAY);
                else data.setAttached(DISPLAY, next);
            }
        });
    }
}
