package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.runtime.ToolsmithVillageEconomyRuntime.ReplacementTool;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import dev.totem.villagers.world.ShepherdWorldWorkAction;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** A funded, persistent shortage can support a new smith after the village has a food producer. */
public final class VillageWorkshopApprenticeship {
    enum SourceCareer {
        MINER("totem:miner", ReplacementTool.PICKAXE), LUMBERJACK("totem:lumberjack", ReplacementTool.AXE),
        LIBRARIAN("minecraft:librarian", null), TOOLSMITH("minecraft:toolsmith", null), UNEMPLOYED("minecraft:none", null);
        final String role;
        final ReplacementTool tool;
        SourceCareer(String role, ReplacementTool tool) { this.role = role; this.tool = tool; }
    }

    private static final int INTERVAL = 100;
    private static final int DEMAND_TICKS = 48000;
    private record Shortage(GlobalPos home, String order, int ticks, long lastSeen) {
        static final Codec<Shortage> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("home").forGetter(Shortage::home),
                Codec.STRING.fieldOf("order").forGetter(Shortage::order),
                Codec.intRange(0, DEMAND_TICKS).fieldOf("ticks").forGetter(Shortage::ticks),
                Codec.LONG.fieldOf("last_seen").forGetter(Shortage::lastSeen)
        ).apply(i, Shortage::new));
    }

    private static final AttachmentType<Shortage> SHORTAGE = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:replacement_tool_demand"), Shortage.CODEC);

    private static final AttachmentType<Shortage> ARROW_SHORTAGE = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:arrow_demand"), Shortage.CODEC);

    private VillageWorkshopApprenticeship() {}

    static void register() {
        GuardEquipmentOrders.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Villager worker)) return;
            var data = (AttachmentTarget) worker;
            for (var type : List.of(SHORTAGE, ARROW_SHORTAGE)) {
                var old = data.getAttached(type);
                if (old != null) data.setAttached(type, new Shortage(old.home(), old.order(), old.ticks(), level.getGameTime()));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) return;
            for (var level : server.getAllLevels()) {
                for (var resident : LoadedVillagerCache.loaded(level)) {
                    if (!VillagerRuntimeBudget.due(level.getGameTime(), resident.getUUID(), INTERVAL)) continue;
                    observe(level, resident, level.getGameTime());
                    if (role(resident).equals("totem:guard")) GuardEquipmentOrders.observe(level, resident, level.getGameTime());
                }
            }
        });
    }

    /** Keep the vanilla POI available for a trial in a settlement already growing or catching its own food. */
    public static boolean reservesStationForTrial(ServerLevel level, Villager resident) {
        var brain = resident.getBrain();
        var site = brain.getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).orElse(null);
        var home = brain.getMemory(MemoryModuleType.HOME).orElse(null);
        if (site == null || home == null || !site.dimension().equals(level.dimension()) || !home.dimension().equals(level.dimension())
                || !level.isLoaded(site.pos()) || !(level.getBlockState(site.pos()).is(Blocks.SMITHING_TABLE) || level.getBlockState(site.pos()).is(Blocks.STONECUTTER)
                        || level.getBlockState(site.pos()).is(Blocks.FLETCHING_TABLE) || level.getBlockState(site.pos()).is(Blocks.BLAST_FURNACE)
                        || level.getBlockState(site.pos()).is(Blocks.BREWING_STAND)
                        || level.getBlockState(site.pos()).is(Blocks.CARTOGRAPHY_TABLE) || level.getBlockState(site.pos()).is(Blocks.LECTERN) || level.getBlockState(site.pos()).is(Blocks.GRINDSTONE) || level.getBlockState(site.pos()).is(Blocks.LOOM) || level.getBlockState(site.pos()).is(Blocks.SMOKER) || RecipeBackedWorkshopAction.matchesStation(level, site.pos(), Blocks.CAULDRON))
                || !level.isLoaded(home.pos()) || !level.getBlockState(home.pos()).is(BlockTags.BEDS)
                || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || !VillagerResourceWorkforceRuntime.isEligibleCandidate(resident, WorkerAssignmentSavedData.forServer(level.getServer()))) return false;
        var parcels = ProductionParcel.get(level.getServer());
        return level.getEntitiesOfClass(Villager.class, resident.getBoundingBox().inflate(32), Villager::isAlive).stream()
                .anyMatch(v -> parcels.claim(v.getUUID()).filter(c -> c.home().filter(b -> b.dimension().equals(level.dimension())
                        && b.pos().distSqr(home.pos()) <= 32 * 32).isPresent()).isPresent());
    }

    static void observe(ServerLevel level, Villager customer, long now) {
        if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED) return;
        var data = (AttachmentTarget) customer;
        var tool = requestedTool(customer);
        var home = customer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        if (tool == null || !customer.isAlive() || customer.isBaby() || home == null || !home.dimension().equals(level.dimension())
                || tool.hasUsableTool(inventories.inventory(customer.getUUID()))) {
            data.removeAttached(SHORTAGE);
            return;
        }
        if (!level.isLoaded(home.pos())) return;
        if (!level.getBlockState(home.pos()).is(BlockTags.BEDS)) {
            data.removeAttached(SHORTAGE);
            return;
        }
        if (customer.blockPosition().distSqr(home.pos()) > 32 * 32) return;
        var buyer = inventories.inventory(customer.getUUID());
        var payment = new ItemStack(Items.EMERALD, tool.stonePrice());
        var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        if (buyer.countMatchingItem(payment) < payment.getCount() || !workingSite(level, customer, tool, assignments)) {
            data.removeAttached(SHORTAGE);
            return;
        }
        var old = data.getAttached(SHORTAGE);
        boolean sameOrder = old != null && old.home().equals(home) && old.order().equals(tool.stoneOrderId());
        if (sameOrder && now == old.lastSeen()) return;
        int ticks = sameOrder ? Math.min(DEMAND_TICKS, old.ticks() + (int) Math.clamp(now - old.lastSeen(), 0L, INTERVAL)) : 0;
        data.setAttached(SHORTAGE, new Shortage(home, tool.stoneOrderId(), ticks, now));
        if (ticks < DEMAND_TICKS || !available(level, customer)) return;
        var order = WorkOrderDefinitions.catalog().snapshot().get(tool.stoneOrderId());
        var product = new ItemStack(tool.stoneItem());
        if (order != null && order.professionId().equals("minecraft:toolsmith")
                && fillOrder(level, customer, home, order, Blocks.SMITHING_TABLE, product, payment, SourceCareer.MINER, null, home.pos())) data.removeAttached(SHORTAGE);
    }

    private record ArrowRequest(String key, java.util.UUID owner, BlockPos station) {}

    /** A funded order for an equipped guard or a tipped-arrow workshop carrying the matching potion. */
    static void observeArrowDemand(ServerLevel level, Villager customer, WorkOrder demand, long now) {
        var data = (AttachmentTarget)customer;
        var home = customer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || !home.dimension().equals(level.dimension()) || !level.isLoaded(home.pos())
                || !level.getBlockState(home.pos()).is(BlockTags.BEDS) || customer.isBaby() || !customer.isAlive()) {
            data.removeAttached(ARROW_SHORTAGE);
            return;
        }
        var buyer = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(customer.getUUID());
        var price = new ItemStack(Items.EMERALD);
        int arrows = buyer.countMatchingItem(new ItemStack(Items.ARROW));
        boolean guard = role(customer).equals("totem:guard");
        ArrowRequest request = guard ? guardArrowOrder(level, customer, buyer) : workshopArrowOrder(level, customer, buyer, demand);
        int target = guard ? 4 : 8;
        if (request == null || arrows >= target || buyer.countMatchingItem(price) < (target - arrows + 3) / 4) {
            data.removeAttached(ARROW_SHORTAGE);
            return;
        }
        var old = data.getAttached(ARROW_SHORTAGE);
        boolean same = old != null && old.order().equals(request.key()) && old.home().equals(home);
        if (same && old.lastSeen() == now) return;
        int ticks = same ? Math.min(DEMAND_TICKS, old.ticks() + (int)Math.clamp(now - old.lastSeen(), 0L, 20L)) : 0;
        data.setAttached(ARROW_SHORTAGE, new Shortage(home, request.key(), ticks, now));
        if (now % 100 >= 20 || !available(level, customer) || customer.blockPosition().distSqr(home.pos()) > 32 * 32) return;
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:fletcher_arrows");
        var four = new ItemStack(Items.ARROW, 4);
        if (order == null || !order.professionId().equals("minecraft:fletcher") || !order.matchesOutput(four, level.registryAccess())
                || order.output().count() != 4 || !order.allowedSources().contains(WorkSource.WORKSHOP)) return;
        if (supplyFromWorkshop(level, customer, home, order, Blocks.FLETCHING_TABLE, four, price)
                || ticks >= DEMAND_TICKS && fillOrder(level, customer, home, order, Blocks.FLETCHING_TABLE, four,
                        price, SourceCareer.LUMBERJACK, request.owner(), request.station())) data.removeAttached(ARROW_SHORTAGE);
    }

    private static ArrowRequest workshopArrowOrder(ServerLevel level, Villager customer, VillagerWorkInventory buyer, WorkOrder demand) {
        var job = customer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (!role(customer).equals("minecraft:fletcher") || job == null || !job.dimension().equals(level.dimension())
                || !level.isLoaded(job.pos()) || !level.getBlockState(job.pos()).is(Blocks.FLETCHING_TABLE)
                || demand == null || !dev.totem.villagers.work.FletcherTippedArrowOrders.isOfferBoundTippedArrowOrder(demand)) return null;
        var offer = dev.totem.villagers.work.FletcherTippedArrowOrders.matchingOffer(demand, customer.getOffers(), level);
        var potion = offer == null ? null : dev.totem.villagers.work.FletcherTippedArrowOrders.lingeringPotionFor(offer).orElse(null);
        return potion != null && buyer.countMatchingItem(potion) > 0 ? new ArrowRequest(demand.id() + "/" + job.pos().asLong(), null, job.pos()) : null;
    }

    private static ArrowRequest guardArrowOrder(ServerLevel level, Villager guard, VillagerWorkInventory buyer) {
        if (buyer.snapshot().stream().noneMatch(s -> (s.is(Items.BOW) || s.is(Items.CROSSBOW))
                && s.getDamageValue() < s.getMaxDamage())) return null;
        var post = responsiblePost(level, guard);
        return post == null ? null : new ArrowRequest(post.villageId() + "/" + post.packedConstructionPad(), post.ownerId(), BlockPos.of(post.packedConstructionPad()));
    }

    static dev.totem.villagers.guard.GuardPost responsiblePost(ServerLevel level, Villager guard) {
        var village = WorkerAssignmentSavedData.forServer(level.getServer()).getAssignment(guard.getUUID())
                .filter(a -> a.roleId().equals("totem:guard")).flatMap(a -> a.managedVillageId())
                .flatMap(dev.totem.villagers.guard.ManagedVillageSavedData.forServer(level.getServer())::get).orElse(null);
        if (village == null || !village.guardVillagerId().equals(guard.getUUID()) || village.construction().isPresent()
                || !village.post().dimensionId().equals(level.dimension().identifier().toString())) return null;
        var post = BlockPos.of(village.post().packedConstructionPad());
        return level.isLoaded(post) && guard.blockPosition().distSqr(post) <= 32 * 32
                && WorldWorkPermissions.mayWork(level, guard, post) ? village.post() : null;
    }

    static List<Villager> households(ServerLevel level, Villager customer, GlobalPos home) {
        return level.getEntitiesOfClass(Villager.class, customer.getBoundingBox().inflate(32),
                v -> belongsToVillage(level, v, home));
    }

    static boolean belongsToVillage(ServerLevel level, Villager resident, GlobalPos home) {
        return resident.isAlive() && !resident.isBaby() && resident.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(b -> b.dimension().equals(level.dimension()) && b.pos().distSqr(home.pos()) <= 32 * 32
                        && level.isLoaded(b.pos()) && level.getBlockState(b.pos()).is(BlockTags.BEDS)).isPresent();
    }

    private record TrialSite(BlockPos pos, Sheep sheep) {}

    static boolean fillOrder(ServerLevel level, Villager customer, GlobalPos home, WorkOrder order,
                             net.minecraft.world.level.block.Block station, ItemStack product, ItemStack payment,
                             SourceCareer source, java.util.UUID owner, BlockPos anchor) {
        boolean wool = station == Blocks.LOOM && ShepherdWorldWorkAction.supports(order);
        if ((!wool && !order.allowedSources().contains(WorkSource.WORKSHOP)) || order.output().count() != product.getCount()
                || !order.matchesOutput(product, level.registryAccess()) || !available(level, customer)) return false;
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var buyer = inventories.inventory(customer.getUUID());
        var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        var profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(order.professionId()));
        var residents = households(level, customer, home);
        var parcels = ProductionParcel.get(level.getServer());
        if (residents.stream().anyMatch(v -> v != customer && role(v).equals(order.professionId())
                        && (!wool || VillagerShepherdWorkRuntime.canSupplyColour(level, v, customer, order, product, inventories)))
                || !VillageFoodForecast.coversNextMeal(level, residents, inventories, customer, payment.getCount(), product.is(Items.STONE_HOE))) return false;

        for (var worker : residents.stream().filter(v -> v != customer && available(level, v) && VillagerWorkNeeds.canWork(v)
                        && VillagerCareerHistory.mayChange(v) && !v.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE))
                .sorted(Comparator.comparing((Villager v) -> role(v).equals(source.role))
                        .thenComparingDouble(customer::distanceToSqr)).toList()) {
            var assigned = assignments.getAssignment(worker.getUUID()).orElse(null);
            if (owner != null && assigned != null && !VillagerBuilderRuntime.servesWorksite(level, assigned, owner,
                    level.dimension().identifier().toString(), anchor, assignments)) continue;
            if (!canLeave(level, worker, residents, assignments, source, owner, anchor)) continue;
            var trial = level.getPoiManager().findAllClosestFirstWithType(profession.heldJobSite(), p -> level.isLoaded(p)
                            && RecipeBackedWorkshopAction.matchesStation(level, p, station) && WorldWorkPermissions.mayUseHouseholdLand(level, worker, p),
                    home.pos(), 16, PoiManager.Occupancy.HAS_SPACE)
                    .map(com.mojang.datafixers.util.Pair::getSecond)
                    .filter(p -> !customer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .filter(j -> j.dimension().equals(level.dimension()) && j.pos().equals(p)).isPresent())
                    .filter(p -> atStation(level, worker, p)
                            || reachable(worker, p))
                    .map(p -> new TrialSite(p, wool ? VillagerShepherdWorkRuntime.trialSheep(level, worker, p, order).orElse(null) : null))
                    .filter(t -> !wool || t.sheep() != null).findFirst().orElse(null);
            if (trial == null) continue;
            var site = trial.pos();
            var seller = inventories.inventory(worker.getUUID());
            int batches = product.is(Items.LEATHER_BOOTS) ? 2 : 1;
            if (!seller.canReserveExact(order.requiredInputs()) || batches == 2 && seller.countMatchingItem(new ItemStack(Items.LEATHER)) < 8) {
                if (WorkshopLocalMarket.restock(level, worker, order, seller, batches)) return false;
                continue;
            }
            var sheep = trial.sheep();
            if (wool) {
                if (!VillagerShepherdWorkRuntime.prepareShearing(level, worker, sheep, seller)) continue;
            } else if (!atStation(level, worker, site)) {
                if (approach(worker, site)) return false;
                continue;
            }
            if (worker.distanceToSqr(customer) > 16 || !worker.hasLineOfSight(customer)) {
                if (approach(customer, worker.blockPosition())) return false;
                continue;
            }
            if (VillagerResourceWorkforceRuntime.claimExactJobSite(level, profession, site).isEmpty()) return false;
            boolean appointed = false;
            boolean pastureReserved = false;
            try {
                if (wool) {
                    pastureReserved = parcels.reservePasture(level, worker, site, sheep.blockPosition().below());
                    if (!pastureReserved) continue;
                    if (!new ShepherdWorldWorkAction().shear(level, worker, sheep, order, seller, buyer, payment)) continue;
                } else if (!deliver(level, worker, site, order, seller, buyer, payment, product, inventories)) continue;
                if ((source == SourceCareer.TOOLSMITH || source == SourceCareer.LIBRARIAN) && role(worker).equals(source.role)) {
                    worker.releasePoi(MemoryModuleType.JOB_SITE);
                    worker.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
                }
                assignments.removeAssignment(worker.getUUID());
                if (!pastureReserved) parcels.release(worker.getUUID());
                VillagerResourceWorkforceRuntime.establishVanillaProfession(level, worker, profession, site);
                if (order.professionId().equals("minecraft:toolsmith") && assigned != null)
                    assigned.workZoneId().ifPresent(id -> GuardEquipmentOrders.originZone(worker, id));
                VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
                appointed = true;
            } finally {
                if (!appointed) {
                    if (pastureReserved) parcels.release(worker.getUUID());
                    level.getPoiManager().release(site);
                }
            }
            return true;
        }
        return false;
    }

    static boolean supplyFromWorkshop(ServerLevel level, Villager customer, GlobalPos home, WorkOrder order,
                                      net.minecraft.world.level.block.Block station, ItemStack product, ItemStack paid) {
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var buyer = inventories.inventory(customer.getUUID());
        boolean wool = station == Blocks.LOOM && ShepherdWorldWorkAction.supports(order);
        if ((!wool && !order.allowedSources().contains(WorkSource.WORKSHOP)) || !order.matchesOutput(product, level.registryAccess())) return false;
        var suppliers = level.getEntitiesOfClass(Villager.class, customer.getBoundingBox().inflate(32),
                v -> v != customer && v.isAlive() && !v.isBaby() && role(v).equals(order.professionId())
                        && v.getBrain().getMemory(MemoryModuleType.HOME).filter(h -> h.dimension().equals(level.dimension())
                        && h.pos().distSqr(home.pos()) <= 32 * 32 && level.isLoaded(h.pos())
                        && level.getBlockState(h.pos()).is(BlockTags.BEDS)).isPresent());
        suppliers.sort(Comparator.comparing((Villager v) -> customer.distanceToSqr(v) > 16 || !customer.hasLineOfSight(v))
                .thenComparing(v -> inventories.inventory(v.getUUID()).countMatchingItem(product) < product.getCount())
                .thenComparingDouble(customer::distanceToSqr));
        for (var supplier : suppliers) {
            var job = supplier.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
            if (job == null || !job.dimension().equals(level.dimension()) || !level.isLoaded(job.pos())
                    || !RecipeBackedWorkshopAction.matchesStation(level, job.pos(), station)
                    || !dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, supplier, job.pos())
                    || !available(level, supplier) || !dev.totem.villagers.needs.VillagerWorkNeeds.canWork(supplier)
                    || !wool && !RecipeBackedWorkshopAction.supports(order, level, job.pos())) continue;
            if (VillagerWorkSavedData.forServer(level.getServer()).get(supplier.getUUID())
                    .flatMap(s -> s.activeWork()).isPresent()) continue;
            var seller = inventories.inventory(supplier.getUUID());
            boolean workingTool = dev.totem.villagers.work.VillagerProfessionEquipment.tool(order.professionId())
                    .filter(product::is).isPresent();
            var requiredStock = workingTool ? product.copyWithCount(product.getCount() + 1) : product;
            boolean finishedGoods = WorkshopLocalMarket.hasSurplus(level, supplier, seller, requiredStock);
            Sheep sheep = null;
            if (wool && !finishedGoods) {
                sheep = VillagerShepherdWorkRuntime.trialSheep(level, supplier, job.pos(), order).orElse(null);
                if (sheep == null || !VillagerShepherdWorkRuntime.prepareShearing(level, supplier, sheep, seller)) continue;
            }
            if (!finishedGoods && !seller.canReserveExact(order.requiredInputs())) {
                if (WorkshopLocalMarket.restock(level, supplier, order, seller)) return false;
                continue;
            }
            if (!finishedGoods && !wool && !atStation(level, supplier, job.pos())) {
                if (approach(supplier, job.pos())) return false;
                continue;
            }
            if (supplier.distanceToSqr(customer) > 16 || !customer.hasLineOfSight(supplier)) {
                if (approach(customer, supplier.blockPosition())) return false;
                continue;
            }
            if (finishedGoods) {
                if (WorkshopLocalMarket.exchange(inventories, seller, buyer, product, paid)) return true;
                continue;
            }
            if (wool) {
                if (new ShepherdWorldWorkAction().shear(level, supplier, sheep, order, seller, buyer, paid)) return true;
                continue;
            }
            if (deliver(level, supplier, job.pos(), order, seller, buyer, paid, product, inventories)) {
                return true;
            }
        }
        return false;
    }

    static boolean deliver(ServerLevel level, Villager worker, BlockPos site, WorkOrder order,
                                   VillagerWorkInventory seller, VillagerWorkInventory buyer, ItemStack price,
                                   ItemStack product, VillagerWorkInventorySavedData inventories) {
        if (!order.allowedSources().contains(WorkSource.WORKSHOP) || !order.matchesOutput(product, level.registryAccess())) return false;
        synchronized (inventories) {
            var materials = seller.reserveExact(order.requiredInputs()).orElse(null);
            if (materials == null) return false;
            var money = buyer.reserveExactMatchingItem(price).orElse(null);
            boolean paid = false;
            try {
                var action = new RecipeBackedWorkshopAction(level, worker, site, order);
                if (money == null || !seller.canInsertExact(price) || !buyer.canInsertExact(product)
                        || !action.complete() || !action.returnedItem().isEmpty()) return false;
                // Both destinations have capacity after their own debit, under the common inventory lock.
                materials.commitWithReturn(price);
                money.commitWithReturn(product);
                paid = true;
                return true;
            } finally {
                if (!paid) {
                    materials.rollback();
                    if (money != null) money.rollback();
                }
            }
        }
    }

    static boolean atStation(ServerLevel level, Villager worker, BlockPos site) {
        if (worker.distanceToSqr(Vec3.atCenterOf(site)) > 16) return false;
        var ray = new net.minecraft.world.level.ClipContext(worker.getEyePosition(), Vec3.atCenterOf(site),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker);
        return level.clip(ray).getBlockPos().equals(site);
    }

    private static boolean reachable(Villager worker, BlockPos site) {
        var path = worker.getNavigation().createPath(site, 1);
        return path != null && path.canReach();
    }

    private static boolean workingSite(ServerLevel level, Villager worker, ReplacementTool tool, WorkerAssignmentSavedData assignments) {
        var job = worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (tool == ReplacementTool.HOE) {
            if (job == null || !job.dimension().equals(level.dimension()) || !level.isLoaded(job.pos())
                    || !level.getBlockState(job.pos()).is(Blocks.COMPOSTER)) return false;
            var parcels = ProductionParcel.get(level.getServer());
            parcels.reconcile(level, worker);
            return parcels.claim(worker.getUUID()).filter(c -> c.role().equals("minecraft:farmer") && c.station().equals(job)).isPresent();
        }
        var zone = assignments.getAssignment(worker.getUUID()).filter(a -> a.roleId().equals(tool.professionId()))
                .flatMap(a -> a.workZoneId().flatMap(assignments::getZone)).orElse(null);
        if (zone == null || !zone.zone().dimensionId().equals(level.dimension().identifier().toString())) return false;
        var bounds = zone.zone();
        var p = worker.blockPosition();
        var nearest = new BlockPos(Math.clamp(p.getX(), bounds.minimum().x(), bounds.maximum().x()),
                Math.clamp(p.getY(), bounds.minimum().y(), bounds.maximum().y()), Math.clamp(p.getZ(), bounds.minimum().z(), bounds.maximum().z()));
        if (!level.isLoaded(nearest) || nearest.distSqr(p) > 32 * 32) return false;
        return tool == ReplacementTool.AXE || job != null && job.dimension().equals(level.dimension())
                && bounds.contains(bounds.dimensionId(), new dev.totem.villagers.worker.BlockCoordinate(job.pos().getX(), job.pos().getY(), job.pos().getZ()))
                && level.isLoaded(job.pos()) && level.getBlockState(job.pos()).is(Blocks.FURNACE);
    }

    static boolean idleHousehold(ServerLevel level, Villager worker, WorkerAssignmentSavedData assignments) {
        return VillagerResourceWorkforceRuntime.isEligibleCandidate(worker, assignments)
                && !worker.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)
                && ProductionParcel.get(level.getServer()).claim(worker.getUUID()).isEmpty();
    }

    static boolean mayStartForestry(ServerLevel level, Villager worker, WorkerAssignmentSavedData assignments) {
        if (hasPendingWork(level, worker) || !VillagerResourceWorkforceRuntime.isEligibleCandidate(worker, assignments)
                || worker.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)) return false;
        var parcel = ProductionParcel.get(level.getServer()).claim(worker.getUUID());
        return parcel.isEmpty() || parcel.get().role().equals("starter")
                && parcel.get().home().equals(worker.getBrain().getMemory(MemoryModuleType.HOME));
    }

    private static boolean hasPendingWork(ServerLevel level, Villager worker) {
        var state = VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID()).orElse(null);
        return state != null && (state.activeWork().isPresent() || !state.merchantStock().isEmpty() || !state.variantMerchantStock().isEmpty());
    }

    static boolean canLeave(ServerLevel level, Villager worker, List<Villager> residents, WorkerAssignmentSavedData assignments, SourceCareer source, java.util.UUID owner, BlockPos anchor) {
        if (hasPendingWork(level, worker)) return false;
        if (idleHousehold(level, worker, assignments)) return true;
        if (source == SourceCareer.UNEMPLOYED) return false;
        if (source == SourceCareer.TOOLSMITH || source == SourceCareer.LIBRARIAN)
            return role(worker).equals(source.role) && GuardEquipmentOrders.canReleaseSpecialist(level, worker, residents, owner, anchor);
        var sourceTool = source.tool;
        var donor = assignments.getAssignment(worker.getUUID()).orElse(null);
        if (donor == null || !donor.automaticallyAssigned() || !role(worker).equals(sourceTool.professionId())
                || !donor.roleId().equals(sourceTool.professionId())) return false;
        var zone = donor.workZoneId().flatMap(assignments::getZone).orElse(null);
        if (zone == null || !workingSite(level, worker, sourceTool, assignments)) return false;
        return residents.stream().anyMatch(v -> v != worker && role(v).equals(sourceTool.professionId()) && VillagerWorkNeeds.canWork(v)
                && workingSite(level, v, sourceTool, assignments)
                && assignments.getAssignment(v.getUUID()).filter(a -> a.roleId().equals(donor.roleId())
                        && !a.workZoneId().equals(donor.workZoneId())).filter(a -> a.workZoneId().flatMap(assignments::getZone)
                        .filter(z -> z.zone().dimensionId().equals(zone.zone().dimensionId())
                                && (z.zone().ownerId().equals(zone.zone().ownerId())
                                    || owner != null && (z.zone().personal() || zone.zone().personal())
                                    && VillagerBuilderRuntime.servesWorksite(level, donor, owner, zone.zone().dimensionId(), anchor, assignments)
                                    && VillagerBuilderRuntime.servesWorksite(level, a, owner, zone.zone().dimensionId(), anchor, assignments))
                                && VillagerWorldWorkRuntime.canKeepSupplying(level, v, z)).isPresent()).isPresent());
    }

    private static boolean approach(Villager worker, BlockPos target) {
        var path = worker.getNavigation().createPath(target, 1);
        return path != null && path.canReach() && worker.getNavigation().moveTo(path, .5);
    }

    static boolean available(ServerLevel level, Villager worker) {
        var brain = worker.getBrain();
        return !worker.isSleeping() && !worker.isTrading() && !level.isRaided(worker.blockPosition())
                && !brain.hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY)
                && !brain.hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                && !brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && !brain.hasMemoryValue(MemoryModuleType.AVOID_TARGET);
    }

    private static ReplacementTool requestedTool(Villager worker) {
        String id = role(worker);
        for (var tool : ReplacementTool.values()) if (tool.professionId().equals(id)) return tool;
        return null;
    }

    private static String role(Villager v) {
        return String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().profession().value()));
    }
}
