package dev.totem.villagers.world;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Harvests mature field crops into the Farmer's personal work inventory and
 * immediately replants them. Harvest output comes from the native crop loot
 * table; one real seed or crop item is held back for replanting.
 */
public final class FarmerWorldWorkAction {
    private static final List<CropDefinition> CROPS = List.of(
            crop("totem:farmer_wheat", "totem:farmer_mature_wheat", Blocks.WHEAT, Items.WHEAT, Items.WHEAT_SEEDS),
            crop("totem:farmer_carrot", "totem:farmer_mature_carrots", Blocks.CARROTS, Items.CARROT, Items.CARROT),
            crop("totem:farmer_potato", "totem:farmer_mature_potatoes", Blocks.POTATOES, Items.POTATO, Items.POTATO),
            crop("totem:farmer_beetroot", "totem:farmer_mature_beetroots", Blocks.BEETROOTS, Items.BEETROOT, Items.BEETROOT_SEEDS)
    );
    private static final Map<String, CropDefinition> CROPS_BY_ORDER = indexByOrder();
    private static final Map<Block, CropDefinition> CROPS_BY_BLOCK = indexByBlock();

    public boolean complete(
            ServerLevel level,
            Villager farmer,
            BlockPos cropPosition,
            WorkOrder order,
            VillagerWorkInventory inventory
    ) {
        CropDefinition crop = definition(order).orElse(null);
        if (crop == null || !isMature(level, cropPosition, crop)
                || !isFarmer(farmer)
                || !WorldWorkPermissions.mayWork(level, farmer, cropPosition)) {
            return false;
        }
        BlockState original = level.getBlockState(cropPosition);
        ItemStack hoe = bestHoe(inventory).orElse(null);
        if (hoe == null) {
            return false;
        }
        List<ItemStack> harvested = Block.getDrops(original, level, cropPosition, null, farmer, hoe);
        List<ItemStack> stored = retainReplantingInput(harvested, crop.replantingItem());
        if (stored == null) {
            return false;
        }
        var reservation = inventory.reserveExactMatchingItem(hoe).orElse(null);
        if (reservation == null) {
            return false;
        }
        List<ItemStack> returned = new ArrayList<>(stored);
        ItemStack wornHoe = wearOnce(hoe);
        if (!wornHoe.isEmpty()) {
            returned.add(wornHoe);
        }
        if (!inventory.canInsertAllExact(returned)) {
            reservation.rollback();
            return false;
        }
        if (!level.setBlock(cropPosition, crop.block().getStateForAge(0), 3)) {
            reservation.rollback();
            return false;
        }
        if (reservation.commitWithReturns(List.copyOf(returned))) {
            applyStoredBoneMeal(level, cropPosition, inventory);
            farmer.swing(InteractionHand.MAIN_HAND);
            farmer.playWorkSound();
            return true;
        }
        level.setBlock(cropPosition, original, 3);
        reservation.rollback();
        return false;
    }

    /** Reuses physical Composter output on the newly replanted crop. */
    private static void applyStoredBoneMeal(ServerLevel level, BlockPos cropPosition,
                                            VillagerWorkInventory inventory) {
        ItemStack one = new ItemStack(Items.BONE_MEAL);
        ItemStack stored = inventory.takeExactMatchingItem(one).orElse(null);
        if (stored == null) {
            return;
        }
        if (!BoneMealItem.growCrop(stored, level, cropPosition)) {
            inventory.insertExact(stored);
        }
    }

    public boolean hasUsableHoe(VillagerWorkInventory inventory) {
        return bestHoe(inventory).isPresent();
    }

    /** Kept for callers and data packs that explicitly ask about wheat. */
    public boolean isMatureWheat(ServerLevel level, BlockPos cropPosition) {
        return isMature(level, cropPosition, CROPS.getFirst());
    }

    public boolean isMatureCrop(ServerLevel level, BlockPos cropPosition, WorkOrder order) {
        return definition(order).map(crop -> isMature(level, cropPosition, crop)).orElse(false);
    }

    /**
     * Identifies a supported mature crop with one block-state lookup. Field
     * discovery uses this to scan once for all four crop orders instead of
     * rescanning the same area independently for every crop type.
     */
    public Optional<String> matureCropOrderId(ServerLevel level, BlockPos cropPosition) {
        if (!level.isLoaded(cropPosition)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(cropPosition);
        CropDefinition crop = CROPS_BY_BLOCK.get(state.getBlock());
        return crop != null && crop.block().isMaxAge(state)
                ? Optional.of(crop.orderId())
                : Optional.empty();
    }

    /** A conservative one-stack capacity probe used before the scheduled commit. */
    public Optional<ItemStack> primaryHarvest(WorkOrder order) {
        return definition(order).map(crop -> new ItemStack(crop.harvestItem(), 1));
    }

    public boolean supports(WorkOrder order) {
        return definition(order).isPresent();
    }

    private static Optional<CropDefinition> definition(WorkOrder order) {
        if (!"minecraft:farmer".equals(order.professionId())
                || !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORLD)
                || order.output().count() != 1) {
            return Optional.empty();
        }
        CropDefinition crop = CROPS_BY_ORDER.get(order.id());
        return crop != null
                && crop.targetTag().equals(order.worldTargetTag())
                && crop.harvestItemId().equals(order.output().itemId())
                ? Optional.of(crop)
                : Optional.empty();
    }

    private static boolean isMature(ServerLevel level, BlockPos cropPosition, CropDefinition crop) {
        if (!level.isLoaded(cropPosition)) {
            return false;
        }
        BlockState state = level.getBlockState(cropPosition);
        return state.is(crop.block()) && crop.block().isMaxAge(state);
    }

    private static List<ItemStack> retainReplantingInput(List<ItemStack> harvested, Item replantingItem) {
        List<ItemStack> stored = new ArrayList<>(harvested.stream().map(ItemStack::copy).toList());
        for (int index = 0; index < stored.size(); index++) {
            ItemStack stack = stored.get(index);
            if (!stack.is(replantingItem)) {
                continue;
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                stored.remove(index);
            }
            return List.copyOf(stored);
        }
        return null;
    }

    private static Optional<ItemStack> bestHoe(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream()
                .filter(stack -> !stack.isEmpty() && isHoe(stack))
                .map(stack -> stack.copyWithCount(1))
                .max(Comparator.comparingInt(FarmerWorldWorkAction::remainingDurability));
    }

    private static boolean isHoe(ItemStack stack) {
        return stack.is(Items.WOODEN_HOE) || stack.is(Items.STONE_HOE) || stack.is(Items.COPPER_HOE)
                || stack.is(Items.IRON_HOE) || stack.is(Items.GOLDEN_HOE) || stack.is(Items.DIAMOND_HOE)
                || stack.is(Items.NETHERITE_HOE);
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

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

    private static Map<String, CropDefinition> indexByOrder() {
        Map<String, CropDefinition> result = new LinkedHashMap<>();
        for (CropDefinition crop : CROPS) {
            result.put(crop.orderId(), crop);
        }
        return Map.copyOf(result);
    }

    private static Map<Block, CropDefinition> indexByBlock() {
        Map<Block, CropDefinition> result = new LinkedHashMap<>();
        for (CropDefinition crop : CROPS) {
            result.put(crop.block(), crop);
        }
        return Map.copyOf(result);
    }

    private static CropDefinition crop(String orderId, String targetTag, Block block, Item harvestItem, Item replantingItem) {
        return new CropDefinition(orderId, targetTag, (CropBlock) block, harvestItem, replantingItem,
                BuiltInRegistries.ITEM.getKey(harvestItem).toString());
    }

    private static boolean isFarmer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:farmer".equals(id.toString());
    }

    private record CropDefinition(String orderId, String targetTag, CropBlock block, Item harvestItem,
                                  Item replantingItem, String harvestItemId) {
    }
}
