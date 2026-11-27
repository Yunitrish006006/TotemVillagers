package dev.totem.villagers.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.trading.MerchantOffer;
import java.util.*;

/** One transferable lesson quotes a real offer at its undiscounted price. No goods change hands during teaching. */
public final class VillagerTradeKnowledge {
    public record Lesson(Identifier item, int batch, int price) {
        static final Codec<Lesson> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("item").forGetter(Lesson::item),
                Codec.intRange(1, 64).fieldOf("batch").forGetter(Lesson::batch),
                Codec.intRange(1, 64).fieldOf("emeralds").forGetter(Lesson::price)).apply(i, Lesson::new));
    }
    private static final int LIMIT = 64;
    private static final String BOOK_KEY = "totem_villagers_trade_lesson";
    private static final AttachmentType<List<Lesson>> LEARNED = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:trade_knowledge"), Lesson.CODEC.listOf(0, LIMIT));
    private static final AttachmentType<Long> NEXT_LESSON = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:next_meeting_lesson"), Codec.LONG);
    private static final Map<Villager, Long> RETRY = new WeakHashMap<>();

    private VillagerTradeKnowledge() {}

    static List<Lesson> known(Villager v) {
        return ((AttachmentTarget)v).getAttachedOrElse(LEARNED, List.of());
    }

    private static String learn(Villager student, Lesson lesson) {
        var current = known(student);
        if (current.stream().anyMatch(k -> k.item.equals(lesson.item))) return "known";
        if (current.size() >= LIMIT) return "full";
        var goods = BuiltInRegistries.ITEM.getValue(lesson.item);
        if (goods == null || goods == Items.AIR || goods == Items.EMERALD || goods.getDefaultInstance().getMaxStackSize() <= 1
                || lesson.batch > goods.getDefaultInstance().getMaxStackSize()) return "invalid";
        var next = new ArrayList<>(current);
        next.add(lesson);
        ((AttachmentTarget)student).setAttached(LEARNED, List.copyOf(next));
        return "learned";
    }

    private static Optional<Lesson> quote(MerchantOffer offer) {
        var item = offer.getResult();
        if (item.getCount() > 64 || offer.getBaseCostA().getCount() > 64 || item.isEmpty() || item.is(Items.EMERALD) || item.getMaxStackSize() <= 1
                || !ItemStack.isSameItemSameComponents(item, new ItemStack(item.getItem()))
                || !offer.getBaseCostA().is(Items.EMERALD) || !offer.getCostB().isEmpty()) return Optional.empty();
        return Optional.of(new Lesson(BuiltInRegistries.ITEM.getKey(item.getItem()), item.getCount(), offer.getBaseCostA().getCount()));
    }

    static void rememberSale(Villager v, MerchantOffer offer) {
        quote(offer).ifPresent(l -> learn(v, l));
    }

    private static List<Lesson> lessons(Villager teacher) {
        Map<Identifier, Lesson> teachings = new LinkedHashMap<>();
        teacher.getOffers().stream().filter(o -> !o.isOutOfStock()).map(VillagerTradeKnowledge::quote).flatMap(Optional::stream)
                .forEach(l -> teachings.putIfAbsent(l.item, l));
        for (var l : known(teacher)) teachings.putIfAbsent(l.item, l);
        return List.copyOf(teachings.values());
    }

    public static void exchangeAtBell(ServerLevel level, List<Villager> attendees) {
        if (attendees.size() < 2) return;
        long now = level.getGameTime();
        Map<Villager, List<Lesson>> teachers = new HashMap<>();
        for (int i = 0; i < attendees.size(); i++) {
            var student = attendees.get(i);
            var data = (AttachmentTarget)student;
            if (known(student).size() >= LIMIT || now < data.getAttachedOrElse(NEXT_LESSON, 0L)
                    || now < RETRY.getOrDefault(student, 0L)) continue;
            RETRY.put(student, now + 200);
            int start = Math.floorMod(i + now / 200, attendees.size());
            for (int j = 0; j < Math.min(8, attendees.size()); j++) {
                var teacher = attendees.get((start + j) % attendees.size());
                if (teacher == student || student.distanceToSqr(teacher) > 36 || !student.hasLineOfSight(teacher)) continue;
                boolean learned = false;
                for (var l : teachers.computeIfAbsent(teacher, VillagerTradeKnowledge::lessons)) {
                    if (!learn(student, l).equals("learned")) continue;
                    data.setAttached(NEXT_LESSON, now + 24000);
                    student.getLookControl().setLookAt(teacher);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                            student.getX(), student.getEyeY(), student.getZ(), 5, .2, .12, .2, .15);
                    learned = true;
                    break;
                }
                if (learned) break;
            }
        }
    }

    static ItemStack manual(Lesson l) {
        var book = new ItemStack(Items.WRITTEN_BOOK);
        var name = BuiltInRegistries.ITEM.getValue(l.item).getDefaultInstance().getHoverName();
        var page = Component.translatable("knowledge.totem.villager.page", name, l.batch, l.price);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Totem Villagers"),
                "Totem Villagers", 0, List.of(Filterable.passThrough(page)), true));
        book.set(DataComponents.ITEM_NAME, Component.translatable("knowledge.totem.villager.book", name));
        CustomData.update(DataComponents.CUSTOM_DATA, book, tag -> tag.put(BOOK_KEY, Lesson.CODEC.encodeStart(NbtOps.INSTANCE, l).getOrThrow()));
        return book;
    }

    static Optional<Lesson> read(ItemStack book) {
        if (!book.is(Items.WRITTEN_BOOK)) return Optional.empty();
        var tag = book.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().get(BOOK_KEY);
        return tag == null ? Optional.empty() : Lesson.CODEC.parse(NbtOps.INSTANCE, tag).result();
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || player.isSpectator()
                    || !(entity instanceof Villager resident)) return InteractionResult.PASS;
            var held = player.getMainHandItem();
            var sample = player.getOffhandItem();
            boolean copy = held.is(Items.BOOK);
            var entry = read(held);
            if (!copy && entry.isEmpty()) return InteractionResult.PASS;
            if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
            if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED)
                return InteractionResult.PASS;
            if (copy) {
                var available = lessons(resident);
                if (sample.isEmpty()) {
                    var text = Component.translatable("knowledge.totem.villager.list", resident.getDisplayName());
                    if (available.isEmpty()) text.append(Component.translatable("knowledge.totem.villager.empty"));
                    for (int i = 0; i < available.size(); i++) {
                        if (i > 0) text.append("、");
                        text.append(BuiltInRegistries.ITEM.getValue(available.get(i).item).getDefaultInstance().getHoverName());
                    }
                    serverPlayer.sendSystemMessage(text);
                    return InteractionResult.SUCCESS;
                }
                var lesson = available.stream().filter(l -> sample.is(BuiltInRegistries.ITEM.getValue(l.item))
                        && ItemStack.isSameItemSameComponents(sample, new ItemStack(sample.getItem()))).findFirst();
                if (lesson.isEmpty()) {
                    serverPlayer.sendOverlayMessage(Component.translatable("knowledge.totem.villager.unknown"));
                } else {
                    var result = manual(lesson.get());
                    if (!player.getAbilities().instabuild) held.shrink(1);
                    if (held.isEmpty()) player.setItemInHand(hand, result);
                    else player.getInventory().placeItemBackInInventory(result, net.minecraft.util.Prediction.SERVER_ONLY);
                    serverPlayer.sendOverlayMessage(result.getHoverName());
                }
            } else {
                var lesson = entry.orElseThrow();
                String outcome = learn(resident, lesson);
                var goods = BuiltInRegistries.ITEM.getValue(lesson.item);
                serverPlayer.sendOverlayMessage(Component.translatable("knowledge.totem.villager." + outcome,
                        goods == null ? Component.literal(lesson.item.toString()) : goods.getDefaultInstance().getHoverName()));
            }
            return InteractionResult.SUCCESS;
        });
    }
}
