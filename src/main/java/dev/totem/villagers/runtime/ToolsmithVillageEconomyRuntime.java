package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.world.ToolsmithFiberWorldWorkAction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Physical village supply chain for replacement resource tools. A Toolsmith buys real wood and mineral batches from
 * the two resource specialists, processes only live player recipes, forges a real hoe, pickaxe or axe at its Smithing
 * Table and sells that exact tool to the Farmer, Miner or Lumberjack that needs it.
 */
public final class ToolsmithVillageEconomyRuntime {
    private static final int PROCESS_INTERVAL_TICKS = 20;
    private static final double SEARCH_RANGE_SQUARED = 32.0D * 32.0D;
    private static final double EXCHANGE_REACH_SQUARED = 4.0D * 4.0D;
    private static final double WORK_REACH_SQUARED = 4.0D * 4.0D;
    private static final int WOOD_BATCH = 1;
    private static final int STICK_BATCH = 2;
    private static final int IRON_SMELT_BATCH = 4;
    private static final int COPPER_SMELT_BATCH = 4;
    private static final int MIN_IRON_SMELT_BATCH = 2;
    private static final int MIN_COPPER_SMELT_BATCH = 3;
    /** One log yields eight live-recipe sticks, enough handles for two ordinary three-stick tools. */
    private static final int WOOD_PRICE = 2;
    private static final int STICK_PRICE = 1;
    private static final int FISHING_ROD_PRICE = 3;
    private static final int FISHING_ROD_PREORDER_DURABILITY = 32;
    private static final int STRING_BATCH = 2;
    private static final int FIBRES_PER_STRING = 3;
    private static final int SHEARS_IRON_PRICE = 2;
    private static final int MINERAL_RESERVE_BATCH = 3;
    private static final int MINERAL_RESERVE_TARGET = 6;
    private static final int MINERAL_RESERVE_PRICE = 4;
    private static final int TOOLSMITH_CASH_RESERVE = 6;
    private static final int FIBRE_SEARCH_RADIUS = 12;
    private static final int FIBRE_SEARCH_VERTICAL = 4;
    private static final TagKey<Block> FIBRE_PLANTS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "toolsmith_fiber_plants"));
    private static final TagKey<Item> PLANT_FIBRES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("totem", "plant_fibers"));
    private static final ToolsmithFiberWorldWorkAction FIBRE_HARVEST = new ToolsmithFiberWorldWorkAction();
    private static final WorkshopCommitService COMMITS = new WorkshopCommitService();

    private ToolsmithVillageEconomyRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PROCESS_INTERVAL_TICKS == 0) {
                process(server);
            }
        });
    }

    /** Public only for deterministic GameTests; production runs the same bounded pass once per second. */
    public static void tickForGameTest(MinecraftServer server) {
        process(server);
    }

    private static void process(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            villagers.stream().filter(villager -> isProfession(villager, "totem:miner"))
                    .forEach(miner -> smeltMetals(level, miner, inventories.inventory(miner.getUUID())));
            villagers.stream().filter(villager -> isProfession(villager, "minecraft:toolsmith"))
                    .forEach(toolsmith -> serveResourceWorker(level, toolsmith, villagers, inventories));
        }
    }

    private static void smeltMetals(ServerLevel level, Villager miner, VillagerWorkInventory inventory) {
        if (!VillagerWorkNeeds.canWork(miner)
                || VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(miner.getUUID()).activeWork().isPresent()
                || smeltingFuel(inventory) == null) {
            return;
        }
        BlockPos furnace = jobSite(level, miner, Blocks.FURNACE).orElse(null);
        if (furnace == null) {
            return;
        }
        if (miner.distanceToSqr(Vec3.atCenterOf(furnace)) > WORK_REACH_SQUARED) {
            miner.getNavigation().moveTo(furnace.getX() + .5D, furnace.getY(), furnace.getZ() + .5D, .5D);
            return;
        }
        int ironBatch = availableSmeltingBatch(inventory, Items.RAW_IRON,
                IRON_SMELT_BATCH, MIN_IRON_SMELT_BATCH);
        int copperBatch = availableSmeltingBatch(inventory, Items.RAW_COPPER,
                COPPER_SMELT_BATCH, MIN_COPPER_SMELT_BATCH);
        if ((ironBatch > 0 && smeltBatch(level, inventory, Items.RAW_IRON, Items.IRON_INGOT, ironBatch))
                || (copperBatch > 0
                && smeltBatch(level, inventory, Items.RAW_COPPER, Items.COPPER_INGOT, copperBatch))) {
            miner.playWorkSound();
        }
    }

    /** Keeps efficient four-item batches, but releases exactly enough accumulated ore for shears or a three-part tool. */
    private static int availableSmeltingBatch(VillagerWorkInventory inventory, Item raw,
                                               int preferredBatch, int minimumUsefulBatch) {
        int available = count(inventory, raw);
        return available >= preferredBatch ? preferredBatch
                : available >= minimumUsefulBatch ? available : 0;
    }

    private static boolean smeltBatch(ServerLevel level, VillagerWorkInventory inventory, Item raw, Item ingot,
                                      int batchSize) {
        Item fuel = smeltingFuel(inventory);
        if (count(inventory, raw) < batchSize || fuel == null) {
            return false;
        }
        SingleRecipeInput input = new SingleRecipeInput(new ItemStack(raw));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack one = recipe.value().assemble(input);
        if (!one.is(ingot) || one.getCount() != 1) {
            return false;
        }
        Identifier rawId = BuiltInRegistries.ITEM.getKey(raw);
        if (rawId == null) {
            return false;
        }
        Identifier fuelId = BuiltInRegistries.ITEM.getKey(fuel);
        if (fuelId == null) {
            return false;
        }
        var reservation = inventory.reserveExact(List.of(
                new ItemAmount(rawId.toString(), batchSize), new ItemAmount(fuelId.toString(), 1))).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(one.copyWithCount(batchSize))) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    /** Uses renewable charcoal first while retaining vanilla coal compatibility for isolated smelting jobs. */
    private static Item smeltingFuel(VillagerWorkInventory inventory) {
        if (count(inventory, Items.CHARCOAL) > 0) {
            return Items.CHARCOAL;
        }
        return count(inventory, Items.COAL) > 0 ? Items.COAL : null;
    }

    private static void serveResourceWorker(ServerLevel level, Villager toolsmith, List<? extends Villager> villagers,
                                            VillagerWorkInventorySavedData inventories) {
        if (!VillagerWorkNeeds.canWork(toolsmith)
                || VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(toolsmith.getUUID()).activeWork().isPresent()) {
            return;
        }
        BlockPos smithingTable = jobSite(level, toolsmith, Blocks.SMITHING_TABLE).orElse(null);
        if (smithingTable == null) {
            return;
        }
        if (serveFisherman(level, toolsmith, smithingTable, villagers, inventories)) {
            return;
        }
        ToolRequest request = nextRequest(toolsmith, villagers, inventories).orElse(null);
        if (request == null) {
            maintainToolMineralReserve(toolsmith, villagers, inventories);
            return;
        }
        VillagerWorkInventory inventory = inventories.inventory(toolsmith.getUUID());
        if (sellTool(toolsmith, request.worker(), inventory,
                inventories.inventory(request.worker().getUUID()), request.tool(), villagers, inventories)) {
            return;
        }
        if (count(inventory, Items.STICK) < 2) {
            boolean hasProcessableWood = inventory.snapshot().stream()
                    .anyMatch(stack -> stack.is(ItemTags.PLANKS) && stack.getCount() >= 2 || stack.is(ItemTags.LOGS));
            if (hasProcessableWood && !atStationOrNavigate(toolsmith, smithingTable)) {
                return;
            }
            if (hasProcessableWood && (craftSticks(level, inventory) || craftPlanks(level, inventory))) {
                toolsmith.playWorkSound();
                return;
            }
            if (!buyFromProfessionWithSharedPayment(toolsmith, "totem:lumberjack",
                    new ItemStack(Items.STICK, STICK_BATCH),
                    STICK_BATCH, STICK_PRICE, villagers, inventories)) {
                buyLogs(toolsmith, villagers, inventories);
            }
            return;
        }
        if (count(inventory, Items.IRON_INGOT) >= request.tool().materialCount()) {
            if (atStationOrNavigate(toolsmith, smithingTable)) {
                forge(level, toolsmith, smithingTable, inventory, request.tool().ironOrderId());
            }
            return;
        }
        if (buyFromProfessionWithSharedPayment(toolsmith, "totem:miner",
                new ItemStack(Items.IRON_INGOT, request.tool().materialCount()),
                request.tool().materialCount(), request.tool().ironMaterialPrice(), villagers, inventories)) {
            return;
        }
        if (count(inventory, Items.COPPER_INGOT) >= request.tool().materialCount()) {
            if (atStationOrNavigate(toolsmith, smithingTable)) {
                forge(level, toolsmith, smithingTable, inventory, request.tool().copperOrderId());
            }
            return;
        }
        if (buyFromProfessionWithSharedPayment(toolsmith, "totem:miner",
                new ItemStack(Items.COPPER_INGOT, request.tool().materialCount()),
                request.tool().materialCount(), request.tool().copperMaterialPrice(), villagers, inventories)) {
            return;
        }
        if (count(inventory, Items.COBBLESTONE) >= request.tool().materialCount()) {
            if (atStationOrNavigate(toolsmith, smithingTable)) {
                forge(level, toolsmith, smithingTable, inventory, request.tool().stoneOrderId());
            }
            return;
        }
        buyFromProfessionWithSharedPayment(toolsmith, "totem:miner",
                new ItemStack(Items.COBBLESTONE, request.tool().materialCount()),
                request.tool().materialCount(), request.tool().stoneMaterialPrice(), villagers, inventories);
    }

    /**
     * Turns mined metal into regular income before a tool actually breaks. The Toolsmith holds at most two
     * three-ingot tool batches and never spends its six-emerald operating buffer, so reserve buying cannot starve
     * fishing-rod wood/string purchases. Iron is preferred because the Y=56 simulations found copper stockpiling at
     * roughly five times the effective iron yield; copper remains the fallback whenever a full iron batch is absent.
     */
    private static boolean maintainToolMineralReserve(Villager toolsmith, List<? extends Villager> villagers,
                                                      VillagerWorkInventorySavedData inventories) {
        VillagerWorkInventory inventory = inventories.inventory(toolsmith.getUUID());
        int storedToolMetal = count(inventory, Items.IRON_INGOT) + count(inventory, Items.COPPER_INGOT);
        if (storedToolMetal + MINERAL_RESERVE_BATCH > MINERAL_RESERVE_TARGET
                || count(inventory, Items.EMERALD) < TOOLSMITH_CASH_RESERVE + MINERAL_RESERVE_PRICE) {
            return false;
        }
        return buyFromProfession(toolsmith, "totem:miner",
                new ItemStack(Items.IRON_INGOT, MINERAL_RESERVE_BATCH), MINERAL_RESERVE_BATCH,
                MINERAL_RESERVE_PRICE, villagers, inventories)
                || buyFromProfession(toolsmith, "totem:miner",
                new ItemStack(Items.COPPER_INGOT, MINERAL_RESERVE_BATCH), MINERAL_RESERVE_BATCH,
                MINERAL_RESERVE_PRICE, villagers, inventories);
    }

    /**
     * Supplies the Fisherman from physical stock. If no rod is ready, the Toolsmith must obtain the exact live-recipe
     * inputs and craft one at its Smithing Table before any emerald exchange can occur.
     */
    private static boolean serveFisherman(ServerLevel level, Villager toolsmith, BlockPos smithingTable,
                                           List<? extends Villager> villagers,
                                           VillagerWorkInventorySavedData inventories) {
        Villager fisherman = villagers.stream()
                .filter(candidate -> isProfession(candidate, "minecraft:fisherman"))
                .filter(candidate -> toolsmith.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> needsFishingRodOrder(inventories.inventory(candidate.getUUID())))
                .min(Comparator.comparingDouble(toolsmith::distanceToSqr)).orElse(null);
        if (fisherman == null) {
            return false;
        }
        VillagerWorkInventory toolsmithInventory = inventories.inventory(toolsmith.getUUID());
        VillagerWorkInventory fishermanInventory = inventories.inventory(fisherman.getUUID());
        ItemStack merchandise = toolsmithInventory.snapshot().stream()
                .filter(stack -> stack.is(Items.FISHING_ROD) && !stack.isDamaged())
                .findFirst().map(stack -> stack.copyWithCount(1)).orElse(null);
        if (merchandise != null) {
            if (count(fishermanInventory, Items.EMERALD) < FISHING_ROD_PRICE) {
                financeFishermanRodWithFood(level, fisherman, villagers, inventories);
            }
            if (toolsmith.distanceToSqr(fisherman) > EXCHANGE_REACH_SQUARED) {
                fisherman.getNavigation().moveTo(toolsmith, .5D);
                return true;
            }
            ItemStack rodPayment = new ItemStack(Items.EMERALD, FISHING_ROD_PRICE);
            if (exchange(fishermanInventory, toolsmithInventory, rodPayment, merchandise)
                    || sponsoredFinishedToolExchange(fisherman, toolsmith, villagers, inventories,
                    rodPayment, merchandise)) {
                refreshOffers(toolsmith);
                fisherman.playWorkSound();
            }
            return true;
        }
        if (count(toolsmithInventory, Items.STICK) < 3) {
            boolean hasProcessableWood = toolsmithInventory.snapshot().stream()
                    .anyMatch(stack -> stack.is(ItemTags.PLANKS) && stack.getCount() >= 2 || stack.is(ItemTags.LOGS));
            if (hasProcessableWood && !atStationOrNavigate(toolsmith, smithingTable)) {
                return true;
            }
            if (hasProcessableWood && (craftSticks(level, toolsmithInventory) || craftPlanks(level, toolsmithInventory))) {
                toolsmith.playWorkSound();
                return true;
            }
            return buyFromProfession(toolsmith, "totem:lumberjack", new ItemStack(Items.STICK, STICK_BATCH),
                    STICK_BATCH, STICK_PRICE, villagers, inventories)
                    || sponsoredBuyFromProfession(fisherman, toolsmith, "totem:lumberjack",
                    new ItemStack(Items.STICK, STICK_BATCH), STICK_BATCH, STICK_PRICE,
                    FISHING_ROD_PRICE, villagers, inventories)
                    || communitySponsorBuyFromProfession(toolsmith, "totem:lumberjack",
                    new ItemStack(Items.STICK, STICK_BATCH), STICK_BATCH, STICK_PRICE, villagers, inventories)
                    || buyLogs(toolsmith, villagers, inventories)
                    || sponsoredBuyLogs(fisherman, toolsmith, FISHING_ROD_PRICE, villagers, inventories)
                    || communitySponsorBuyLogs(toolsmith, villagers, inventories);
        }
        if (count(toolsmithInventory, Items.STRING) < STRING_BATCH) {
            return obtainRenewableString(level, toolsmith, smithingTable, villagers, inventories,
                    toolsmithInventory);
        }
        if (!atStationOrNavigate(toolsmith, smithingTable)) {
            return true;
        }
        return forge(level, toolsmith, smithingTable, toolsmithInventory, "totem:toolsmith_fishing_rod");
    }

    private static boolean needsFishingRodOrder(VillagerWorkInventory inventory) {
        List<ItemStack> stock = inventory.snapshot();
        long usable = FishingRodUse.usableCount(stock);
        if (usable < 1) {
            return true;
        }
        return usable == 1 && FishingRodUse.nextForWork(stock)
                .map(rod -> FishingRodUse.remainingDurability(rod) <= FISHING_ROD_PREORDER_DURABILITY)
                .orElse(false);
    }

    /** Converts bounded physical catch stock into the missing working capital before the full-price rod exchange. */
    private static void financeFishermanRodWithFood(ServerLevel level, Villager fisherman,
                                                    List<? extends Villager> villagers,
                                                    VillagerWorkInventorySavedData inventories) {
        VillagerWorkInventory fishermanInventory = inventories.inventory(fisherman.getUUID());
        List<? extends Villager> buyers = villagers.stream().filter(candidate -> candidate != fisherman)
                .filter(candidate -> !candidate.isBaby() && candidate.isAlive())
                .sorted(Comparator.comparingInt(candidate -> storedNutrition(
                        inventories.inventory(candidate.getUUID()))))
                .toList();
        for (Villager buyer : buyers) {
            if (count(fishermanInventory, Items.EMERALD) >= FISHING_ROD_PRICE) {
                return;
            }
            VillagerFoodEconomyRuntime.tryPurchaseWorkOrderFoodReserve(level, buyer, fisherman);
        }
    }

    /**
     * Builds string only from plants that were physically cut with shears. Both the fibre recipe and any replacement
     * shears are resolved through the world's current player crafting recipes, so a data pack can alter or disable
     * either path without villagers retaining a hidden recipe.
     */
    private static boolean obtainRenewableString(ServerLevel level, Villager toolsmith, BlockPos smithingTable,
                                                 List<? extends Villager> villagers,
                                                 VillagerWorkInventorySavedData inventories,
                                                 VillagerWorkInventory inventory) {
        if (buyMissingString(toolsmith, inventory, villagers, inventories)) {
            return true;
        }
        List<ItemStack> fibreInputs = plantFibreInputs(inventory);
        if (fibreInputs.size() == FIBRES_PER_STRING) {
            if (!atStationOrNavigate(toolsmith, smithingTable)) {
                return true;
            }
            if (craftPlantFibreString(level, inventory, fibreInputs)) {
                toolsmith.playWorkSound();
                return true;
            }
            // A full batch that no currently loaded player recipe accepts must not trigger further plant clearing.
            return buyMissingString(toolsmith, inventory, villagers, inventories);
        }
        if (!hasUsableShears(inventory)) {
            if (count(inventory, Items.IRON_INGOT) >= 2) {
                if (!atStationOrNavigate(toolsmith, smithingTable)) {
                    return true;
                }
                if (craftShears(level, inventory)) {
                    toolsmith.playWorkSound();
                    return true;
                }
            } else if (buyFromProfession(toolsmith, "totem:miner", new ItemStack(Items.IRON_INGOT, 2),
                    2, SHEARS_IRON_PRICE, villagers, inventories)
                    || communitySponsorBuyFromProfession(toolsmith, "totem:miner",
                    new ItemStack(Items.IRON_INGOT, 2), 2, SHEARS_IRON_PRICE, villagers, inventories)) {
                return true;
            }
        } else {
            BlockPos target = nearestFibrePlant(level, toolsmith, smithingTable).orElse(null);
            if (target != null) {
                if (toolsmith.distanceToSqr(Vec3.atCenterOf(target)) > WORK_REACH_SQUARED) {
                    toolsmith.getNavigation().moveTo(target.getX() + .5D, target.getY(), target.getZ() + .5D, .5D);
                    return true;
                }
                if (FIBRE_HARVEST.complete(level, toolsmith, target, FIBRE_PLANTS, PLANT_FIBRES, inventory)) {
                    return true;
                }
            }
        }
        return buyMissingString(toolsmith, inventory, villagers, inventories);
    }

    /** Buys only the exact remaining string needed for one rod instead of requiring a wasteful fixed two-item lot. */
    private static boolean buyMissingString(Villager toolsmith, VillagerWorkInventory inventory,
                                            List<? extends Villager> villagers,
                                            VillagerWorkInventorySavedData inventories) {
        int missing = Math.max(0, STRING_BATCH - count(inventory, Items.STRING));
        if (missing < 1) {
            return false;
        }
        ItemStack string = new ItemStack(Items.STRING, missing);
        return buyFromProfession(toolsmith, "minecraft:fisherman", string, missing, 1, villagers, inventories)
                || communitySponsorBuyFromProfession(toolsmith, "minecraft:fisherman",
                string, missing, 1, villagers, inventories);
    }

    private static Optional<ToolRequest> nextRequest(Villager toolsmith, List<? extends Villager> villagers,
                                                     VillagerWorkInventorySavedData inventories) {
        for (ReplacementTool tool : ReplacementTool.values()) {
            Villager worker = villagers.stream()
                    .filter(candidate -> isProfession(candidate, tool.professionId()))
                    .filter(candidate -> toolsmith.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                    .filter(candidate -> !tool.hasUsableTool(inventories.inventory(candidate.getUUID())))
                    .min(Comparator.comparingDouble(toolsmith::distanceToSqr)).orElse(null);
            if (worker != null) {
                return Optional.of(new ToolRequest(worker, tool));
            }
        }
        return Optional.empty();
    }

    private static boolean forge(ServerLevel level, Villager toolsmith, BlockPos station,
                                 VillagerWorkInventory inventory, String orderId) {
        WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
        RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(level, toolsmith, station, order);
        WorkshopCommitResult result = COMMITS.completePhysical(inventory, order, action, completed -> {
            Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(completed.output().itemId()));
            return item == null ? ItemStack.EMPTY : new ItemStack(item, completed.output().count());
        });
        if (result == WorkshopCommitResult.COMPLETED) {
            refreshOffers(toolsmith);
            return true;
        }
        return false;
    }

    private static boolean sellTool(Villager toolsmith, Villager worker,
                                    VillagerWorkInventory seller, VillagerWorkInventory buyer,
                                    ReplacementTool tool, List<? extends Villager> villagers,
                                    VillagerWorkInventorySavedData inventories) {
        ItemStack merchandise = seller.snapshot().stream()
                .filter(stack -> stack.is(tool.ironItem()) || stack.is(tool.copperItem()) || stack.is(tool.stoneItem()))
                .filter(stack -> !stack.isDamaged())
                // The Toolsmith's own founding iron pickaxe is work/render equipment, not merchant stock.
                .filter(stack -> !stack.is(Items.IRON_PICKAXE)
                        || seller.countMatchingItem(stack.copyWithCount(1)) > 1)
                .map(stack -> stack.copyWithCount(1))
                .max(Comparator.comparingInt(stack -> stack.is(tool.ironItem()) ? 2
                        : stack.is(tool.copperItem()) ? 1 : 0)).orElse(null);
        if (merchandise == null) {
            return false;
        }
        int price = merchandise.is(tool.ironItem()) ? tool.ironPrice()
                : merchandise.is(tool.copperItem()) ? tool.copperPrice() : tool.stonePrice();
        if (toolsmith.distanceToSqr(worker) > EXCHANGE_REACH_SQUARED) {
            worker.getNavigation().moveTo(toolsmith, .5D);
            return true;
        }
        ItemStack payment = new ItemStack(Items.EMERALD, price);
        if (exchange(buyer, seller, payment, merchandise)
                || sponsoredFinishedToolExchange(worker, toolsmith, villagers, inventories,
                payment, merchandise)) {
            refreshOffers(toolsmith);
            worker.playWorkSound();
        }
        return true;
    }

    private static boolean buyFromProfession(Villager buyer, String sellerProfession, ItemStack material,
                                             int count, int price, List<? extends Villager> villagers,
                                             VillagerWorkInventorySavedData inventories) {
        ItemStack batch = material.copyWithCount(count);
        Villager seller = villagers.stream().filter(candidate -> candidate != buyer)
                .filter(candidate -> isProfession(candidate, sellerProfession))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> buyer.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> inventories.inventory(candidate.getUUID()).countMatchingItem(batch) >= count)
                .min(Comparator.comparingDouble(buyer::distanceToSqr)).orElse(null);
        if (seller == null) {
            return false;
        }
        if (buyer.distanceToSqr(seller) > EXCHANGE_REACH_SQUARED) {
            buyer.getNavigation().moveTo(seller, .5D);
            return true;
        }
        return exchange(inventories.inventory(buyer.getUUID()), inventories.inventory(seller.getUUID()),
                new ItemStack(Items.EMERALD, price), batch);
    }

    /**
     * Essential replacement tools may combine the Toolsmith's cash with one nearby worker's cash when buying a full
     * material batch. The Miner still receives the complete listed price and the exact batch still goes to the
     * Toolsmith; this only prevents fragmented village liquidity from stopping an otherwise funded work order.
     */
    private static boolean buyFromProfessionWithSharedPayment(Villager buyer, String sellerProfession,
                                                               ItemStack material, int count, int price,
                                                               List<? extends Villager> villagers,
                                                               VillagerWorkInventorySavedData inventories) {
        ItemStack batch = material.copyWithCount(count);
        Villager seller = villagers.stream().filter(candidate -> candidate != buyer)
                .filter(candidate -> isProfession(candidate, sellerProfession))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> buyer.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> inventories.inventory(candidate.getUUID()).countMatchingItem(batch) >= count)
                .min(Comparator.comparingDouble(buyer::distanceToSqr)).orElse(null);
        if (seller == null) {
            return false;
        }
        if (buyer.distanceToSqr(seller) > EXCHANGE_REACH_SQUARED) {
            buyer.getNavigation().moveTo(seller, .5D);
            return true;
        }
        ItemStack payment = new ItemStack(Items.EMERALD, price);
        return exchange(inventories.inventory(buyer.getUUID()), inventories.inventory(seller.getUUID()),
                payment, batch)
                || sponsoredFinishedToolExchange(buyer, seller, villagers, inventories, payment, batch);
    }

    /**
     * A customer with a live replacement request may fund raw material directly when the Toolsmith lacks working
     * capital. The merchandise goes to the Toolsmith, the emerald goes to the resource worker, and enough customer
     * cash is reserved for the completed tool payment. This is a three-party physical exchange, never a loan or mint.
     */
    private static boolean sponsoredBuyFromProfession(Villager payer, Villager recipient, String sellerProfession,
                                                       ItemStack material, int count, int price, int finalPaymentReserve,
                                                       List<? extends Villager> villagers,
                                                       VillagerWorkInventorySavedData inventories) {
        ItemStack batch = material.copyWithCount(count);
        Villager seller = villagers.stream().filter(candidate -> candidate != payer && candidate != recipient)
                .filter(candidate -> isProfession(candidate, sellerProfession))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> recipient.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .filter(candidate -> inventories.inventory(candidate.getUUID()).countMatchingItem(batch) >= count)
                .min(Comparator.comparingDouble(recipient::distanceToSqr)).orElse(null);
        if (seller == null || count(inventories.inventory(payer.getUUID()), Items.EMERALD)
                < price + finalPaymentReserve) {
            return false;
        }
        if (payer.distanceToSqr(seller) > SEARCH_RANGE_SQUARED) {
            return false;
        }
        if (recipient.distanceToSqr(seller) > EXCHANGE_REACH_SQUARED) {
            recipient.getNavigation().moveTo(seller, .5D);
            return true;
        }
        if (payer.distanceToSqr(seller) > EXCHANGE_REACH_SQUARED) {
            payer.getNavigation().moveTo(seller, .5D);
            return true;
        }
        return sponsoredExchange(inventories.inventory(payer.getUUID()), inventories.inventory(recipient.getUUID()),
                inventories.inventory(seller.getUUID()), new ItemStack(Items.EMERALD, price), batch);
    }

    private static boolean buyLogs(Villager buyer, List<? extends Villager> villagers,
                                   VillagerWorkInventorySavedData inventories) {
        Optional<LogSupplier> supplier = villagers.stream().filter(candidate -> candidate != buyer)
                .filter(candidate -> isProfession(candidate, "totem:lumberjack"))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> buyer.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .map(candidate -> inventories.inventory(candidate.getUUID()).snapshot().stream()
                        .filter(stack -> stack.is(ItemTags.LOGS) && stack.getCount() >= WOOD_BATCH)
                        .findFirst().map(stack -> new LogSupplier(candidate, stack.copyWithCount(WOOD_BATCH))))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(entry -> buyer.distanceToSqr(entry.villager())));
        if (supplier.isEmpty()) {
            return false;
        }
        LogSupplier selected = supplier.orElseThrow();
        if (buyer.distanceToSqr(selected.villager()) > EXCHANGE_REACH_SQUARED) {
            buyer.getNavigation().moveTo(selected.villager(), .5D);
            return true;
        }
        ItemStack payment = new ItemStack(Items.EMERALD, WOOD_PRICE);
        return exchange(inventories.inventory(buyer.getUUID()), inventories.inventory(selected.villager().getUUID()),
                payment, selected.logs())
                || sponsoredFinishedToolExchange(buyer, selected.villager(), villagers, inventories,
                payment, selected.logs());
    }

    private static boolean sponsoredBuyLogs(Villager payer, Villager recipient, int finalPaymentReserve,
                                            List<? extends Villager> villagers,
                                            VillagerWorkInventorySavedData inventories) {
        Optional<LogSupplier> supplier = villagers.stream().filter(candidate -> candidate != payer && candidate != recipient)
                .filter(candidate -> isProfession(candidate, "totem:lumberjack"))
                .filter(VillagerWorkNeeds::canWork)
                .filter(candidate -> recipient.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .map(candidate -> inventories.inventory(candidate.getUUID()).snapshot().stream()
                        .filter(stack -> stack.is(ItemTags.LOGS) && stack.getCount() >= WOOD_BATCH)
                        .findFirst().map(stack -> new LogSupplier(candidate, stack.copyWithCount(WOOD_BATCH))))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(entry -> recipient.distanceToSqr(entry.villager())));
        if (supplier.isEmpty() || count(inventories.inventory(payer.getUUID()), Items.EMERALD)
                < WOOD_PRICE + finalPaymentReserve) {
            return false;
        }
        LogSupplier selected = supplier.orElseThrow();
        if (payer.distanceToSqr(selected.villager()) > SEARCH_RANGE_SQUARED) {
            return false;
        }
        if (recipient.distanceToSqr(selected.villager()) > EXCHANGE_REACH_SQUARED) {
            recipient.getNavigation().moveTo(selected.villager(), .5D);
            return true;
        }
        if (payer.distanceToSqr(selected.villager()) > EXCHANGE_REACH_SQUARED) {
            payer.getNavigation().moveTo(selected.villager(), .5D);
            return true;
        }
        return sponsoredExchange(inventories.inventory(payer.getUUID()), inventories.inventory(recipient.getUUID()),
                inventories.inventory(selected.villager().getUUID()), new ItemStack(Items.EMERALD, WOOD_PRICE),
                selected.logs());
    }

    private static boolean communitySponsorBuyFromProfession(Villager recipient, String sellerProfession,
                                                               ItemStack material, int materialCount, int price,
                                                               List<? extends Villager> villagers,
                                                               VillagerWorkInventorySavedData inventories) {
        for (Villager sponsor : villagers.stream().filter(candidate -> candidate != recipient)
                .filter(candidate -> !isProfession(candidate, sellerProfession))
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby())
                .sorted(Comparator.comparingInt((Villager candidate) -> count(
                        inventories.inventory(candidate.getUUID()), Items.EMERALD)).reversed()).toList()) {
            if (sponsoredBuyFromProfession(sponsor, recipient, sellerProfession, material, materialCount, price, 0,
                    villagers, inventories)) {
                return true;
            }
        }
        return false;
    }

    private static boolean communitySponsorBuyLogs(Villager recipient, List<? extends Villager> villagers,
                                                    VillagerWorkInventorySavedData inventories) {
        for (Villager sponsor : villagers.stream().filter(candidate -> candidate != recipient)
                .filter(candidate -> !isProfession(candidate, "totem:lumberjack"))
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby())
                .sorted(Comparator.comparingInt((Villager candidate) -> count(
                        inventories.inventory(candidate.getUUID()), Items.EMERALD)).reversed()).toList()) {
            if (sponsoredBuyLogs(sponsor, recipient, 0, villagers, inventories)) {
                return true;
            }
        }
        return false;
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
            throw new IllegalStateException("Physical village exchange payment capacity changed during commit");
        }
        return true;
    }

    private static boolean sponsoredExchange(VillagerWorkInventory payer, VillagerWorkInventory recipient,
                                             VillagerWorkInventory seller, ItemStack payment,
                                             ItemStack merchandise) {
        if (payer.countMatchingItem(payment) < payment.getCount()
                || seller.countMatchingItem(merchandise) < merchandise.getCount()
                || !recipient.canInsertExact(merchandise) || !seller.canInsertExact(payment)
                || payer.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = seller.takeExactMatchingItem(merchandise).orElse(null);
        if (delivered == null) {
            payer.insertExact(payment);
            return false;
        }
        if (!recipient.insertExact(delivered)) {
            seller.insertExact(delivered);
            payer.insertExact(payment);
            return false;
        }
        if (!seller.insertExact(payment)) {
            throw new IllegalStateException("Sponsored material payment capacity changed during commit");
        }
        return true;
    }

    /**
     * Pools fragmented nearby cash for one essential material or finished-tool order. The seller receives the full
     * listed price, every contributing villager is physically present, and failure refunds every prior withdrawal.
     */
    private static boolean sponsoredFinishedToolExchange(Villager customer, Villager sellerVillager,
                                                          List<? extends Villager> villagers,
                                                          VillagerWorkInventorySavedData inventories,
                                                          ItemStack fullPayment, ItemStack merchandise) {
        if (!fullPayment.is(Items.EMERALD)) {
            return false;
        }
        VillagerWorkInventory customerInventory = inventories.inventory(customer.getUUID());
        VillagerWorkInventory sellerInventory = inventories.inventory(sellerVillager.getUUID());
        List<PaymentContribution> contributions = new ArrayList<>();
        int remaining = fullPayment.getCount();
        int customerContribution = Math.min(remaining, customerInventory.countMatchingItem(fullPayment));
        if (customerContribution > 0) {
            contributions.add(new PaymentContribution(customer, customerInventory, customerContribution));
            remaining -= customerContribution;
        }
        for (Villager sponsor : villagers.stream()
                .filter(candidate -> candidate != customer && candidate != sellerVillager)
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby())
                .filter(candidate -> candidate.distanceToSqr(sellerVillager) <= SEARCH_RANGE_SQUARED)
                .sorted(Comparator.comparingInt((Villager candidate) -> inventories.inventory(candidate.getUUID())
                        .countMatchingItem(fullPayment)).reversed()).toList()) {
            if (remaining < 1) {
                break;
            }
            VillagerWorkInventory sponsorInventory = inventories.inventory(sponsor.getUUID());
            int contribution = Math.min(remaining, sponsorInventory.countMatchingItem(fullPayment));
            if (contribution > 0) {
                contributions.add(new PaymentContribution(sponsor, sponsorInventory, contribution));
                remaining -= contribution;
            }
        }
        if (remaining > 0) {
            return false;
        }
        for (PaymentContribution contribution : contributions) {
            if (contribution.villager().distanceToSqr(sellerVillager) > EXCHANGE_REACH_SQUARED) {
                contribution.villager().getNavigation().moveTo(sellerVillager, .5D);
                return false;
            }
        }
        if (sellerInventory.countMatchingItem(merchandise) < merchandise.getCount()
                || !customerInventory.canInsertExact(merchandise)
                || !sellerInventory.canInsertExact(fullPayment)) {
            return false;
        }
        List<PaymentContribution> withdrawn = new ArrayList<>();
        for (PaymentContribution contribution : contributions) {
            ItemStack payment = fullPayment.copyWithCount(contribution.amount());
            if (contribution.inventory().takeExactMatchingItem(payment).isEmpty()) {
                refundPayments(withdrawn, fullPayment);
                return false;
            }
            withdrawn.add(contribution);
        }
        ItemStack delivered = sellerInventory.takeExactMatchingItem(merchandise).orElse(null);
        if (delivered == null) {
            refundPayments(withdrawn, fullPayment);
            return false;
        }
        if (!customerInventory.insertExact(delivered)) {
            sellerInventory.insertExact(delivered);
            refundPayments(withdrawn, fullPayment);
            return false;
        }
        if (!sellerInventory.insertExact(fullPayment)) {
            throw new IllegalStateException("Pooled work-order payment capacity changed during commit");
        }
        return true;
    }

    private static void refundPayments(List<PaymentContribution> contributions, ItemStack paymentTemplate) {
        for (PaymentContribution contribution : contributions) {
            if (!contribution.inventory().insertExact(paymentTemplate.copyWithCount(contribution.amount()))) {
                throw new IllegalStateException("Could not refund a pooled work-order contribution");
            }
        }
    }

    private static boolean craftPlanks(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack log = inventory.snapshot().stream().filter(stack -> stack.is(ItemTags.LOGS))
                .findFirst().map(stack -> stack.copyWithCount(1)).orElse(null);
        if (log == null) {
            return false;
        }
        CraftingInput input = CraftingInput.of(1, 1, List.of(log));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (output.isEmpty() || !output.is(ItemTags.PLANKS)) {
            return false;
        }
        var reservation = inventory.reserveExactMatchingItem(log).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(output.copy())) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    private static boolean craftSticks(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack planks = inventory.snapshot().stream().filter(stack -> stack.is(ItemTags.PLANKS) && stack.getCount() >= 2)
                .findFirst().map(stack -> stack.copyWithCount(2)).orElse(null);
        if (planks == null) {
            return false;
        }
        ItemStack one = planks.copyWithCount(1);
        CraftingInput input = CraftingInput.of(1, 2, List.of(one, one.copy()));
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!output.is(Items.STICK) || output.getCount() < 1) {
            return false;
        }
        var reservation = inventory.reserveExactMatchingItem(planks).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(output.copy())) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    private static List<ItemStack> plantFibreInputs(VillagerWorkInventory inventory) {
        List<ItemStack> inputs = new ArrayList<>(FIBRES_PER_STRING);
        for (ItemStack stack : inventory.snapshot()) {
            if (stack.isEmpty() || !stack.is(PLANT_FIBRES)) {
                continue;
            }
            int accepted = Math.min(stack.getCount(), FIBRES_PER_STRING - inputs.size());
            for (int index = 0; index < accepted; index++) {
                inputs.add(stack.copyWithCount(1));
            }
            if (inputs.size() == FIBRES_PER_STRING) {
                return List.copyOf(inputs);
            }
        }
        return List.copyOf(inputs);
    }

    private static boolean craftPlantFibreString(ServerLevel level, VillagerWorkInventory inventory,
                                                 List<ItemStack> inputs) {
        if (inputs.size() != FIBRES_PER_STRING || inputs.stream().anyMatch(stack -> !stack.is(PLANT_FIBRES))) {
            return false;
        }
        CraftingInput input = CraftingInput.of(3, 1, inputs);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!output.is(Items.STRING) || output.isEmpty()) {
            return false;
        }
        var reservation = inventory.reserveExactMatching(inputs).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(output.copy())) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    private static boolean craftShears(ServerLevel level, VillagerWorkInventory inventory) {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        List<ItemStack> slots = List.of(ItemStack.EMPTY, iron, iron.copy(), ItemStack.EMPTY);
        CraftingInput input = CraftingInput.of(2, 2, slots);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!output.is(Items.SHEARS) || output.getCount() != 1 || output.isDamaged()) {
            return false;
        }
        var reservation = inventory.reserveExact(List.of(new ItemAmount("minecraft:iron_ingot", 2))).orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.commitWithReturn(output.copy())) {
            return true;
        }
        reservation.rollback();
        return false;
    }

    private static boolean hasUsableShears(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream().anyMatch(stack -> stack.is(Items.SHEARS));
    }

    private static Optional<BlockPos> nearestFibrePlant(ServerLevel level, Villager toolsmith, BlockPos centre) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -FIBRE_SEARCH_VERTICAL; y <= FIBRE_SEARCH_VERTICAL; y++) {
            for (int x = -FIBRE_SEARCH_RADIUS; x <= FIBRE_SEARCH_RADIUS; x++) {
                for (int z = -FIBRE_SEARCH_RADIUS; z <= FIBRE_SEARCH_RADIUS; z++) {
                    BlockPos candidate = centre.offset(x, y, z);
                    if (!level.isLoaded(candidate) || !eligibleFibrePlant(level, candidate)
                            || !dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, toolsmith, candidate)) {
                        continue;
                    }
                    double distance = toolsmith.distanceToSqr(Vec3.atCenterOf(candidate));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean eligibleFibrePlant(ServerLevel level, BlockPos position) {
        var state = level.getBlockState(position);
        return state.is(FIBRE_PLANTS)
                // Clip only a mature lower vine segment. The upper mother vine is never selected, so generated
                // villages retain a renewable fibre source rather than eventually consuming their whole trellis.
                && (!state.is(Blocks.VINE) || level.getBlockState(position.above()).is(Blocks.VINE))
                && (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER);
    }

    private static Optional<BlockPos> jobSite(ServerLevel level, Villager villager, net.minecraft.world.level.block.Block block) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension())).map(GlobalPos::pos)
                .filter(level::isLoaded).filter(position -> level.getBlockState(position).is(block));
    }

    private static boolean atStationOrNavigate(Villager toolsmith, BlockPos station) {
        if (toolsmith.distanceToSqr(Vec3.atCenterOf(station)) <= WORK_REACH_SQUARED) {
            return true;
        }
        toolsmith.getNavigation().moveTo(station.getX() + .5D, station.getY(), station.getZ() + .5D, .5D);
        return false;
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int storedNutrition(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream()
                .mapToInt(stack -> dev.totem.villagers.needs.VillagerNutrition.nutrition(stack) * stack.getCount())
                .sum();
    }

    private static boolean isProfession(Villager villager, String expected) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && expected.equals(id.toString());
    }

    private static void refreshOffers(Villager villager) {
        var offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers != null) {
            VillagerTradeStockAuthority.refreshOffers(villager, offers);
        }
    }

    private record LogSupplier(Villager villager, ItemStack logs) {
    }

    private record ToolRequest(Villager worker, ReplacementTool tool) {
    }

    private record PaymentContribution(Villager villager, VillagerWorkInventory inventory, int amount) {
    }

    /** Priority is intentional: food production, mining inputs, then renewable wood. */
    private enum ReplacementTool {
        HOE("minecraft:farmer", Items.IRON_HOE, Items.COPPER_HOE, Items.STONE_HOE, 2,
                "totem:toolsmith_iron_hoe", "totem:toolsmith_copper_hoe", "totem:toolsmith_stone_hoe", 4, 4, 3),
        PICKAXE("totem:miner", Items.IRON_PICKAXE, Items.COPPER_PICKAXE, Items.STONE_PICKAXE, 3,
                "totem:toolsmith_iron_pickaxe", "totem:toolsmith_copper_pickaxe",
                "totem:toolsmith_stone_pickaxe", 5, 5, 4),
        AXE("totem:lumberjack", Items.IRON_AXE, Items.COPPER_AXE, Items.STONE_AXE, 3,
                "totem:toolsmith_iron_axe", "totem:toolsmith_copper_axe", "totem:toolsmith_stone_axe", 5, 5, 4);

        private final String professionId;
        private final Item ironItem;
        private final Item copperItem;
        private final Item stoneItem;
        private final int materialCount;
        private final String ironOrderId;
        private final String copperOrderId;
        private final String stoneOrderId;
        private final int ironPrice;
        private final int copperPrice;
        private final int stonePrice;

        ReplacementTool(String professionId, Item ironItem, Item copperItem, Item stoneItem, int materialCount,
                        String ironOrderId, String copperOrderId, String stoneOrderId,
                        int ironPrice, int copperPrice, int stonePrice) {
            this.professionId = professionId;
            this.ironItem = ironItem;
            this.copperItem = copperItem;
            this.stoneItem = stoneItem;
            this.materialCount = materialCount;
            this.ironOrderId = ironOrderId;
            this.copperOrderId = copperOrderId;
            this.stoneOrderId = stoneOrderId;
            this.ironPrice = ironPrice;
            this.copperPrice = copperPrice;
            this.stonePrice = stonePrice;
        }

        private boolean hasUsableTool(VillagerWorkInventory inventory) {
            return inventory.snapshot().stream().anyMatch(this::matchesFamily);
        }

        private boolean matchesFamily(ItemStack stack) {
            return switch (this) {
                case HOE -> stack.is(Items.WOODEN_HOE) || stack.is(Items.STONE_HOE) || stack.is(Items.COPPER_HOE)
                        || stack.is(Items.IRON_HOE) || stack.is(Items.GOLDEN_HOE)
                        || stack.is(Items.DIAMOND_HOE) || stack.is(Items.NETHERITE_HOE);
                case PICKAXE -> stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE)
                        || stack.is(Items.COPPER_PICKAXE) || stack.is(Items.IRON_PICKAXE)
                        || stack.is(Items.GOLDEN_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE)
                        || stack.is(Items.NETHERITE_PICKAXE);
                case AXE -> stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE) || stack.is(Items.COPPER_AXE)
                        || stack.is(Items.IRON_AXE) || stack.is(Items.GOLDEN_AXE)
                        || stack.is(Items.DIAMOND_AXE) || stack.is(Items.NETHERITE_AXE);
            };
        }

        private String professionId() { return professionId; }
        private Item ironItem() { return ironItem; }
        private Item copperItem() { return copperItem; }
        private Item stoneItem() { return stoneItem; }
        private int materialCount() { return materialCount; }
        private String ironOrderId() { return ironOrderId; }
        private String copperOrderId() { return copperOrderId; }
        private String stoneOrderId() { return stoneOrderId; }
        private int ironPrice() { return ironPrice; }
        private int copperPrice() { return copperPrice; }
        private int stonePrice() { return stonePrice; }
        /** The remaining one emerald of retail value pays the Lumberjack's amortized stick share. */
        private int ironMaterialPrice() { return ironPrice - 1; }
        private int copperMaterialPrice() { return copperPrice - 1; }
        private int stoneMaterialPrice() { return stonePrice - 1; }
    }
}
