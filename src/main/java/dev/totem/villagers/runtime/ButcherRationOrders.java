package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** An unfilled ration purchase can support a smoker supplied with two batches of actual meat. */
final class ButcherRationOrders {
    private static final long PAUSED = Long.MIN_VALUE;
    private static final int WAIT = 48000;
    private static final int BATCH = 8;
    private record Request(GlobalPos home, UUID supplier, String order, int ticks, long seen) {
        static final Codec<Request> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("home").forGetter(Request::home), UUIDUtil.CODEC.fieldOf("supplier").forGetter(Request::supplier),
                Codec.STRING.fieldOf("order").forGetter(Request::order), Codec.intRange(0, WAIT).fieldOf("ticks").forGetter(Request::ticks),
                Codec.LONG.fieldOf("seen").forGetter(Request::seen)).apply(i, Request::new));
    }
    private static final AttachmentType<Request> DEMAND = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:butcher_ration_order"), Request.CODEC);
    private record Meat(Item raw, Item cooked, String id) {}
    private static final List<Meat> MEATS = List.of(new Meat(Items.PORKCHOP, Items.COOKED_PORKCHOP, "totem:butcher_cooked_porkchop"),
            new Meat(Items.MUTTON, Items.COOKED_MUTTON, "totem:butcher_cooked_mutton"),
            new Meat(Items.CHICKEN, Items.COOKED_CHICKEN, "totem:butcher_cooked_chicken"));

    private record Firing(UUID cook, UUID buyer, Meat meat, Item fuel, SmokerBlockEntity smoker, int duration, int worked, long seen, long lastWorked) {}
    // Inputs stay with the owners until completion. An interrupted batch can restart without refunds.
    private static final java.util.Map<ServerLevel, java.util.Map<BlockPos, Firing>> FIRES = new java.util.WeakHashMap<>();

    private static final java.util.Map<Villager, Long> LOADED = new java.util.WeakHashMap<>();

    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var level : server.getAllLevels()) workTick(level, level.getGameTime());
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            var jobs = FIRES.get(level);
            if (entity instanceof Villager && jobs != null)
                jobs.values().removeIf(f -> f.cook().equals(entity.getUUID()) || f.buyer().equals(entity.getUUID()));
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                server.getAllLevels().forEach(FIRES::remove));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Villager v)) return;
            LOADED.put(v, level.getGameTime());
            var target = (AttachmentTarget)v;
            var old = target.getAttached(DEMAND);
            if (old != null) target.setAttached(DEMAND, new Request(old.home(), old.supplier(), old.order(), old.ticks(), old.seen() == PAUSED ? PAUSED : level.getGameTime()));
        });
    }

    static void clear(Villager buyer) {
        var target = (AttachmentTarget)buyer;
        if (target.getAttached(DEMAND) == null) return;
        target.removeAttached(DEMAND);
        cancelCooking(buyer);
    }

    private static void cancelCooking(Villager buyer) {
        if (buyer.level() instanceof ServerLevel level && FIRES.get(level) != null)
            FIRES.get(level).values().removeIf(job -> job.buyer().equals(buyer.getUUID()));
    }

    static void pause(Villager buyer) {
        var target = (AttachmentTarget)buyer;
        var old = target.getAttached(DEMAND);
        if (old == null) return;
        if (old.seen() != PAUSED) target.setAttached(DEMAND, new Request(old.home(), old.supplier(), old.order(), old.ticks(), PAUSED));
        cancelCooking(buyer);
    }

    static void observe(ServerLevel level, Villager buyer, VillagerWorkInventorySavedData inventories, long now) {
        var home = buyer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || !home.dimension().equals(level.dimension()) || !level.isLoaded(home.pos())
                || !level.getBlockState(home.pos()).is(BlockTags.BEDS) || buyer.blockPosition().distSqr(home.pos()) > 32 * 32
                || !VillagerFoodEconomyRuntime.needsFoodRestock(buyer, inventories.inventory(buyer.getUUID()))
                || inventories.inventory(buyer.getUUID()).countMatchingItem(new ItemStack(Items.EMERALD)) == 0) {
            clear(buyer);
            return;
        }
        var residents = VillageWorkshopApprenticeship.households(level, buyer, home);
        var data = (AttachmentTarget)buyer;
        var old = data.getAttached(DEMAND);
        if (!VillageWorkshopApprenticeship.available(level, buyer)) {
            pause(buyer);
            return;
        }
        if (old != null && old.home().equals(home)) {
            var previous = level.getEntity(old.supplier());
            if (previous instanceof Villager cook && VillageWorkshopApprenticeship.belongsToVillage(level, cook, home)
                    && (!residents.contains(cook) || !VillageWorkshopApprenticeship.available(level, cook))
                    && (cook.getVillagerData().profession().is(VillagerProfession.BUTCHER) || VillageWorkshopApprenticeship.idleHousehold(
                            level, cook, WorkerAssignmentSavedData.forServer(level.getServer())))
                    && MEATS.stream().filter(m -> m.id().equals(old.order())).anyMatch(m ->
                            inventories.inventory(cook.getUUID()).countMatchingItem(new ItemStack(m.raw())) >= 2 * BATCH)) {
                pause(buyer);
                return;
            }
        }
        var candidates = residents.stream().filter(v -> v != buyer && VillageWorkshopApprenticeship.available(level, v) && VillagerWorkNeeds.canWork(v))
                .sorted(Comparator.comparing((Villager v) -> !v.getVillagerData().profession().is(VillagerProfession.BUTCHER))
                        .thenComparing(v -> old == null || !v.getUUID().equals(old.supplier())).thenComparingDouble(buyer::distanceToSqr)).toList();
        var restock = new java.util.ArrayList<Villager>();
        for (var worker : candidates) {
            boolean hired = worker.getVillagerData().profession().is(VillagerProfession.BUTCHER);
            var state = VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID()).orElse(null);
            if (state != null && state.activeWork().isPresent()) continue;
            if (!hired && (!VillageWorkshopApprenticeship.idleHousehold(level, worker, WorkerAssignmentSavedData.forServer(level.getServer()))
                    || !VillagerCareerHistory.mayChange(worker) || worker.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)
                    || state != null && (!state.merchantStock().isEmpty() || !state.variantMerchantStock().isEmpty()))) continue;
            var stock = inventories.inventory(worker.getUUID());
            var fuel = fuel(stock);
            if (conflicts(level, null, worker, buyer)) continue;
            if (buyer.distanceToSqr(worker) > 16 || !buyer.hasLineOfSight(worker)) {
                var path = buyer.getNavigation().createPath(worker, 1);
                if (path == null || !path.canReach()) continue;
            }
            if (stock.countMatchingItem(new ItemStack(Items.EMERALD)) > 1) restock.add(worker);
            if (fuel == null) continue;
            for (var meat : MEATS) {
                if (stock.countMatchingItem(new ItemStack(meat.raw())) < (hired ? BATCH : 2 * BATCH)) continue;
                var site = station(level, worker, buyer, hired);
                if (site == null) continue;
                int duration = duration(level, site, meat, fuel);
                if (duration < 1) continue;
                boolean same = old != null && old.supplier().equals(worker.getUUID()) && old.home().equals(home) && old.order().equals(meat.id());
                int ticks = same ? Math.min(WAIT, old.ticks() + loadedInterval(worker, old.seen(), now)) : 0;
                if ((hired || ticks >= WAIT) && !canFeed(level, residents, inventories, worker, buyer, meat, fuel, hired)) continue;
                data.setAttached(DEMAND, new Request(home, worker.getUUID(), meat.id(), ticks, now));
                if (hired || ticks >= WAIT) {
                    if (supply(level, worker, buyer, site, meat, fuel, hired, inventories, now, duration)) {
                        clear(buyer);
                        VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(buyer, inventories.inventory(buyer.getUUID()));
                    }
                }
                return;
            }
        }
        clear(buyer);
        for (var cook : restock) {
            boolean hired = cook.getVillagerData().profession().is(VillagerProfession.BUTCHER);
            var site = station(level, cook, buyer, hired);
            if (site != null && buySupplies(level, buyer, cook, residents, inventories, site, hired)) return;
        }
    }

    private record Purchase(Villager seller, ItemStack lot) {}

    private static boolean buySupplies(ServerLevel level, Villager customer, Villager cook, List<Villager> residents,
                                       VillagerWorkInventorySavedData accounts, BlockPos site, boolean hired) {
        var wallet = accounts.inventory(cook.getUUID());
        var coin = new ItemStack(Items.EMERALD);
        var suppliers = residents.stream().filter(v -> v != cook && v != customer
                && VillageWorkshopApprenticeship.available(level, v) && !reservedForSmoking(level, v)
                && !v.getVillagerData().profession().is(VillagerProfession.BUTCHER))
                .sorted(Comparator.comparingDouble(cook::distanceToSqr)).toList();
        for (var meat : MEATS) {
            int missing = (hired ? BATCH : 2 * BATCH) - wallet.countMatchingItem(new ItemStack(meat.raw()));
            int lots = Math.max(0, Math.ceilDiv(missing, BATCH));
            var fuel = fuel(wallet);
            if (wallet.countMatchingItem(coin) <= lots + (fuel == null ? 1 : 0)) continue;
            var forecast = accounts.copyAccounts(residents.stream().map(Villager::getUUID).toList());
            var quotes = new java.util.ArrayList<Purchase>(3);
            for (int batch = 0; batch < lots; batch++) {
                var raw = quote(level, cook, suppliers, forecast, new ItemStack(meat.raw(), BATCH), true);
                if (raw == null) break;
                quotes.add(raw);
            }
            if (quotes.size() < lots) continue;
            if (fuel == null) {
                for (var item : List.of(Items.CHARCOAL, Items.COAL)) {
                    if (duration(level, site, meat, item) < 1) continue;
                    var offer = quote(level, cook, suppliers, forecast, new ItemStack(item), false);
                    if (offer == null) continue;
                    fuel = item;
                    quotes.add(offer);
                    break;
                }
            }
            if (fuel == null || quotes.isEmpty() || duration(level, site, meat, fuel) < 1
                    || !canFeed(level, residents, forecast, cook, customer, meat, fuel, hired)) continue;
            var next = quotes.getFirst();
            if (cook.distanceToSqr(next.seller()) <= 16 && cook.hasLineOfSight(next.seller())) {
                if (buy(accounts, cook, next)) return true;
            } else {
                var path = cook.getNavigation().createPath(next.seller(), 1);
                if (path != null && path.canReach() && cook.getNavigation().moveTo(path, .5)) return true;
            }
        }
        return false;
    }

    private static Purchase quote(ServerLevel level, Villager cook, List<Villager> sellers, VillagerWorkInventorySavedData accounts,
                                  ItemStack lot, boolean meat) {
        for (var seller : sellers) {
            var stock = accounts.inventory(seller.getUUID());
            var available = meat ? lot : lot.copyWithCount(lot.getCount() + 1);
            if (!WorkshopLocalMarket.hasSurplus(level, seller, stock, available)
                    || meat && VillageProductionStockPolicy.storedNutrition(stock)
                        - VillagerNutrition.nutrition(lot) * lot.getCount() < VillagerNutrition.MAX_FOOD_LEVEL) continue;
            if (cook.distanceToSqr(seller) > 16 || !cook.hasLineOfSight(seller)) {
                var path = cook.getNavigation().createPath(seller, 1);
                if (path == null || !path.canReach()) continue;
            }
            var proposed = new Purchase(seller, lot);
            if (buy(accounts, cook, proposed)) return proposed;
        }
        return null;
    }

    private static boolean buy(VillagerWorkInventorySavedData accounts, Villager cook, Purchase quote) {
        return WorkshopLocalMarket.exchange(accounts, accounts.inventory(quote.seller().getUUID()),
                accounts.inventory(cook.getUUID()), quote.lot(), new ItemStack(Items.EMERALD));
    }

    private static int loadedInterval(Villager worker, long previous, long now) {
        if (previous == PAUSED) return 0;
        long delta = now - Math.max(previous, LOADED.getOrDefault(worker, now));
        return delta >= 0 && delta <= 100 ? (int)delta : 0;
    }

    private static boolean canFeed(ServerLevel level, List<Villager> residents, VillagerWorkInventorySavedData inventories,
                                   Villager worker, Villager buyer, Meat meat, Item fuel, boolean hired) {
        var forecast = inventories.copyAccounts(residents.stream().map(Villager::getUUID).toList());
        return exchange(forecast, worker, buyer, meat, fuel)
                && VillageProductionStockPolicy.storedNutrition(forecast.inventory(worker.getUUID())) >= VillagerNutrition.MAX_FOOD_LEVEL
                && (hired || VillageFoodForecast.coversNextMeal(level, residents, forecast));
    }

    private static Item fuel(VillagerWorkInventory stock) {
        for (var item : List.of(Items.CHARCOAL, Items.COAL)) {
            var stack = new ItemStack(item);
            if (stack.has(DataComponents.COOKING_FUEL) && stock.countMatchingItem(stack) > 0) return item;
        }
        return null;
    }

    private static int duration(ServerLevel level, BlockPos site, Meat meat, Item fuel) {
        WorkOrder order = WorkOrderDefinitions.catalog().snapshot().get(meat.id());
        if (order == null || !order.professionId().equals("minecraft:butcher") || !order.allowedSources().contains(WorkSource.WORKSHOP)
                || order.output().count() != 1 || order.requiredInputs().size() != 1 || order.requiredInputs().getFirst().count() != 1
                || !order.requiredInputs().getFirst().itemId().equals(BuiltInRegistries.ITEM.getKey(meat.raw()).toString())) return 0;
        var input = new SingleRecipeInput(new ItemStack(meat.raw()));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMOKING, input, level).orElse(null);
        if (recipe == null) return 0;
        var result = recipe.value().assemble(input);
        if (result.getCount() != 1 || !ItemStack.isSameItemSameComponents(result, new ItemStack(meat.cooked()))
                || !order.matchesOutput(result, level.registryAccess())) return 0;
        var smoker = (dev.totem.villagers.mixin.FurnaceFuelInvoker)level.getBlockEntity(site);
        var stack = new ItemStack(fuel);
        int burn = smoker.totemVillagers$burnDuration(level, stack);
        float speed = smoker.totemVillagers$cookingSpeed(level, stack);
        long time = BATCH * (long)Math.ceil(recipe.value().cookingTime() / (speed > 0 ? speed : 1));
        return time > 0 && time <= burn ? (int)time : 0;
    }

    private static BlockPos station(ServerLevel level, Villager worker, Villager buyer, boolean hired) {
        var role = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:butcher"));
        if (hired) return worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(s -> s.dimension().equals(level.dimension())
                && !conflicts(level, s.pos(), worker, buyer) && usable(level, worker, s.pos())).map(GlobalPos::pos).orElse(null);
        return level.getPoiManager().findAllClosestFirstWithType(role.heldJobSite(), p -> !conflicts(level, p, worker, buyer) && usable(level, worker, p), worker.blockPosition(),
                16, PoiManager.Occupancy.HAS_SPACE).map(com.mojang.datafixers.util.Pair::getSecond).findFirst().orElse(null);
    }

    private static boolean usable(ServerLevel level, Villager worker, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.SMOKER) || level.getBlockState(pos).getValue(BlockStateProperties.LIT)
                || !(level.getBlockEntity(pos) instanceof SmokerBlockEntity smoker) || !smoker.isEmpty()
                || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, pos)) return false;
        if (VillageWorkshopApprenticeship.atStation(level, worker, pos)) return true;
        var path = worker.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    private static boolean supply(ServerLevel level, Villager worker, Villager customer, BlockPos site,
                                  Meat meat, Item fuel, boolean hired, VillagerWorkInventorySavedData inventories, long now, int duration) {
        if (!VillageWorkshopApprenticeship.atStation(level, worker, site)) {
            worker.getNavigation().moveTo(site.getX() + .5, site.getY(), site.getZ() + .5, .5);
            return false;
        }
        if (customer.distanceToSqr(worker) > 16 || !customer.hasLineOfSight(worker)) {
            customer.getNavigation().moveTo(worker, .5);
            return false;
        }
        if (!finishCooking(level, site, worker, customer, meat, fuel, duration, now)) return false;
        var role = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:butcher"));
        if (!hired && VillagerResourceWorkforceRuntime.claimExactJobSite(level, role, site).isEmpty()) return false;
        boolean committed = false;
        try {
            if (!exchange(inventories, worker, customer, meat, fuel)) return false;
            if (!hired) {
                VillagerResourceWorkforceRuntime.establishVanillaProfession(level, worker, role, site);
                VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
            }
            committed = true;
            worker.playWorkSound();
            return true;
        } finally {
            if (!hired && !committed) level.getPoiManager().release(site);
        }
    }

    private static boolean finishCooking(ServerLevel level, BlockPos site, Villager worker, Villager customer,
                                         Meat meat, Item fuel, int duration, long now) {
        var jobs = FIRES.computeIfAbsent(level, ignored -> new java.util.HashMap<>());
        var old = jobs.get(site);
        if (old != null && now - old.seen() > 100) old = null;
        if (old != null && (!old.buyer().equals(customer.getUUID()) || !old.cook().equals(worker.getUUID()))) return false;
        var smoker = (SmokerBlockEntity)level.getBlockEntity(site);
        int worked = old != null && old.smoker() == smoker && old.meat() == meat && old.fuel() == fuel && old.duration() == duration ? old.worked() : 0;
        jobs.put(site, new Firing(worker.getUUID(), customer.getUUID(), meat, fuel, smoker, duration, worked, now,
                old == null ? now : old.lastWorked()));
        return worked >= duration;
    }

    private static boolean conflicts(ServerLevel level, BlockPos site, Villager worker, Villager buyer) {
        var jobs = FIRES.get(level);
        if (jobs == null) return false;
        for (var entry : jobs.entrySet()) {
            var job = entry.getValue();
            boolean pair = job.cook().equals(worker.getUUID()) && job.buyer().equals(buyer.getUUID());
            if (pair && (site == null || site.equals(entry.getKey()))) continue;
            if (entry.getKey().equals(site) || job.cook().equals(worker.getUUID()) || job.buyer().equals(buyer.getUUID())
                    || job.cook().equals(buyer.getUUID()) || job.buyer().equals(worker.getUUID())) return true;
        }
        return false;
    }

    static boolean reservedForSmoking(ServerLevel level, Villager worker) {
        var jobs = FIRES.get(level);
        if (jobs == null || jobs.isEmpty()) return false;
        var site = worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        return site != null && site.dimension().equals(level.dimension()) && jobs.containsKey(site.pos())
                || jobs.values().stream().anyMatch(job -> job.cook().equals(worker.getUUID()));
    }

    static void workTick(ServerLevel level, long now) {
        var jobs = FIRES.get(level);
        if (jobs == null || jobs.isEmpty()) return;
        jobs.entrySet().removeIf(entry -> {
            var job = entry.getValue();
            if (now <= job.lastWorked()) return false;
            var pos = entry.getKey();
            if (now - job.seen() > 100 || now - job.lastWorked() != 1 || !level.isLoaded(pos)
                    || level.getBlockEntity(pos) != job.smoker() || !job.smoker().isEmpty() || level.getBlockState(pos).getValue(BlockStateProperties.LIT)
                    || !(level.getEntity(job.cook()) instanceof Villager cook) || !(level.getEntity(job.buyer()) instanceof Villager buyer)
                    || !cook.isAlive() || !buyer.isAlive() || !VillageWorkshopApprenticeship.available(level, cook)
                    || !VillagerWorkNeeds.canWork(cook) || !VillageWorkshopApprenticeship.atStation(level, cook, pos)
                    || !WorldWorkPermissions.mayUseHouseholdLand(level, cook, pos)) return true;
            var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(cook.getUUID());
            if (stock.countMatchingItem(new ItemStack(job.meat().raw())) < BATCH
                    || stock.countMatchingItem(new ItemStack(job.fuel())) < 1) return true;
            entry.setValue(new Firing(job.cook(), job.buyer(), job.meat(), job.fuel(), job.smoker(), job.duration(),
                    Math.min(job.duration(), job.worked() + 1), job.seen(), now));
            return false;
        });
    }

    private static boolean exchange(VillagerWorkInventorySavedData accounts, Villager worker, Villager customer, Meat meat, Item fuel) {
        var serving = new ItemStack(meat.cooked());
        int nutrition = VillagerNutrition.nutrition(serving);
        if (nutrition <= 0) return false;
        int pack = Math.ceilDiv(VillagerFoodEconomyRuntime.INTERNAL_FOOD_NUTRITION, nutrition);
        if (pack > BATCH) return false;
        var stock = accounts.inventory(worker.getUUID());
        var buyer = accounts.inventory(customer.getUUID());
        var inputs = stock.reserveExactMatching(List.of(new ItemStack(meat.raw(), BATCH), new ItemStack(fuel))).orElse(null);
        if (inputs == null) return false;
        var price = new ItemStack(Items.EMERALD);
        var money = buyer.reserveExactMatchingItem(price).orElse(null);
        var returns = pack == BATCH ? List.of(price) : List.of(price, serving.copyWithCount(BATCH - pack));
        if (money == null || !buyer.canInsertExact(serving.copyWithCount(pack)) || !stock.canInsertAllExact(returns)) {
            inputs.rollback();
            if (money != null) money.rollback();
            return false;
        }
        inputs.commitWithReturns(returns);
        money.commitWithReturn(serving.copyWithCount(pack));
        return true;
    }

    private ButcherRationOrders() {}
}
