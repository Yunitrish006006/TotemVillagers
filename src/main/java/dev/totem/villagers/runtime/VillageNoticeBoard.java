package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.Random;
import java.util.UUID;

public final class VillageNoticeBoard {
    private static final long DAY = 24000;
    record Notice(GlobalPos bell, String kind, ItemStack goods, int price, long expires) {
        static final Codec<Notice> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("bell").forGetter(Notice::bell),
                Codec.STRING.fieldOf("kind").forGetter(Notice::kind),
                ItemStack.CODEC.fieldOf("goods").forGetter(Notice::goods),
                Codec.intRange(1,64).fieldOf("price").forGetter(Notice::price),
                Codec.LONG.fieldOf("expires").forGetter(Notice::expires)).apply(i, Notice::new));
    }
    private static final AttachmentType<Notice> POST = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:village_notice"), Notice.CODEC);
    private static final AttachmentType<Long> LAST_ERRAND = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:last_errand_day"), Codec.LONG);

    static void withdraw(Villager resident) {
        ((AttachmentTarget)resident).removeAttached(POST);
    }

    private static void post(ServerLevel level, Villager resident, String kind, ItemStack goods, int price) {
        var site = resident.getBrain().getMemory(MemoryModuleType.MEETING_POINT).orElseThrow();
        var owner = (AttachmentTarget)resident;
        var old = owner.getAttached(POST);
        if (old != null && old.expires > level.getGameTime() && old.kind.equals(kind) && site.equals(old.bell)
                && old.price == price && ItemStack.matches(old.goods, goods)) return;
        owner.setAttached(POST, new Notice(site, kind, goods.copy(), price, level.getGameTime() + DAY));
    }

    static void postNeed(ServerLevel level, Villager resident, boolean food, VillagerMaterialLogisticsRuntime.MaterialPurchase purchase) {
        if (food) post(level, resident, "food", new ItemStack(Items.BREAD, 5), 1);
        else post(level, resident, "material", purchase.material(), purchase.emeraldPrice());
    }

    static void offerErrand(ServerLevel level, Villager resident, VillagerWorkInventory stock) {
        long day = level.getGameTime() / DAY;
        var owner = (AttachmentTarget)resident;
        if (Long.valueOf(day).equals(owner.getAttached(LAST_ERRAND))) return;
        owner.setAttached(LAST_ERRAND, day);
        var id = resident.getUUID();
        var random = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits() ^ day * 0x9E3779B97F4A7C15L);
        if (random.nextInt(16) != 0) return;
        var goods = random.nextBoolean() ? new ItemStack(Items.STICK, 8) : new ItemStack(Items.STRING, 4);
        if (stock.countMatchingItem(goods) < goods.getCount()) post(level, resident, "errand", goods, 1);
    }

    static boolean needed(ServerLevel level, Villager resident, Notice n, VillagerWorkInventory stock) {
        if (stock.countMatchingItem(new ItemStack(Items.EMERALD)) < n.price) return false;
        return switch (n.kind) {
            case "food" -> VillagerFoodEconomyRuntime.needsFoodRestock(resident, stock);
            case "material" -> VillagerMaterialLogisticsRuntime.meetingPurchase(level, resident, stock,
                    VillagerWorkshopRuntime.materialDemandFor(level, resident))
                    .filter(p -> p.emeraldPrice() == n.price && ItemStack.matches(p.material(), n.goods)).isPresent();
            case "errand" -> stock.countMatchingItem(n.goods) < n.goods.getCount()
                    && !VillagerFoodEconomyRuntime.needsFoodRestock(resident, stock);
            default -> false;
        };
    }

    public static void register() {
        VillageBoardStand.register();
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            var pos = hit.getBlockPos();
            if (hand != InteractionHand.MAIN_HAND || player.isSpectator()
                    || !(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return InteractionResult.PASS;
            String title = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).getFirst().getString().strip();
            var first = sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).getFirst();
            if (first.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                    && translated.getKey().startsWith("notice.totem.villager.panel.")) {
                if (world instanceof ServerLevel level && player instanceof ServerPlayer visitor) {
                    var bell = ((AttachmentTarget)sign).getAttached(VillageBoardStand.BELL);
                    if (bell == null || !level.isLoaded(bell) || !level.getBlockState(bell).is(Blocks.BELL)) return InteractionResult.PASS;
                    if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED)
                        visitor.sendSystemMessage(message("disabled"));
                    else {
                        var customer = ((AttachmentTarget)sign).getAttached(VillageBoardStand.CUSTOMER);
                        if (visitor.isShiftKeyDown() && !visitor.getMainHandItem().isEmpty() && customer == null)
                            visitor.sendSystemMessage(message("no_match"));
                        else interact(level, visitor, bell, customer);
                        VillageBoardStand.refresh(level, bell);
                    }
                }
                return InteractionResult.SUCCESS;
            }
            if (!title.equals("村莊布告欄") && !title.equalsIgnoreCase("Village Board")) return InteractionResult.PASS;
            for (Direction direction : Direction.values()) {
                var bell = pos.relative(direction);
                if (!world.isLoaded(bell) || !world.getBlockState(bell).is(Blocks.BELL)) continue;
                if (player instanceof ServerPlayer visitor && world instanceof ServerLevel level) {
                    if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED) {
                        visitor.sendSystemMessage(message("disabled"));
                    } else interact(level, visitor, bell);
                }
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    static Notice notice(Villager resident) {
        return ((AttachmentTarget)resident).getAttached(POST);
    }

    static java.util.List<Villager> openResidents(ServerLevel level, BlockPos bell) {
        var site = GlobalPos.of(level.dimension(), bell);
        var residents = level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(64), v -> {
            var n = ((AttachmentTarget)v).getAttached(POST);
            return v.isAlive() && n != null && n.expires > level.getGameTime() && site.equals(n.bell)
                    && v.getBrain().getMemory(MemoryModuleType.MEETING_POINT).filter(site::equals).isPresent();
        });
        residents.sort(Comparator.<Villager, Boolean>comparing(v ->
                ((AttachmentTarget)v).getAttached(POST).kind.equals("errand")).thenComparing(Villager::getUUID));
        var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
        residents.removeIf(v -> !needed(level, v, notice(v), accounts.inventory(v.getUUID())));
        return residents;
    }

    static void interact(ServerLevel level, ServerPlayer player, BlockPos bell) {
        interact(level, player, bell, null);
    }

    private static void interact(ServerLevel level, ServerPlayer player, BlockPos bell, UUID selected) {
        var held = player.getMainHandItem();
        boolean deliver = player.isShiftKeyDown() && !held.isEmpty();
        var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
        var residents = openResidents(level, bell);
        int shown = 0;
        for (var resident : residents) {
            var n = ((AttachmentTarget)resident).getAttached(POST);
            var stock = accounts.inventory(resident.getUUID());
            if (deliver && selected != null && !selected.equals(resident.getUUID())) continue;
            if (deliver) {
                if (!ItemStack.isSameItemSameComponents(held, n.goods) || held.getCount() < n.goods.getCount()) continue;
                var reward = new ItemStack(Items.EMERALD, n.price);
                if (!stock.exchangeExactMatching(reward, n.goods)) continue;
                held.shrink(n.goods.getCount());
                withdraw(resident);
                player.getInventory().placeItemBackInInventory(reward, net.minecraft.util.Prediction.SERVER_ONLY);
                player.sendSystemMessage(message("done", resident.getDisplayName(), n.price));
                return;
            }
            player.sendSystemMessage(message(n.kind, resident.getDisplayName(), n.goods.getCount(), n.goods.getHoverName(), n.price));
            shown++;
        }
        player.sendSystemMessage(message(deliver ? "no_match" : shown == 0 ? "empty" : "hint"));
    }

    private static Component message(String key, Object... args) {
        return Component.translatable("notice.totem.villager." + key, args);
    }

    private VillageNoticeBoard() {}
}
