package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.ProductionParcel;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.List;

/** Estimates how much food residents need to carry a new specialist until the next local harvest. */
public final class VillageFoodForecast {
    private static final int DAY = 24000;
    private static final int MAX_WAIT = 14 * DAY;
    private static final int DIGEST = VillagerFoodEconomyRuntime.DIGEST_INTERVAL_TICKS;

    record Harvest(GlobalPos station, int elapsed, int interval, boolean produced) {
        static final Codec<Harvest> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("station").forGetter(Harvest::station),
                Codec.intRange(0, MAX_WAIT).fieldOf("elapsed").forGetter(Harvest::elapsed),
                Codec.intRange(0, MAX_WAIT).fieldOf("interval").forGetter(Harvest::interval),
                Codec.BOOL.fieldOf("produced").forGetter(Harvest::produced)).apply(i, Harvest::new));

        Harvest after(int ticks) {
            int next = Math.min(MAX_WAIT, elapsed + ticks);
            return next == elapsed ? this : new Harvest(station, next, interval, produced);
        }

        Harvest delivered() {
            // Several carrots from one field, or several catches on the same outing, are one harvest window.
            if (produced && elapsed < DIGEST) return this;
            int next = produced ? Math.max(elapsed, (interval + elapsed) / 2) : 0;
            return new Harvest(station, 0, next, true);
        }

        int remaining() {
            int cycle = interval == 0 ? DAY : interval;
            if (!produced || interval == 0) return Math.max(cycle, elapsed);
            if (elapsed >= cycle) return elapsed;
            return Math.max(DIGEST, cycle - elapsed);
        }
    }

    private static final AttachmentType<Harvest> HARVEST = AttachmentRegistry.createPersistent(
            net.minecraft.resources.Identifier.parse("totem-villagers:food_harvest_interval"), Harvest.CODEC);

    private static final java.util.WeakHashMap<Villager, Long> LAST_SAMPLE = new java.util.WeakHashMap<>();

    static Harvest harvest(Villager villager) {
        return ((AttachmentTarget)villager).getAttached(HARVEST);
    }

    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager v) LAST_SAMPLE.put(v, level.getGameTime());
        });
    }

    static void sample(ServerLevel level, Villager resident, long now) {
        Long previous = LAST_SAMPLE.put(resident, now);
        observe(level, resident, previous == null ? 0 : (int)Math.clamp(now - previous, 0L, 100L));
    }

    static void observe(ServerLevel level, Villager resident, int loadedTicks) {
        var data = (AttachmentTarget) resident;
        var old = data.getAttached(HARVEST);
        var station = station(level, resident);
        if (station == null) {
            if (old != null) data.removeAttached(HARVEST);
            return;
        }
        var next = old != null && station.equals(old.station()) ? old.after(loadedTicks) : new Harvest(station, 0, 0, false);
        if (!next.equals(old)) data.setAttached(HARVEST, next);
    }

    /** Called only once an edible output has actually been deposited. */
    public static void delivered(ServerLevel level, Villager producer) {
        var station = station(level, producer);
        if (station == null) return;
        var data = (AttachmentTarget) producer;
        var old = data.getAttached(HARVEST);
        var harvest = old != null && station.equals(old.station()) ? old : new Harvest(station, 0, 0, false);
        var next = harvest.delivered();
        if (!next.equals(old)) data.setAttached(HARVEST, next);
    }

    static boolean coversNextMeal(ServerLevel level, List<? extends Villager> residents, VillagerWorkInventorySavedData inventories) {
        return coversNextMeal(level, residents, inventories, null, 0, false);
    }

    static boolean coversNextMeal(ServerLevel level, List<? extends Villager> residents, VillagerWorkInventorySavedData inventories,
                                  Villager customer, int committedEmeralds, boolean deliveringHoe) {
        int soonest = Integer.MAX_VALUE;
        long stored = 0;
        for (var resident : residents) {
            stored += VillageProductionStockPolicy.storedNutrition(inventories.inventory(resident.getUUID()));
            ProductionParcel.get(level.getServer()).reconcile(level, resident);
            var station = station(level, resident);
            if (station == null || !level.isLoaded(station.pos()) || !productive(level, resident, inventories,
                    deliveringHoe && resident == customer)) continue;
            var harvest = harvest(resident);
            int wait = harvest != null && station.equals(harvest.station()) ? harvest.remaining() : DAY;
            soonest = Math.min(soonest, wait);
        }
        if (soonest == Integer.MAX_VALUE) return false;
        long needed = 0;
        var requirements = new java.util.LinkedHashMap<java.util.UUID, Integer>();
        boolean selfSufficient = true;
        boolean heals = level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);
        for (var resident : residents) {
            int recovery = heals ? (int)Math.ceil(Math.max(0, resident.getMaxHealth() - resident.getHealth()) * 1.5) : 0;
            int target = requiredNutrition(soonest, VillagerNutrition.foodLevel(resident), recovery);
            requirements.put(resident.getUUID(), target);
            needed += target;
            selfSufficient &= VillageProductionStockPolicy.storedNutrition(inventories.inventory(resident.getUUID())) >= target;
        }
        if (stored < needed) return false;
        if (selfSufficient) return true;
        var projection = inventories.copyAccounts(requirements.keySet());
        var emerald = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD);
        if (committedEmeralds > 0 && projection.inventory(customer.getUUID())
                .takeExactMatchingItem(emerald.copyWithCount(committedEmeralds)).isEmpty()) return false;
        for (var buyer : residents) {
            var wallet = projection.inventory(buyer.getUUID());
            int goal = requirements.get(buyer.getUUID());
            if (VillageProductionStockPolicy.storedNutrition(wallet) >= goal) continue;
            for (var producer : residents) {
                if (buyer == producer || !VillagerFoodEconomyRuntime.isFoodProducer(producer)
                        || buyer.distanceToSqr(producer) > 32 * 32 || !(buyer.distanceToSqr(producer) <= 9 && buyer.hasLineOfSight(producer)
                        || pathReaches(buyer, producer.blockPosition()))) continue;
                var pantry = projection.inventory(producer.getUUID());
                while (VillageProductionStockPolicy.storedNutrition(wallet) < goal) {
                    if (wallet.countMatchingItem(emerald) < 1) return false;
                    var pack = VillagerFoodEconomyRuntime.foodForSale(pantry).orElse(null);
                    if (pack == null) break;
                    int nutrition = pack.stream().mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount()).sum();
                    if (VillageProductionStockPolicy.storedNutrition(pantry) - nutrition < requirements.get(producer.getUUID())) break;
                    var goods = pantry.reserveExactMatching(pack).orElseThrow();
                    var payment = wallet.reserveExactMatchingItem(emerald).orElseThrow();
                    if (!pantry.canInsertExact(emerald) || !wallet.canInsertAllExact(pack)) {
                        goods.rollback();
                        payment.rollback();
                        break;
                    }
                    wallet.insertAllExact(pack);
                    goods.commitWithReturn(emerald);
                    payment.commit();
                }
                if (VillageProductionStockPolicy.storedNutrition(wallet) >= goal) break;
            }
            if (VillageProductionStockPolicy.storedNutrition(wallet) < goal) return false;
        }
        return true;
    }

    private static boolean reachable(Villager v, net.minecraft.core.BlockPos pos) {
        return v.blockPosition().distSqr(pos) <= 9 || pathReaches(v, pos);
    }

    private static boolean pathReaches(Villager v, net.minecraft.core.BlockPos pos) {
        var path = v.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    private static boolean productive(ServerLevel level, Villager worker, VillagerWorkInventorySavedData inventories, boolean deliveringHoe) {
        var claim = ProductionParcel.get(level.getServer()).claim(worker.getUUID()).orElseThrow();
        var stock = inventories.inventory(worker.getUUID());
        if (!dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, worker, claim.station().pos())) return false;
        if (claim.role().equals("minecraft:fisherman"))
            return VillagerFishermanWorkRuntime.hasViableFishingParcel(level, worker, claim, stock);
        if (!deliveringHoe && dev.totem.villagers.world.FarmerWorldWorkAction.bestHoe(stock).isEmpty()) return false;
        return claim.cells().stream().filter(level::isLoaded).anyMatch(soil ->
                ProductionParcel.availableCell(level, worker, soil, true)
                        && dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, worker, soil.above())
                        && dev.totem.villagers.world.FarmerWorldWorkAction.growsFood(level, soil.above()) && reachable(worker, soil.above()));
    }

    static int requiredNutrition(int horizon, int foodLevel, int recovery) {
        int pulses = Math.ceilDiv(horizon, DIGEST);
        return Math.max(VillagerNutrition.MAX_FOOD_LEVEL, VillagerNutrition.EAT_UNTIL + pulses)
                + Math.max(0, VillagerNutrition.EAT_UNTIL - foodLevel) + recovery;
    }

    private static GlobalPos station(ServerLevel level, Villager worker) {
        var role = worker.getVillagerData().profession();
        String id;
        if (role.is(VillagerProfession.FARMER)) id = "minecraft:farmer";
        else if (role.is(VillagerProfession.FISHERMAN)) id = "minecraft:fisherman";
        else return null;
        return ProductionParcel.get(level.getServer()).claim(worker.getUUID())
                .filter(c -> c.role().equals(id) && c.station().dimension().equals(level.dimension())
                        && c.home().isPresent() && c.home().equals(worker.getBrain().getMemory(MemoryModuleType.HOME))
                        && worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(c.station()::equals).isPresent())
                .map(ProductionParcel.Claim::station).orElse(null);
    }

    private VillageFoodForecast() {}
}
