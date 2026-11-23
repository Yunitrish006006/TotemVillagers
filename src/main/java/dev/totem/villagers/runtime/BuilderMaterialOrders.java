package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.builder.BuilderSite;
import dev.totem.villagers.builder.VanillaVillageBlueprints.BlueprintBlock;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;

/** The builder buys the exact next blueprint block. The quoted price follows the kind of material requested. */
final class BuilderMaterialOrders {
    private record Trip(UUID site, int index, UUID supplier) {}
    private static final java.util.Map<Villager, Trip> TRIPS = new java.util.WeakHashMap<>();

    static boolean shopping(Villager buyer, BuilderSite site) {
        var trip = TRIPS.get(buyer);
        return trip != null && trip.site().equals(site.id()) && trip.index() == site.nextBlockIndex();
    }

    private static final int WAIT = 48000;
    private record Request(UUID site, int index, GlobalPos home, String material, int ticks, long seen) {
        static final Codec<Request> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("site").forGetter(Request::site),
                Codec.INT.fieldOf("index").forGetter(Request::index),
                GlobalPos.CODEC.fieldOf("home").forGetter(Request::home),
                Codec.STRING.fieldOf("material").forGetter(Request::material),
                Codec.intRange(0, WAIT).fieldOf("ticks").forGetter(Request::ticks),
                Codec.LONG.fieldOf("seen").forGetter(Request::seen)).apply(i, Request::new));
    }

    private static final AttachmentType<Request> REQUEST = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:masonry_request"), Request.CODEC);

    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Villager builder)) return;
            var old = ((AttachmentTarget)builder).getAttached(REQUEST);
            if (old != null) ((AttachmentTarget)builder).setAttached(REQUEST, new Request(old.site(), old.index(), old.home(), old.material(), old.ticks(), level.getGameTime()));
        });
    }

    static void request(ServerLevel level, Villager builder, BuilderSite site, BlueprintBlock planned, VillagerWorkInventory buyer, long now) {
        request(level, builder, site, planned, buyer, now, 1);
    }

    static boolean rawMaterial(BlueprintBlock planned) {
        var stack = new ItemStack(planned.state().getBlock().asItem());
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(Items.COBBLESTONE);
    }

    static void request(ServerLevel level, Villager builder, BuilderSite site, BlueprintBlock planned,
                        VillagerWorkInventory buyer, long now, int lot) {
        boolean bookshelf = planned.state().is(Blocks.BOOKSHELF);
        var paid = new ItemStack(Items.EMERALD, bookshelf ? 4 : 1);
        var home = builder.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        var data = (AttachmentTarget) builder;
        if (home == null || !home.dimension().equals(level.dimension()) || !level.isLoaded(home.pos())
                || !level.getBlockState(home.pos()).is(BlockTags.BEDS)) {
            data.removeAttached(REQUEST);
            return;
        }
        if (builder.isTrading()) return;
        var needed = new ItemStack(planned.state().getBlock().asItem());
        if (rawMaterial(planned)) {
            data.removeAttached(REQUEST);
            if (needed.is(ItemTags.PLANKS) && buyer.snapshot().stream().anyMatch(log -> VillageWoodCrafting.makesPlanks(level, log, needed))) {
                VillageWoodCrafting.craftPlanks(level, buyer, needed);
                return;
            }
            if (now % 100 < 10 || shopping(builder, site)) buyRawMaterial(level, builder, site, home, buyer, needed, lot);
            return;
        }
        if (builder.blockPosition().distSqr(home.pos()) > 32 * 32) return;
        if (!buyer.canReserveExactMatching(java.util.List.of(paid))) {
            data.removeAttached(REQUEST);
            return;
        }
        var material = Identifier.parse(planned.materialItemId());
        boolean wool = planned.state().is(BlockTags.WOOL);
        var catalog = WorkOrderDefinitions.catalog().snapshot();
        var order = catalog.get("totem:" + (bookshelf ? "librarian_" : wool ? "shepherd_" : "mason_") + material.getPath());
        if (order == null || !order.output().itemId().equals(planned.materialItemId())) return;
        var product = new ItemStack(planned.state().getBlock().asItem(), order.output().count());
        if (!order.matchesOutput(product, level.registryAccess())) return;
        if (wool) {
            if (!dev.totem.villagers.world.ShepherdWorldWorkAction.supports(order)) return;
        } else if (bookshelf) {
            if (!order.professionId().equals("minecraft:librarian") || product.getCount() != 1
                    || !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)) return;
        } else if (!order.professionId().equals("minecraft:mason") || order.requiredInputs().size() != 1
                || order.requiredInputs().getFirst().count() != 1
                || !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)
                || !dev.totem.villagers.workshop.RecipeBackedWorkshopAction.hasStonecuttingRecipe(level, order)) return;
        var station = bookshelf ? Blocks.LECTERN : wool ? Blocks.LOOM : Blocks.STONECUTTER;
        var old = data.getAttached(REQUEST);
        boolean same = old != null && old.site().equals(site.id()) && old.index() == site.nextBlockIndex()
                && old.home().equals(home) && old.material().equals(planned.materialItemId());
        if (same && old.seen() == now) return;
        int ticks = same ? Math.min(WAIT, old.ticks() + (int)Math.clamp(now - old.seen(), 0L, 10L)) : 0;
        data.setAttached(REQUEST, new Request(site.id(), site.nextBlockIndex(), home, planned.materialItemId(), ticks, now));
        if (same && now / 100 == old.seen() / 100 && !(old.ticks() < WAIT && ticks == WAIT)) return;
        if (VillageWorkshopApprenticeship.supplyFromWorkshop(level, builder, home, order, station, product, paid)) {
            data.removeAttached(REQUEST);
            return;
        }
        if (ticks >= WAIT && VillageWorkshopApprenticeship.fillOrder(level, builder, home, order,
                station, product, paid, wool || bookshelf ? VillageWorkshopApprenticeship.SourceCareer.UNEMPLOYED : VillageWorkshopApprenticeship.SourceCareer.MINER, site.ownerId(),
                net.minecraft.core.BlockPos.of(site.anchorPosition()))) {
            data.removeAttached(REQUEST);
        }
    }

    private static void buyRawMaterial(ServerLevel level, Villager builder, BuilderSite site, GlobalPos home,
                                       VillagerWorkInventory buyer, ItemStack needed, int lot) {
        boolean stone = needed.is(Items.COBBLESTONE);
        String role = stone ? "totem:miner" : "totem:lumberjack";
        var anchor = BlockPos.of(site.anchorPosition());
        if (home.pos().distSqr(anchor) > 48 * 48 || builder.blockPosition().distSqr(anchor) > 48 * 48) return;
        var assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var trip = TRIPS.remove(builder);
        boolean follow = trip != null && trip.site().equals(site.id()) && trip.index() == site.nextBlockIndex();
        var suppliers = level.getEntitiesOfClass(Villager.class, builder.getBoundingBox().inflate(32),
                v -> (!follow || v.getUUID().equals(trip.supplier())) && v != builder && v.isAlive() && !v.isBaby() && VillageWorkshopApprenticeship.available(level, v)
                        && v.blockPosition().distSqr(anchor) <= 48 * 48
                        && v.getVillagerData().profession().unwrapKey().filter(k -> k.identifier().toString().equals(role)).isPresent()
                        && v.getBrain().getMemory(MemoryModuleType.HOME).filter(h -> h.dimension().equals(home.dimension())
                        && h.pos().distSqr(home.pos()) <= 32 * 32 && level.isLoaded(h.pos())
                        && level.getBlockState(h.pos()).is(BlockTags.BEDS)).isPresent()
                        && assignments.getAssignment(v.getUUID()).filter(a -> a.roleId().equals(role)
                        && VillagerBuilderRuntime.servesWorksite(level, a, site.ownerId(), site.dimensionId(),
                        anchor, assignments)).isPresent());
        suppliers.sort(Comparator.comparingDouble(builder::distanceToSqr));
        for (var supplier : suppliers) {
            var seller = inventories.inventory(supplier.getUUID());
            for (var stack : seller.snapshot()) {
                if (stack.isEmpty()) continue;
                var goods = stack.copyWithCount(1);
                boolean matches = ItemStack.isSameItemSameComponents(goods, needed)
                        || needed.is(ItemTags.PLANKS) && VillageWoodCrafting.makesPlanks(level, goods, needed);
                if (!matches || !WorkshopLocalMarket.hasSurplus(level, supplier, seller, goods, true)) continue;
                var price = new ItemStack(Items.EMERALD, goods.is(ItemTags.LOGS) ? ToolsmithVillageEconomyRuntime.WOOD_PRICE : 1);
                if (!buyer.canReserveExactMatching(java.util.List.of(price))) continue;
                if (builder.distanceToSqr(supplier) <= 16 && builder.hasLineOfSight(supplier)) {
                    if (WorkshopLocalMarket.exchange(inventories, seller, buyer, goods, price)) {
                        for (int i = 1; i < lot && WorkshopLocalMarket.hasSurplus(level, supplier, seller, goods, true); i++) {
                            if (!WorkshopLocalMarket.exchange(inventories, seller, buyer, goods, price)) break;
                        }
                        return;
                    }
                } else {
                    var path = builder.getNavigation().createPath(supplier, 1);
                    if (VillagerBuilderRuntime.walk(builder, path)) {
                        TRIPS.put(builder, new Trip(site.id(), site.nextBlockIndex(), supplier.getUUID()));
                        return;
                    }
                }
                break;
            }
        }
    }

    static boolean awaitingBookshelf(Villager resident) {
        var request = ((AttachmentTarget)resident).getAttached(REQUEST);
        return request != null && request.material().equals("minecraft:bookshelf")
                && resident.getVillagerData().profession().is(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.VILLAGER_PROFESSION, Identifier.parse("totem:builder")));
    }

    private BuilderMaterialOrders() {}
}
