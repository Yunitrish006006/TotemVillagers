package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Each attendee remembers at most one agreement. Prices and goods remain owned by the existing markets. */
public final class VillageMeeting {
    static final String RATION = "totem:ration";
    private record Agreement(UUID buyer, UUID seller, String item, int count, long expires) {
        static final Codec<Agreement> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("buyer").forGetter(Agreement::buyer),
                UUIDUtil.CODEC.fieldOf("seller").forGetter(Agreement::seller),
                Codec.STRING.fieldOf("item").forGetter(Agreement::item),
                Codec.intRange(1, 999).fieldOf("count").forGetter(Agreement::count),
                Codec.LONG.fieldOf("expires").forGetter(Agreement::expires)).apply(i, Agreement::new));
    }
    private static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<Agreement> PLAN =
            AttachmentRegistry.createPersistent(Identifier.parse("totem-villagers:meeting_agreement"), Agreement.CODEC);

    private VillageMeeting() {}

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Villager resident && entity.getRemovalReason() != null
                    && (entity.getRemovalReason().shouldDestroy() || entity.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION)) {
                var agreement = plan(resident);
                if (agreement != null) cancel(level, resident, agreement);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(
                (original, moved, from, to) -> { if (moved instanceof Villager v) ((AttachmentTarget)v).removeAttached(PLAN); });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer viewer && world instanceof ServerLevel level && hand == InteractionHand.MAIN_HAND
                    && entity instanceof Villager resident && !player.isSpectator()
                    && WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() == WorkBackedTradingMode.ENFORCED)
                viewer.sendOverlayMessage(goal(level, resident));
            return InteractionResult.PASS;
        });
    }

    private static Agreement plan(Villager v) {
        var data = (AttachmentTarget)v;
        var plan = data.getAttached(PLAN);
        if (plan != null && plan.expires <= v.level().getGameTime()) {
            data.removeAttached(PLAN);
            return null;
        }
        return plan;
    }

    static UUID supplier(Villager buyer, String item) {
        var p = plan(buyer);
        return p != null && p.buyer.equals(buyer.getUUID()) && p.item.equals(item) ? p.seller : null;
    }

    public static int promisedMaterial(Villager seller, ItemStack item) {
        var p = plan(seller);
        return p != null && p.seller.equals(seller.getUUID()) && p.item.equals(BuiltInRegistries.ITEM.getKey(item.getItem()).toString())
                ? p.count : 0;
    }

    public static boolean availableForSale(Villager seller, ItemStack result,
                                           dev.totem.villagers.inventory.VillagerWorkInventory inventory) {
        return availableTo(seller, null, java.util.List.of(result), inventory);
    }

    static boolean availableTo(Villager seller, Villager buyer, List<ItemStack> goods,
                               dev.totem.villagers.inventory.VillagerWorkInventory inventory) {
        var p = plan(seller);
        if (p == null || !p.seller.equals(seller.getUUID()) || buyer != null && buyer.getUUID().equals(p.buyer)) return true;
        if (RATION.equals(p.item)) {
            int nutrition = goods.stream().mapToInt(s -> VillagerNutrition.nutrition(s) * s.getCount()).sum();
            if (nutrition <= 0) return true;
            return inventory.snapshot().stream().mapToInt(s -> VillagerNutrition.nutrition(s) * s.getCount()).sum()
                    - nutrition >= VillagerNutrition.MAX_FOOD_LEVEL + VillagerFoodEconomyRuntime.INTERNAL_FOOD_NUTRITION;
        }
        for (var result : goods) {
            if (BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(p.item)
                    && inventory.countMatchingItem(result) - result.getCount() < p.count) return false;
        }
        return true;
    }

    static boolean preparingFood(Villager worker) {
        var p = plan(worker);
        return p != null && p.seller.equals(worker.getUUID()) && RATION.equals(p.item);
    }

    static void fulfilled(Villager buyer, Villager seller, String item, int count) {
        var agreement = plan(buyer);
        if (agreement != null && seller.getUUID().equals(supplier(buyer, item)) && count >= agreement.count)
            cancel((ServerLevel)buyer.level(), buyer, agreement);
    }

    private static void cancel(ServerLevel level, Villager resident, Agreement p) {
        ((AttachmentTarget)resident).removeAttached(PLAN);
        UUID other = resident.getUUID().equals(p.buyer) ? p.seller : p.buyer;
        if (level.getEntity(other) instanceof Villager v && p.equals(plan(v))) ((AttachmentTarget)v).removeAttached(PLAN);
    }

    static void resolve(ServerLevel level, List<? extends Villager> residents, VillagerWorkInventorySavedData stock,
                        Map<UUID, Optional<WorkOrder>> demands) {

        Map<BlockPos, List<Villager>> bells = new HashMap<>();
        for (var v : residents) {
            var p = plan(v);
            if (p != null) {
                boolean buyer = p.buyer.equals(v.getUUID());
                var partner = level.getEntity(buyer ? p.seller : p.buyer);
                if (partner instanceof Villager other && (!other.isAlive() || !p.equals(plan(other)))
                        || buyer && !stillNeeded(level, v, p, stock, demands)
                        || !buyer && RATION.equals(p.item) && !VillagerFoodEconomyRuntime.isFoodProducer(v)) cancel(level, v, p);
            }
            if (v.isBaby() || !v.getBrain().isActive(Activity.MEET) || !VillageWorkshopApprenticeship.safe(level, v)) continue;
            var site = v.getBrain().getMemory(MemoryModuleType.MEETING_POINT).orElse(null);
            if (site == null || !site.dimension().equals(level.dimension()) || !level.isLoaded(site.pos())
                    || !level.getBlockState(site.pos()).is(Blocks.BELL) || !site.pos().closerToCenterThan(v.position(), 8)) continue;
            bells.computeIfAbsent(site.pos(), ignored -> new ArrayList<>()).add(v);
        }
        for (var group : bells.values()) {
            group.sort(Comparator.comparing(Villager::getUUID));
            dev.totem.villagers.trade.VillagerTradeKnowledge.exchangeAtBell(level, group);
            for (int i = 0; i < group.size(); i++) {
                var buyer = group.get(i);
                if (plan(buyer) != null) continue;
                var wallet = stock.inventory(buyer.getUUID());
                if (wallet.countMatchingItem(new ItemStack(Items.EMERALD)) == 0) continue;
                boolean food = VillagerFoodEconomyRuntime.needsFoodRestock(buyer, wallet);
                var purchase = food ? Optional.<VillagerMaterialLogisticsRuntime.MaterialPurchase>empty() :
                        VillagerMaterialLogisticsRuntime.meetingPurchase(level, buyer, wallet, demands.get(buyer.getUUID()));
                if (!food && purchase.isEmpty()) {
                    VillageNoticeBoard.offerErrand(level, buyer, wallet);
                    continue;
                }
                // Rotate a bounded window so a large crowd does not require an all-pairs search.
                int start = Math.floorMod(level.getGameTime() / 100 + i, group.size());
                for (int j = 0; j < Math.min(8, group.size()); j++) {
                    var seller = group.get((start + j) % group.size());
                    if (seller == buyer || plan(seller) != null || seller.distanceToSqr(buyer) > 36 || !buyer.hasLineOfSight(seller)) continue;
                    if (food ? !canOfferFood(seller, stock)
                            : !VillagerMaterialLogisticsRuntime.canPromise(level, seller, stock, purchase.orElseThrow(),
                                    demands.get(seller.getUUID()))) continue;
                    var promise = new Agreement(buyer.getUUID(), seller.getUUID(), food ? RATION : purchase.get().materialId(),
                            food ? 1 : purchase.get().material().getCount(), level.getGameTime() + 24000);
                    ((AttachmentTarget)buyer).setAttached(PLAN, promise);
                    ((AttachmentTarget)seller).setAttached(PLAN, promise);
                    buyer.getLookControl().setLookAt(seller);
                    seller.getLookControl().setLookAt(buyer);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                            seller.getX(), seller.getEyeY() + .3, seller.getZ(), 3, .12, .08, .12, 0);
                    VillageNoticeBoard.withdraw(buyer);
                    VillageNoticeBoard.withdraw(seller);
                    break;
                }
                if (plan(buyer) == null) VillageNoticeBoard.postNeed(level, buyer, food, purchase.orElse(null));
            }
        }
    }

    private static boolean canOfferFood(Villager seller, VillagerWorkInventorySavedData stock) {
        return VillagerFoodEconomyRuntime.isFoodProducer(seller)
                && (VillagerFoodEconomyRuntime.foodForSale(stock.inventory(seller.getUUID())).isPresent()
                || !VillagerNutrition.isHungry(seller) && seller.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
    }

    private static boolean stillNeeded(ServerLevel level, Villager buyer, Agreement p, VillagerWorkInventorySavedData stock,
                                       Map<UUID, Optional<WorkOrder>> demands) {
        var inventory = stock.inventory(buyer.getUUID());
        if (RATION.equals(p.item)) return VillagerFoodEconomyRuntime.needsFoodRestock(buyer, inventory);
        // A temporary interruption (sleep, danger) does not erase yesterday's conversation.
        if (!VillageWorkshopApprenticeship.safe(level, buyer)) return true;
        return VillagerMaterialLogisticsRuntime.meetingPurchase(level, buyer, inventory, demands.get(buyer.getUUID()))
                .filter(rate -> rate.materialId().equals(p.item) && rate.material().getCount() == p.count).isPresent();
    }

    public static Component goal(ServerLevel level, Villager v) {
        if (v.getBrain().isActive(Activity.PANIC) || v.getBrain().isActive(Activity.HIDE)) return label("safe");
        if (v.isSleeping() || v.getBrain().isActive(Activity.REST)) return label("rest");
        if (v.getBrain().isActive(Activity.MEET)) return label("meet");
        if (VillagerNutrition.isHungry(v)) return label("eat");
        var p = plan(v);
        if (p != null) {
            boolean buying = v.getUUID().equals(p.buyer);
            UUID id = buying ? p.seller : p.buyer;
            var other = level.getEntity(id);
            Component partner = other == null ? label("neighbour") : other.getDisplayName();
            Component item = RATION.equals(p.item) ? label("ration") :
                    new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(p.item))).getHoverName();
            return Component.translatable("goal.totem.villager." + (buying ? "buy" : "supply"), partner, p.count, item);
        }
        var active = VillagerWorkSavedData.forServer(level.getServer()).get(v.getUUID()).flatMap(s -> s.activeWork());
        if (active.isPresent()) {
            var order = dev.totem.villagers.work.WorkOrderDefinitions.catalog().snapshot().get(active.get().orderId());
            if (order != null) return Component.translatable("goal.totem.villager.make",
                    new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(order.output().itemId()))).getHoverName());
        }

        if (v.isBaby()) return label("play");
        var inv = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(v.getUUID());
        if (VillagerFoodEconomyRuntime.needsFoodRestock(v, inv)) return label("restock");
        var missing = VillagerHeadStatus.display(v);
        if (missing != null && missing.material().isPresent()) {
            var need = missing.material().get();
            return Component.translatable("goal.totem.villager.source", need.count(),
                    new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(need.itemId()))).getHoverName());
        }
        if (v.getBrain().isActive(Activity.WORK))
            return label("work");
        return label("idle");
    }

    private static Component label(String key) {
        return Component.translatable("goal.totem.villager." + key);
    }
}
