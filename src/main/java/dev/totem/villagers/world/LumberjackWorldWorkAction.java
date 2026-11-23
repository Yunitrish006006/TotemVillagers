package dev.totem.villagers.world;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.runtime.VillageProductionStockPolicy;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Harvests a complete bounded vertical tree trunk only after confirming a
 * leaf canopy and a viable replacement sapling. Every log and selected canopy
 * leaf is resolved through Minecraft's live block loot tables, so data-pack
 * changes and probabilistic leaf drops remain authoritative. Any failed
 * mutation or inventory insertion restores the captured tree. A persisted
 * generated-village Lumberyard is a rooted renewable plot; every other zone
 * must supply its replacement from the live drops or the worker's inventory.
 */
public final class LumberjackWorldWorkAction {
    private static final double WORK_REACH_SQUARED = 16.0D;
    private static final TagKey<Block> LEAVES = TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "lumberjack_tree_leaves"));
    public static final TagKey<Item> TREE_FIBRES = TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, Identifier.fromNamespaceAndPath("totem", "lumberjack_tree_fibers"));
    private static final int CANOPY_RADIUS = 2;
    private static final int CANOPY_HEIGHT = 2;
    private static final int MIN_TRUNK_HEIGHT = 2;
    private static final int MAX_TRUNK_HEIGHT = 16;

    public static boolean isEligibleBase(ServerLevel level, Villager lumberjack, WorkZone zone,
                                         BlockPos base, TagKey<Block> logs, WorkOrder order) {
        return trunk(level, lumberjack, zone, base, logs, order) != null;
    }

    public boolean complete(ServerLevel level, Villager lumberjack, WorkZone zone,
                            BlockPos base, TagKey<Block> logs, WorkOrder order,
                            VillagerWorkInventory inventory) {
        if (!"totem:lumberjack".equals(order.professionId())
                || lumberjack.distanceToSqr(Vec3.atCenterOf(base)) > WORK_REACH_SQUARED) {
            return false;
        }
        List<BlockPos> trunk = trunk(level, lumberjack, zone, base, logs, order);
        if (trunk == null) {
            return false;
        }
        ItemStack axe = bestAxe(inventory.snapshot()).orElse(null);
        if (axe == null) {
            return false;
        }
        Identifier replacementId = Identifier.tryParse(order.worldReplantBlockId());
        Block replacement = replacementId == null ? null : BuiltInRegistries.BLOCK.getValue(replacementId);
        if (replacement == null || replacement.asItem() == Items.AIR
                || !replacement.defaultBlockState().canSurvive(level, base)) {
            return false;
        }
        List<BlockPos> canopy = zone.naturalTimber() ? List.of(trunk.getLast().above())
                : harvestableCanopy(level, lumberjack, zone, trunk.getLast());
        List<BlockPos> harvestedBlocks = new ArrayList<>(trunk.size() + canopy.size());
        harvestedBlocks.addAll(trunk);
        harvestedBlocks.addAll(canopy);
        List<ItemStack> drops = actualDrops(level, lumberjack, harvestedBlocks, axe);
        // The first household order pays for a plain oak log. Validate the real loot before felling.
        if (zone.naturalTimber() && drops.stream().noneMatch(stack ->
                ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.OAK_LOG)))) return false;
        boolean rootedGeneratedPlot = isRenewableGeneratedLumberyardPlot(level, lumberjack, base);
        boolean replacementDropped = drops.stream().anyMatch(stack -> stack.is(replacement.asItem()));
        List<ItemStack> returns = new ArrayList<>(VillageProductionStockPolicy.boundedLumberjackReturns(
                inventory, drops, replacement.asItem()));
        ItemStack wornAxe = wearOnce(axe);
        if (!wornAxe.isEmpty()) {
            returns.add(wornAxe);
        }
        List<ItemStack> reservedInputs = new ArrayList<>();
        reservedInputs.add(axe);
        if (!rootedGeneratedPlot && !replacementDropped) {
            reservedInputs.add(new ItemStack(replacement.asItem()));
        }
        var reservation = inventory.reserveExactMatching(reservedInputs).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (!inventory.canInsertAllExact(returns)) {
            reservation.rollback();
            return false;
        }
        // Only an actually harvested canopy can supply a clipping. Preserve room for the axe and native drops.
        if (!canopy.isEmpty()) addClipping(inventory, level.getBlockState(canopy.getFirst()), drops, returns);
        Map<BlockPos, BlockState> captured = new LinkedHashMap<>();
        for (BlockPos harvestedBlock : harvestedBlocks) {
            captured.put(harvestedBlock, level.getBlockState(harvestedBlock));
        }
        for (BlockPos harvestedBlock : harvestedBlocks) {
            if (!level.destroyBlock(harvestedBlock, false, lumberjack, 512)) {
                restore(level, captured, zone, base, trunk, canopy);
                reservation.rollback();
                return false;
            }
        }
        if (!level.setBlock(base, replacement.defaultBlockState(), 3)) {
            restore(level, captured, zone, base, trunk, canopy);
            reservation.rollback();
            return false;
        }
        if (!reservation.commitWithReturns(returns)) {
            restore(level, captured, zone, base, trunk, canopy);
            reservation.rollback();
            return false;
        }
        lumberjack.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        lumberjack.playWorkSound();
        return true;
    }

    /** Generated Lumberyards alone carry a persistent root stock; manual zones never receive free saplings. */
    static void addClipping(VillagerWorkInventory inventory, BlockState foliage, List<ItemStack> drops, List<ItemStack> returns) {
        var clipping = new ItemStack(foliage.getBlock().asItem());
        if (clipping.is(TREE_FIBRES) && inventory.countMatchingItem(clipping) < 6
                && drops.stream().noneMatch(stack -> stack.is(clipping.getItem()))) {
            returns.add(clipping);
            if (!inventory.canInsertAllExact(returns)) returns.removeLast();
        }
    }

    public static boolean isRenewableGeneratedLumberyardPlot(ServerLevel level, Villager lumberjack, BlockPos base) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        Optional<java.util.UUID> zoneId = assignments.getAssignment(lumberjack.getUUID())
                .filter(assignment -> "totem:lumberjack".equals(assignment.roleId()))
                .flatMap(assignment -> assignment.workZoneId());
        if (zoneId.isEmpty()) {
            return false;
        }
        boolean baseInsideAssignedLumberyard = assignments.getZone(zoneId.orElseThrow())
                .filter(zone -> "totem:lumberjack".equals(zone.roleId()))
                .filter(zone -> zone.zone().contains(level.dimension().identifier().toString(),
                        new BlockCoordinate(base.getX(), base.getY(), base.getZ())))
                .isPresent();
        if (!baseInsideAssignedLumberyard) {
            return false;
        }
        return GeneratedVillageSavedData.forServer(level.getServer()).snapshot().stream()
                .filter(village -> village.dimensionId().equals(level.dimension().identifier().toString()))
                .anyMatch(village -> village.lumberjackZoneId().filter(zoneId.orElseThrow()::equals).isPresent());
    }

    private static List<BlockPos> trunk(ServerLevel level, Villager lumberjack, WorkZone zone,
                                        BlockPos base, TagKey<Block> logs, WorkOrder order) {
        if (order.worldReplantBlockId().isBlank() || !level.isLoaded(base) || !withinZone(level, zone, base)
                || level.getBlockState(base.below()).is(logs)) {
            return null;
        }
        List<BlockPos> result = new ArrayList<>();
        for (int index = 0; index < MAX_TRUNK_HEIGHT; index++) {
            BlockPos log = base.above(index);
            if (!level.isLoaded(log) || !level.getBlockState(log).is(logs)) {
                break;
            }
            if (!withinZone(level, zone, log)
                    || !WorldWorkPermissions.mayWork(level, lumberjack, log)) {
                return null;
            }
            result.add(log);
        }
        if (result.size() < MIN_TRUNK_HEIGHT
                || (result.size() == MAX_TRUNK_HEIGHT && level.isLoaded(base.above(MAX_TRUNK_HEIGHT))
                && level.getBlockState(base.above(MAX_TRUNK_HEIGHT)).is(logs))) {
            return null;
        }
        if (zone.naturalTimber()) {
            if (!zone.ownerId().equals(lumberjack.getUUID()) || !NaturalOakTimber.recordedTree(level, base, result.size())) return null;
            var crown = base.above(result.size());
            if (!withinZone(level, zone, crown) || !level.getBlockState(crown).is(Blocks.OAK_LEAVES)) return null;
            var zones = WorkerAssignmentSavedData.forServer(level.getServer());
            var parcels = dev.totem.villagers.worker.ProductionParcel.get(level.getServer());
            for (int y = -1; y <= result.size(); y++) {
                var pos = base.above(y);
                if (!WorldWorkPermissions.mayWork(level, lumberjack, pos) || zones.overlapsOtherZone(zone, pos)
                        || parcels.heldByAnother(level, pos, lumberjack)) return null;
                if (y >= 0 && y < result.size()) for (var side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    var beside = pos.relative(side);
                    if (!level.isLoaded(beside)) return null;
                    var neighbor = level.getBlockState(beside);
                    if (!neighbor.isAir() && !neighbor.is(LEAVES) && !neighbor.canBeReplaced()) return null;
                }
            }
        }
        return hasLeafCanopy(level, result.getLast()) ? List.copyOf(result) : null;
    }

    private static boolean withinZone(ServerLevel level, WorkZone zone, BlockPos position) {
        return zone.contains(level.dimension().identifier().toString(),
                new BlockCoordinate(position.getX(), position.getY(), position.getZ()));
    }

    private static boolean hasLeafCanopy(ServerLevel level, BlockPos top) {
        for (int y = 0; y <= CANOPY_HEIGHT; y++) {
            for (int x = -CANOPY_RADIUS; x <= CANOPY_RADIUS; x++) {
                for (int z = -CANOPY_RADIUS; z <= CANOPY_RADIUS; z++) {
                    BlockPos candidate = top.offset(x, y, z);
                    if (level.isLoaded(candidate) && level.getBlockState(candidate).is(LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<BlockPos> harvestableCanopy(ServerLevel level, Villager lumberjack, WorkZone zone, BlockPos top) {
        List<BlockPos> canopy = new ArrayList<>();
        for (int y = 0; y <= CANOPY_HEIGHT; y++) {
            for (int x = -CANOPY_RADIUS; x <= CANOPY_RADIUS; x++) {
                for (int z = -CANOPY_RADIUS; z <= CANOPY_RADIUS; z++) {
                    BlockPos candidate = top.offset(x, y, z);
                    if (level.isLoaded(candidate)
                            && withinZone(level, zone, candidate)
                            && level.getBlockState(candidate).is(LEAVES)
                            && WorldWorkPermissions.mayWork(level, lumberjack, candidate)) {
                        canopy.add(candidate);
                    }
                }
            }
        }
        return List.copyOf(canopy);
    }

    private static List<ItemStack> actualDrops(ServerLevel level, Villager lumberjack,
                                               List<BlockPos> harvestedBlocks, ItemStack axe) {
        List<ItemStack> drops = new ArrayList<>();
        for (BlockPos harvestedBlock : harvestedBlocks) {
            BlockState state = level.getBlockState(harvestedBlock);
            Block.getDrops(state, level, harvestedBlock, null, lumberjack, axe).stream()
                    .filter(stack -> !stack.isEmpty())
                    .map(ItemStack::copy)
                    .forEach(drops::add);
        }
        return List.copyOf(drops);
    }

    private static Optional<ItemStack> bestAxe(List<ItemStack> inventory) {
        return inventory.stream().filter(stack -> !stack.isEmpty() && axeRank(stack) >= 0)
                .map(stack -> stack.copyWithCount(1))
                .max(Comparator.comparingInt(LumberjackWorldWorkAction::axeRank));
    }

    /** One successfully felled tree consumes one point from the exact carried axe. */
    private static ItemStack wearOnce(ItemStack tool) {
        ItemStack worn = tool.copy();
        if (!worn.isDamageableItem()) {
            return worn;
        }
        if (worn.getDamageValue() + 1 >= worn.getMaxDamage()) {
            return ItemStack.EMPTY;
        }
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn;
    }

    private static int axeRank(ItemStack stack) {
        if (stack.is(Items.NETHERITE_AXE)) return 7;
        if (stack.is(Items.DIAMOND_AXE)) return 6;
        if (stack.is(Items.IRON_AXE)) return 5;
        if (stack.is(Items.COPPER_AXE)) return 4;
        if (stack.is(Items.STONE_AXE)) return 3;
        if (stack.is(Items.GOLDEN_AXE)) return 2;
        if (stack.is(Items.WOODEN_AXE)) return 1;
        return -1;
    }

    private static void restore(ServerLevel level, Map<BlockPos, BlockState> captured, WorkZone zone, BlockPos base,
                                List<BlockPos> trunk, List<BlockPos> canopy) {
        captured.forEach((position, state) -> level.setBlock(position, state, 3));
        if (zone.naturalTimber() && captured.entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue())))
            NaturalOakTimber.record(level.getChunkAt(base), base, java.util.Set.copyOf(trunk), java.util.Set.copyOf(canopy));
    }
}
