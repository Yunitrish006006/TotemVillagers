package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.VillagerWorkSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Renewable Furnace trade linking Miners to Lumberjacks and Fishermen. A Miner
 * buys a physical eight-log batch, validates the world's current smelting
 * recipe, consumes one real fuel item and retains only a bounded charcoal
 * reserve for downstream buyers.
 */
public final class MinerCharcoalEconomyRuntime {
    private static final int PROCESS_INTERVAL_TICKS = 20;
    public static final int LOG_BATCH = 8;
    public static final int CHARCOAL_BATCH = 8;
    public static final int CHARCOAL_TARGET = 8;
    public static final int LOG_BATCH_PRICE = 2;
    private static final double SEARCH_RANGE_SQUARED = 32.0D * 32.0D;
    private static final double EXCHANGE_REACH_SQUARED = 4.0D * 4.0D;
    private static final double WORK_REACH_SQUARED = 4.0D * 4.0D;

    private MinerCharcoalEconomyRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PROCESS_INTERVAL_TICKS == 0) {
                process(server);
            }
        });
    }

    /** Returns the number of completed eight-charcoal batches for deterministic GameTests. */
    public static int tickForGameTest(MinecraftServer server) {
        return process(server);
    }

    private static int process(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return 0;
        }
        int completed = 0;
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Villager> villagers = level.getEntities(
                    EntityTypeTest.forClass(Villager.class), Villager::isAlive);
            for (Villager miner : villagers.stream()
                    .filter(villager -> isProfession(villager, "totem:miner")).toList()) {
                if (maintainCharcoal(level, miner, villagers, inventories)) {
                    completed++;
                }
            }
        }
        return completed;
    }

    private static boolean maintainCharcoal(ServerLevel level, Villager miner,
                                             List<? extends Villager> villagers,
                                             VillagerWorkInventorySavedData inventories) {
        if (!VillagerWorkNeeds.canWork(miner)
                || VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(miner.getUUID())
                .activeWork().isPresent()) {
            return false;
        }
        VillagerWorkInventory inventory = inventories.inventory(miner.getUUID());
        if (count(inventory, Items.CHARCOAL) >= CHARCOAL_TARGET) {
            return false;
        }
        BlockPos furnace = jobSite(level, miner).orElse(null);
        if (furnace == null) {
            return false;
        }
        if (miner.distanceToSqr(Vec3.atCenterOf(furnace)) > WORK_REACH_SQUARED) {
            miner.getNavigation().moveTo(furnace.getX() + .5D, furnace.getY(), furnace.getZ() + .5D, .5D);
            return false;
        }
        MinerFurnaceMaintenanceSavedData maintenance = MinerFurnaceMaintenanceSavedData.forServer(level.getServer());
        if (maintenance.requiresReplacement(miner.getUUID())) {
            if (replaceFurnaceFromLiveRecipe(level, inventory)) {
                maintenance.recordReplacement(miner.getUUID());
                miner.playWorkSound();
            }
            return false;
        }
        ItemStack logs = processableLogs(inventory).orElse(null);
        if (logs == null) {
            buyMissingLogs(miner, villagers, inventories, inventory);
            return false;
        }
        Item fuel = furnaceFuel(inventory);
        if (fuel == null) {
            return false;
        }
        if (smeltCharcoal(level, inventory, logs, fuel)) {
            maintenance.recordBatch(miner.getUUID());
            miner.playWorkSound();
            return true;
        }
        return false;
    }

    /** Crafts and installs one exact live-recipe Furnace, consuming the eight physical cobblestone inputs. */
    private static boolean replaceFurnaceFromLiveRecipe(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack cobblestone = new ItemStack(Items.COBBLESTONE);
        CraftingInput input = CraftingInput.of(3, 3, List.of(
                cobblestone, cobblestone.copy(), cobblestone.copy(),
                cobblestone.copy(), ItemStack.EMPTY, cobblestone.copy(),
                cobblestone.copy(), cobblestone.copy(), cobblestone.copy()));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!output.is(Items.FURNACE) || output.getCount() != 1) {
            return false;
        }
        var reservation = inventory.reserveExactMatchingItem(
                new ItemStack(Items.COBBLESTONE, 8)).orElse(null);
        if (reservation == null) {
            return false;
        }
        reservation.commit();
        return true;
    }

    private static Optional<ItemStack> processableLogs(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream()
                .filter(stack -> stack.is(ItemTags.LOGS) && stack.getCount() >= LOG_BATCH)
                .findFirst().map(stack -> stack.copyWithCount(LOG_BATCH));
    }

    private static boolean buyMissingLogs(Villager miner, List<? extends Villager> villagers,
                                          VillagerWorkInventorySavedData inventories,
                                          VillagerWorkInventory buyer) {
        Optional<LogPurchase> supplier = villagers.stream().filter(candidate -> candidate != miner)
                .filter(candidate -> isProfession(candidate, "totem:lumberjack"))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> miner.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .map(candidate -> bestPurchase(candidate, inventories.inventory(candidate.getUUID()), buyer))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(entry -> miner.distanceToSqr(entry.seller())));
        if (supplier.isEmpty()) {
            return false;
        }
        LogPurchase selected = supplier.orElseThrow();
        if (miner.distanceToSqr(selected.seller()) > EXCHANGE_REACH_SQUARED) {
            miner.getNavigation().moveTo(selected.seller(), .5D);
            return false;
        }
        return exchange(buyer, inventories.inventory(selected.seller().getUUID()),
                new ItemStack(Items.EMERALD, selected.price()), selected.logs());
    }

    private static Optional<LogPurchase> bestPurchase(Villager seller, VillagerWorkInventory sellerInventory,
                                                       VillagerWorkInventory buyerInventory) {
        return sellerInventory.snapshot().stream().filter(stack -> stack.is(ItemTags.LOGS))
                .map(stack -> {
                    int owned = buyerInventory.snapshot().stream()
                            .filter(held -> ItemStack.isSameItemSameComponents(held, stack))
                            .mapToInt(ItemStack::getCount).sum();
                    int missing = Math.max(0, LOG_BATCH - owned);
                    int price = Math.max(1, (LOG_BATCH_PRICE * missing + LOG_BATCH - 1) / LOG_BATCH);
                    return new LogPurchase(seller, stack.copyWithCount(missing), price);
                })
                .filter(purchase -> !purchase.logs().isEmpty()
                        && sellerInventory.countMatchingItem(purchase.logs()) >= purchase.logs().getCount())
                .max(Comparator.comparingInt(purchase -> purchase.logs().getCount()));
    }

    private static boolean smeltCharcoal(ServerLevel level, VillagerWorkInventory inventory,
                                         ItemStack logs, Item fuel) {
        SingleRecipeInput input = new SingleRecipeInput(logs.copyWithCount(1));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!output.is(Items.CHARCOAL) || output.getCount() != 1) {
            return false;
        }
        var reservation = inventory.reserveExactMatching(List.of(
                logs.copyWithCount(LOG_BATCH), new ItemStack(fuel))).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(output.copyWithCount(CHARCOAL_BATCH))) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    /** Charcoal is renewable; the last founding coal is reserved for the first charcoal batch. */
    private static Item furnaceFuel(VillagerWorkInventory inventory) {
        if (count(inventory, Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        return count(inventory, Items.COAL) > 0 ? Items.COAL : null;
    }

    private static boolean exchange(VillagerWorkInventory buyer, VillagerWorkInventory seller,
                                    ItemStack payment, ItemStack merchandise) {
        if (buyer.countMatchingItem(payment) < payment.getCount()
                || seller.countMatchingItem(merchandise) < merchandise.getCount()
                || !buyer.canInsertExact(merchandise) || !seller.canInsertExact(payment)
                || buyer.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = seller.takeExactMatchingItem(merchandise).orElse(null);
        if (delivered == null) {
            buyer.insertExact(payment);
            return false;
        }
        if (!buyer.insertExact(delivered)) {
            seller.insertExact(delivered);
            buyer.insertExact(payment);
            return false;
        }
        if (!seller.insertExact(payment)) {
            throw new IllegalStateException("Miner log payment capacity changed during commit");
        }
        return true;
    }

    private static Optional<BlockPos> jobSite(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension())).map(GlobalPos::pos)
                .filter(level::isLoaded).filter(position -> level.getBlockState(position).is(Blocks.FURNACE));
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static boolean isProfession(Villager villager, String expected) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && expected.equals(id.toString());
    }

    private record LogPurchase(Villager seller, ItemStack logs, int price) {
    }
}
