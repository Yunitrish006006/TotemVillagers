package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import dev.totem.villagers.guard.GuardDefenceOrder;
import dev.totem.villagers.guard.GuardDefenceOrderDefinitions;
import dev.totem.villagers.guard.GuardPost;
import dev.totem.villagers.inventory.VillagerWorkInventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A staffed guard post buys equipment from its village, with paid trials for new armorers and weaponsmiths. */
public final class GuardEquipmentOrders {
    private static final int WAIT = 48000;

    private record Gear(String id, String profession, net.minecraft.world.item.Item item,
                        net.minecraft.world.level.block.Block station, int price, VillageWorkshopApprenticeship.SourceCareer source) {}
    private static final Gear SWORD = new Gear("totem:weaponsmith_iron_sword", "minecraft:weaponsmith", Items.IRON_SWORD,
            Blocks.GRINDSTONE, 4, VillageWorkshopApprenticeship.SourceCareer.TOOLSMITH);
    private static final Gear HELMET = new Gear("totem:armorer_iron_helmet", "minecraft:armorer", Items.IRON_HELMET,
            Blocks.BLAST_FURNACE, 7, VillageWorkshopApprenticeship.SourceCareer.TOOLSMITH);
    private static final Gear BOOTS = new Gear("totem:leatherworker_leather_boots", "minecraft:leatherworker", Items.LEATHER_BOOTS,
            Blocks.CAULDRON, 3, VillageWorkshopApprenticeship.SourceCareer.UNEMPLOYED);
    private static final Gear MAP = new Gear("totem:cartographer_empty_map", "minecraft:cartographer", Items.MAP,
            Blocks.CARTOGRAPHY_TABLE, 5, VillageWorkshopApprenticeship.SourceCareer.LIBRARIAN);
    private static final Gear LAMP = new Gear("totem:cleric_glowstone", "minecraft:cleric", Items.GLOWSTONE,
            Blocks.BREWING_STAND, 3, VillageWorkshopApprenticeship.SourceCareer.MINER);

    private record Demand(UUID post, long pad, GlobalPos home, String order, int ticks, long seen) {
        static final Codec<Demand> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("post").forGetter(Demand::post),
                Codec.LONG.fieldOf("pad").forGetter(Demand::pad), GlobalPos.CODEC.fieldOf("home").forGetter(Demand::home),
                Codec.STRING.fieldOf("order").forGetter(Demand::order), Codec.intRange(0, WAIT).fieldOf("ticks").forGetter(Demand::ticks),
                Codec.LONG.fieldOf("seen").forGetter(Demand::seen)).apply(i, Demand::new));
    }

    private record Lease(long position, UUID smith) {
        static final Codec<Lease> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("position").forGetter(Lease::position),
                UUIDUtil.CODEC.fieldOf("smith").forGetter(Lease::smith)).apply(i, Lease::new));
    }
    private static final AttachmentType<List<Lease>> LEASES = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:automatic_smith_tickets"), Lease.CODEC.listOf());

    private static final AttachmentType<Demand> DEMAND = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:guard_equipment_order"), Demand.CODEC);
    private static final AttachmentType<Demand> LIGHT_DEMAND = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:post_lighting_order"), Demand.CODEC);
    private static final AttachmentType<GlobalPos> SMITH_SITE = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:automatic_smith_station"), GlobalPos.CODEC);
    private static final AttachmentType<UUID> ORIGIN = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:smith_original_mine"), UUIDUtil.CODEC);

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Villager v)) return;
            var data = (AttachmentTarget)v;
            for (var key : List.of(DEMAND, LIGHT_DEMAND)) {
                var d = data.getAttached(key);
                if (d != null) data.setAttached(key, new Demand(d.post(), d.pad(), d.home(), d.order(), d.ticks(), level.getGameTime()));
            }
        });
    }

    static void appointedSpecialist(Villager v, GlobalPos station) {
        var data = (AttachmentTarget)v;
        var chunk = (AttachmentTarget)((ServerLevel)v.level()).getChunkAt(station.pos());
        var leases = new java.util.ArrayList<>(chunk.getAttachedOrElse(LEASES, List.of()));
        leases.removeIf(l -> l.position() == station.pos().asLong() || l.smith().equals(v.getUUID()));
        leases.add(new Lease(station.pos().asLong(), v.getUUID()));
        chunk.setAttached(LEASES, List.copyOf(leases));
        data.setAttached(SMITH_SITE, station);
        data.removeAttached(ORIGIN);
    }

    public static void removedTable(net.minecraft.world.level.chunk.ChunkAccess chunk, BlockPos pos) {
        var data = (AttachmentTarget)chunk;
        var old = data.getAttached(LEASES);
        if (old == null || old.stream().noneMatch(l -> l.position() == pos.asLong())) return;
        var next = old.stream().filter(l -> l.position() != pos.asLong()).toList();
        if (next.isEmpty()) data.removeAttached(LEASES);
        else data.setAttached(LEASES, next);
    }

    static void originZone(Villager v, UUID id) { ((AttachmentTarget)v).setAttached(ORIGIN, id); }

    public static void forgetSmith(Villager v) {
        var data = (AttachmentTarget)v;
        data.removeAttached(SMITH_SITE);
        data.removeAttached(ORIGIN);
        if (!dev.totem.villagers.worker.TotemVillagerProfessions.GUARD_ID.equals(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().profession().value()))) {
            data.removeAttached(DEMAND);
            data.removeAttached(LIGHT_DEMAND);
        }
    }

    static void observe(ServerLevel level, Villager guard, long now) {
        var data = (AttachmentTarget)guard;
        var home = guard.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        var post = VillageWorkshopApprenticeship.responsiblePost(level, guard);
        if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED
                || post == null || guard.isBaby() || !guard.isAlive() || home == null || !home.dimension().equals(level.dimension())
                || !level.isLoaded(home.pos()) || !level.getBlockState(home.pos()).is(BlockTags.BEDS)) {
            data.removeAttached(DEMAND);
            data.removeAttached(LIGHT_DEMAND);
            return;
        }
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(guard.getUUID());
        for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.FEET)) {
            if (!guard.getItemBySlot(slot).isEmpty()) continue;
            var tag = slot == EquipmentSlot.HEAD ? ItemTags.HEAD_ARMOR : ItemTags.FOOT_ARMOR;
            stock.snapshot().stream().filter(s -> usable(s) && s.is(tag))
                    .findFirst().flatMap(s -> stock.takeExactMatchingItem(s.copyWithCount(1)))
                    .ifPresent(piece -> guard.setItemSlot(slot, piece));
        }
        Gear gear;
        if (!weapon(guard.getMainHandItem()) && stock.snapshot().stream().noneMatch(GuardEquipmentOrders::weapon)) gear = SWORD;
        else if (guard.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) gear = HELMET;
        else if (guard.getItemBySlot(EquipmentSlot.FEET).isEmpty()) gear = BOOTS;
        else {
            if (needsPostMap(level, guard, post, stock))
                request(level, guard, post, home, stock, MAP, DEMAND, now);
            else data.removeAttached(DEMAND);
            if (needsPostLamp(level, guard, post, stock))
                request(level, guard, post, home, stock, LAMP, LIGHT_DEMAND, now);
            else data.removeAttached(LIGHT_DEMAND);
            return;
        }
        data.removeAttached(LIGHT_DEMAND);
        request(level, guard, post, home, stock, gear, DEMAND, now);
    }

    private static void request(ServerLevel level, Villager guard, GuardPost post, GlobalPos home,
                                VillagerWorkInventory stock, Gear gear, AttachmentType<Demand> key, long now) {
        var data = (AttachmentTarget) guard;
        String id = gear.id();
        var product = new ItemStack(gear.item());
        var price = new ItemStack(Items.EMERALD, gear.price());
        var station = gear.station();
        var order = WorkOrderDefinitions.catalog().snapshot().get(id);
        if (stock.countMatchingItem(price) < price.getCount() || order == null || !order.allowedSources().contains(WorkSource.WORKSHOP)
                || !order.professionId().equals(gear.profession()) || !order.matchesOutput(product, level.registryAccess())) {
            data.removeAttached(key);
            return;
        }
        var old = data.getAttached(key);
        boolean same = old != null && old.post().equals(post.villageId()) && old.pad() == post.packedConstructionPad()
                && old.home().equals(home) && old.order().equals(id);
        if (same && old.seen() == now) return;
        int ticks = same ? Math.min(WAIT, old.ticks() + (int)Math.clamp(now - old.seen(), 0L, 100L)) : 0;
        data.setAttached(key, new Demand(post.villageId(), post.packedConstructionPad(), home, id, ticks, now));
        if (!VillageWorkshopApprenticeship.available(level, guard) || !VillagerWorkNeeds.canWork(guard)
                || guard.blockPosition().distSqr(home.pos()) > 32 * 32) return;
        if (VillageWorkshopApprenticeship.supplyFromWorkshop(level, guard, home, order, station, product, price)
                || ticks >= WAIT && VillageWorkshopApprenticeship.fillOrder(level, guard, home, order, station,
                        product, price, gear.source(), post.ownerId(),
                        BlockPos.of(post.packedConstructionPad()))) data.removeAttached(key);
    }

    private static boolean needsPostLamp(ServerLevel level, Villager guard, GuardPost post,
                                         VillagerWorkInventory stock) {
        var pad = BlockPos.of(post.packedConstructionPad());
        var lamp = pad.above(3);
        var roof = lamp.above();
        if (!level.isLoaded(roof) || !level.getBlockState(lamp).isAir()
                || !level.getBlockState(roof).isFaceSturdy(level, roof, net.minecraft.core.Direction.DOWN)
                || level.getBrightness(LightLayer.BLOCK, pad) >= 8
                || !WorldWorkPermissions.mayUseHouseholdLand(level, guard, lamp)
                || !WorldWorkPermissions.mayUseHouseholdLand(level, guard, roof)) return false;
        var order = GuardDefenceOrderDefinitions.get(GuardDefenceOrder.VANILLA_IRON_GOLEM.id()).orElse(null);
        if (order == null || order.placements().stream().anyMatch(p -> p.x() == 0 && p.z() == 0
                && (p.y() == 3 || p.y() == 4))) return false;
        var block = new ItemStack(Items.GLOWSTONE);
        boolean carrying = stock.countMatchingItem(block) > 0;
        var center = Vec3.atCenterOf(lamp);
        boolean inReach = guard.distanceToSqr(center) <= 16 && level.clip(new ClipContext(guard.getEyePosition(), center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, guard)).getType() == HitResult.Type.MISS;
        if (!inReach) {
            var postEye = Vec3.atBottomCenterOf(pad).add(0, guard.getEyeHeight(), 0);
            if (level.clip(new ClipContext(postEye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, guard))
                    .getType() != HitResult.Type.MISS) return false;
            var path = guard.getNavigation().createPath(pad, 0);
            if (path == null || !path.canReach()) return false;
            if (carrying && VillageWorkshopApprenticeship.available(level, guard) && VillagerWorkNeeds.canWork(guard))
                guard.getNavigation().moveTo(path, .5);
        }
        if (!carrying) return true;
        if (!inReach || !VillageWorkshopApprenticeship.available(level, guard) || !VillagerWorkNeeds.canWork(guard)
                || !level.isUnobstructed(Blocks.GLOWSTONE.defaultBlockState(), lamp, CollisionContext.empty())) return false;
        var reserved = stock.reserveExactMatchingItem(block).orElse(null);
        if (reserved != null) {
            if (level.setBlockAndUpdate(lamp, Blocks.GLOWSTONE.defaultBlockState())) reserved.commit();
            else reserved.rollback();
        }
        return false;
    }

    private static boolean needsPostMap(ServerLevel level, Villager guard, GuardPost post,
                                        VillagerWorkInventory stock) {
        var pad = BlockPos.of(post.packedConstructionPad());
        String marker = "post_" + post.villageId() + "_" + post.packedConstructionPad();
        var decoration = net.minecraft.core.component.DataComponents.MAP_DECORATIONS;
        if (stock.snapshot().stream().anyMatch(stack -> stack.is(Items.FILLED_MAP)
                && stack.has(net.minecraft.core.component.DataComponents.MAP_ID)
                && stack.getOrDefault(decoration, net.minecraft.world.item.component.MapDecorations.EMPTY).decorations().containsKey(marker))) return false;
        var blank = new ItemStack(Items.MAP);
        if (stock.countMatchingItem(blank) == 0) return true;
        if (!VillageWorkshopApprenticeship.available(level, guard) || !VillagerWorkNeeds.canWork(guard)
                || !level.isLoaded(pad) || guard.blockPosition().distSqr(pad) > 16
                || !WorldWorkPermissions.mayWork(level, guard, pad)) return false;
        var reservation = stock.reserveExactMatchingItem(blank).orElse(null);
        if (reservation == null) return false;
        if (stock.snapshot().stream().noneMatch(ItemStack::isEmpty)) {
            reservation.rollback();
            return false;
        }
        var map = net.minecraft.world.item.MapItem.create(level, pad.getX(), pad.getZ(), (byte)0, true, false);
        net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, pad, marker,
                net.minecraft.world.level.saveddata.maps.MapDecorationTypes.WHITE_BANNER);
        reservation.commitWithReturn(map);
        return false;
    }

    private static boolean weapon(ItemStack stack) {
        return usable(stack) && (stack.is(ItemTags.SWORDS) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW));
    }

    private static boolean usable(ItemStack stack) {
        return !stack.isEmpty() && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    static boolean canReleaseSpecialist(ServerLevel level, Villager worker, List<Villager> residents, UUID owner, BlockPos anchor) {
        var original = ((AttachmentTarget)worker).getAttached(SMITH_SITE);
        if (original == null || !original.equals(validStation(level, worker))
                || !((AttachmentTarget)level.getChunkAt(original.pos())).getAttachedOrElse(LEASES, List.of())
                        .contains(new Lease(original.pos().asLong(), worker.getUUID()))) return false;
        var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        if (assignments.getAssignment(worker.getUUID()).isPresent()) return false;
        if (!servesPost(level, worker, owner, anchor, assignments)) return false;
        for (var other : residents) {
            if (other == worker || WorkerAssignmentSavedData.forServer(level.getServer()).getAssignment(other.getUUID()).isPresent() || !VillagerWorkNeeds.canWork(other) || !VillageWorkshopApprenticeship.available(level, other)) continue;
            if (!other.getVillagerData().profession().equals(worker.getVillagerData().profession())) continue;
            var station = validStation(level, other);
            if (station == null || station.equals(original)) continue;
            if (!servesPost(level, other, owner, anchor, assignments)) continue;
            if (worker.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
                if (!canMeet(level, worker, other) || residents.stream().filter(BuilderMaterialOrders::awaitingBookshelf)
                        .anyMatch(builder -> !canMeet(level, builder, other))) continue;
                var shelf = WorkOrderDefinitions.catalog().snapshot().get("totem:librarian_bookshelf");
                var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(other.getUUID());
                if (WorkshopLocalMarket.hasSurplus(level, other, stock, new ItemStack(Items.BOOKSHELF))
                        || shelf != null && shelf.requiredInputs().stream().allMatch(input ->
                            WorkshopLocalMarket.hasSurplus(level, other, stock, new ItemStack(
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(input.itemId())), input.count())))) return true;
            } else if (ToolsmithVillageEconomyRuntime.canCoverOrders(level, worker, other)) return true;
        }
        return false;
    }

    private static boolean canMeet(ServerLevel level, Villager customer, Villager supplier) {
        var home = customer.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || !customer.getBoundingBox().inflate(32).intersects(supplier.getBoundingBox())
                || !VillageWorkshopApprenticeship.belongsToVillage(level, supplier, home)) return false;
        if (customer.distanceToSqr(supplier) <= 16 && customer.hasLineOfSight(supplier)) return true;
        var path = customer.getNavigation().createPath(supplier, 0);
        return path != null && path.canReach();
    }

    private static boolean servesPost(ServerLevel level, Villager worker, UUID owner, BlockPos anchor, WorkerAssignmentSavedData assignments) {
        var zoneId = ((AttachmentTarget)worker).getAttached(ORIGIN);
        return zoneId == null || owner != null && VillagerBuilderRuntime.servesWorksite(level,
                new WorkerAssignment(worker.getUUID(), "totem:miner", Optional.of(zoneId), Optional.empty()), owner,
                level.dimension().identifier().toString(), anchor, assignments);
    }

    private static GlobalPos validStation(ServerLevel level, Villager smith) {
        boolean library = smith.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
        if (!library && !smith.getVillagerData().profession().is(VillagerProfession.TOOLSMITH)) return null;
        var job = smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (job == null || !job.dimension().equals(level.dimension()) || !level.isLoaded(job.pos())
                || !level.getBlockState(job.pos()).is(library ? Blocks.LECTERN : Blocks.SMITHING_TABLE) || !WorldWorkPermissions.mayWork(level, smith, job.pos())) return null;
        boolean occupied = level.getPoiManager().findAllClosestFirstWithType(smith.getVillagerData().profession().value().heldJobSite(),
                job.pos()::equals, job.pos(), 1, PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent();
        if (!occupied) return null;
        if (LoadedVillagerCache.loaded(level).stream().anyMatch(v -> v != smith &&
                (v.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(job::equals).isPresent()
                || v.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).filter(job::equals).isPresent()))) return null;
        if (VillageWorkshopApprenticeship.atStation(level, smith, job.pos())) return job;
        var path = smith.getNavigation().createPath(job.pos(), 1);
        return path != null && path.canReach() ? job : null;
    }

    private GuardEquipmentOrders() {}
}
