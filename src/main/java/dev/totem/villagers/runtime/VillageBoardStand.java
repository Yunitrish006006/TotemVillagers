package dev.totem.villagers.runtime;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.Codec;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/** A village bell remembers its kiosk. Only the signs originally installed by it receive updates. */
public final class VillageBoardStand {
    record Site(BlockPos middle, Direction facing) {
        static final Codec<Site> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("middle").forGetter(Site::middle),
                Direction.CODEC.fieldOf("facing").forGetter(Site::facing)).apply(i, Site::new));
    }
    private static <T> AttachmentType<T> data(String key, Codec<T> codec) {
        return AttachmentRegistry.createPersistent(Identifier.parse("totem-villagers:" + key), codec);
    }
    private static final AttachmentType<Site> SITE = data("board_stand", Site.CODEC);
    static final AttachmentType<BlockPos> BELL = data("board_bell", BlockPos.CODEC);
    static final AttachmentType<UUID> CUSTOMER = data("board_customer", UUIDUtil.CODEC);

    static void register() {
        // Register block-entity attachments before any saved chunks are decoded.
    }

    static void bootstrap(ServerLevel level, GeneratedVillageState village, List<Villager> residents) {
        if (Math.floorMod(level.getGameTime() / 20 + village.id().hashCode(), 5) != 0) return;
        for (var v : residents) {
            var memory = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT).orElse(null);
            if (memory != null && memory.dimension().equals(level.dimension())
                    && updateBell(level, village, memory.pos())) return;
        }
        // A newly generated village can have a bell before anyone claims a meeting point.
        var lo = village.minimum();
        var hi = village.maximum();
        if (residents.isEmpty() && level.players().stream().noneMatch(player ->
                player.getX() >= lo.x() - 64 && player.getX() <= hi.x() + 64
                && player.getZ() >= lo.z() - 64 && player.getZ() <= hi.z() + 64)) return;
        for (int x = lo.x() >> 4; x <= hi.x() >> 4; x++) {
            for (int z = lo.z() >> 4; z <= hi.z() >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof BellBlockEntity && updateBell(level, village, entity.getBlockPos())) return;
                }
            }
        }
    }

    private static boolean updateBell(ServerLevel level, GeneratedVillageState village, BlockPos p) {
        var lo = village.minimum();
        var hi = village.maximum();
        if (p.getX() < lo.x() || p.getX() > hi.x() || p.getZ() < lo.z() || p.getZ() > hi.z()
                || p.getY() < lo.y() || p.getY() > hi.y() || !level.isLoaded(p)
                || !(level.getBlockEntity(p) instanceof BellBlockEntity bell)) return false;
        Site site = ((AttachmentTarget)bell).getAttached(SITE);
        if (site == null) {
            site = build(level, p);
            if (site == null) return true;
            ((AttachmentTarget)bell).setAttached(SITE, site);
            bell.setChanged();
        }
        refresh(level, p, site);
        return true;
    }

    private static Site build(ServerLevel level, BlockPos bell) {
        for (int radius = 3; radius <= 8; radius++) {
            for (Direction front : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[]{0,-1,1,-2,2}) {
                    for (int offset = 0; offset <= 2; offset++) {
                        var base = bell.relative(front.getOpposite(), radius).above(dy)
                                .relative(front.getClockWise(), offset == 2 ? -1 : offset);
                        if (!fits(level, base, front)) continue;
                        for (int x = -1; x <= 1; x++) {
                            var foot = base.relative(front.getClockWise(), x);
                            if (x != 0) level.setBlockAndUpdate(foot, Blocks.SPRUCE_FENCE.defaultBlockState());
                            level.setBlockAndUpdate(foot.above(), Blocks.SPRUCE_PLANKS.defaultBlockState());
                            level.setBlockAndUpdate(foot.above(2), Blocks.SPRUCE_SLAB.defaultBlockState());
                            var paper = foot.above().relative(front);
                            level.setBlockAndUpdate(paper, Blocks.BIRCH_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front));
                            var sign = (SignBlockEntity)level.getBlockEntity(paper);
                            ((AttachmentTarget)sign).setAttached(BELL, bell.immutable());
                            sign.setWaxed(true);
                            sign.setChanged();
                        }
                        return new Site(base.above().relative(front), front);
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, BlockPos base, Direction front) {
        for (int x = -1; x <= 1; x++) {
            var foot = base.relative(front.getClockWise(), x);
            for (int depth = 0; depth <= 2; depth++) {
                var column = foot.relative(front, depth);
                if (!level.isLoaded(column)) return false;
                var ground = level.getBlockState(column.below());
                if (!(ground.is(BlockTags.DIRT) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.MUD) || ground.is(BlockTags.SAND) || ground.is(Blocks.STONE)
                        || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.DIRT_PATH))
                        || depth == 0 && ground.is(Blocks.DIRT_PATH)) return false;
                for (int y = 0; y < (depth == 0 ? 3 : 2); y++) {
                    var state = level.getBlockState(column.above(y));
                    if (!state.isAir() && !state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.SNOW)) return false;
                }
            }
        }
        return level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new AABB(base).expandTowards(front.getClockWise().getStepX(), 0, front.getClockWise().getStepZ())
                        .expandTowards(-front.getClockWise().getStepX(), 2, -front.getClockWise().getStepZ())).isEmpty();
    }

    static void refresh(ServerLevel level, BlockPos bell, Site site) {
        var orders = VillageNoticeBoard.openResidents(level, bell);
        for (int i = 0; i < 3; i++) {
            var p = site.middle.relative(site.facing.getClockWise(), 1 - i);
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof SignBlockEntity sign)
                    || !bell.equals(((AttachmentTarget)sign).getAttached(BELL))) continue;
            var owner = i < orders.size() ? orders.get(i) : null;
            var n = owner == null ? null : VillageNoticeBoard.notice(owner);
            Component header = Component.translatable("notice.totem.villager.panel." + (i == 1 ? "title" : "order"));
            var lines = n == null ? List.of(header, Component.empty(), Component.translatable("notice.totem.villager.panel.empty"), Component.empty())
                    : List.of(header, n.goods().getHoverName(), Component.literal("× " + n.goods().getCount()),
                        Component.translatable("notice.totem.villager.panel.pay", n.price()));
            if (!sign.getText(SignTextSlot.FRONT).getMessages(false).equals(lines))
                sign.setText(new SignText(List.copyOf(lines), List.copyOf(lines), DyeColor.BLACK, false), SignTextSlot.FRONT);
            var data = (AttachmentTarget)sign;
            UUID id = owner == null ? null : owner.getUUID();
            if (!java.util.Objects.equals(id, data.getAttached(CUSTOMER))) {
                if (id == null) data.removeAttached(CUSTOMER);
                else data.setAttached(CUSTOMER, id);
                sign.setChanged();
            }
        }
    }

    static void refresh(ServerLevel level, BlockPos bell) {
        if (level.getBlockEntity(bell) instanceof BellBlockEntity block) {
            var site = ((AttachmentTarget)block).getAttached(SITE);
            if (site != null) refresh(level, bell, site);
        }
    }

    private VillageBoardStand() {}
}
