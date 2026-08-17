package dev.totem.villagers.gametest;

import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.world.FishermanWorldWorkAction;
import dev.totem.villagers.world.FishingRodUse;
import dev.totem.villagers.workshop.MapWorkChestInventory;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.workshop.VillageWorkChest;
import dev.totem.villagers.workshop.WorkChestKey;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Verifies workshop paths against real vanilla recipes, not item-id relabels. */
public final class WorkshopRecipeGameTest {
    private static final Map<String, Block> WORKSHOP_JOB_SITES = Map.ofEntries(
            Map.entry("minecraft:farmer", Blocks.COMPOSTER),
            Map.entry("minecraft:fisherman", Blocks.CAMPFIRE),
            Map.entry("minecraft:shepherd", Blocks.LOOM),
            Map.entry("minecraft:fletcher", Blocks.FLETCHING_TABLE),
            Map.entry("minecraft:librarian", Blocks.LECTERN),
            Map.entry("minecraft:cartographer", Blocks.CARTOGRAPHY_TABLE),
            Map.entry("minecraft:cleric", Blocks.BREWING_STAND),
            Map.entry("minecraft:armorer", Blocks.BLAST_FURNACE),
            Map.entry("minecraft:weaponsmith", Blocks.GRINDSTONE),
            Map.entry("minecraft:toolsmith", Blocks.SMITHING_TABLE),
            Map.entry("minecraft:butcher", Blocks.SMOKER),
            Map.entry("minecraft:leatherworker", Blocks.CAULDRON),
            Map.entry("minecraft:mason", Blocks.STONECUTTER)
    );

    /**
     * Keeps the GameTest suite coupled to the reloaded data catalogue rather
     * than a hand-written list of order IDs. A new static workshop order now
     * has to name a supported profession and pass its live vanilla recipe.
     */
    @GameTest(maxTicks = 100)
    public void everyDataDrivenWorkshopOrderUsesItsCurrentVanillaRecipe(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        Map<String, java.util.List<WorkOrder>> ordersByProfession = WorkOrderDefinitions.catalog().snapshot().values().stream()
                .filter(order -> order.allowedSources().contains(WorkSource.WORKSHOP))
                .collect(java.util.stream.Collectors.groupingBy(WorkOrder::professionId));
        require(helper, !ordersByProfession.isEmpty(), "The reloaded work-order catalogue contains no workshop orders");
        for (Map.Entry<String, java.util.List<WorkOrder>> entry : ordersByProfession.entrySet()) {
            String professionId = entry.getKey();
            Block workstation = WORKSHOP_JOB_SITES.get(professionId);
            require(helper, workstation != null, "Workshop order has no supported profession workstation: " + professionId);
            helper.setBlock(relativeJobSite, workstation);
            Villager villager = spawnVillager(helper, relativeJobSite.above());
            try {
                for (WorkOrder order : entry.getValue()) {
                    RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                    require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                            "Data-driven workshop order was not accepted at its job site: " + order.id());
                    require(helper, action.complete(),
                            "Data-driven workshop order did not validate its current vanilla recipe: " + order.id());
                }
            } finally {
                villager.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void butcherSmokerValidatesTheVanillaMeatRecipes(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.SMOKER);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String orderId : java.util.List.of(
                    "totem:butcher_cooked_mutton",
                    "totem:butcher_cooked_chicken",
                    "totem:butcher_cooked_porkchop",
                    "totem:butcher_rabbit_stew_brown_mushroom",
                    "totem:butcher_rabbit_stew_red_mushroom"
            )) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Butcher order was not accepted at a loaded Smoker: " + orderId);
                require(helper, action.complete(), "Vanilla meat smoking recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fishermanCampfireValidatesTheVanillaFishRecipes(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.CAMPFIRE);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String orderId : java.util.List.of(
                    "totem:fisherman_cooked_cod",
                    "totem:fisherman_cooked_salmon",
                    "totem:fisherman_campfire",
                    "totem:fisherman_oak_boat",
                    "totem:fisherman_spruce_boat",
                    "totem:fisherman_jungle_boat",
                    "totem:fisherman_acacia_boat",
                    "totem:fisherman_dark_oak_boat"
            )) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Fisherman order was not accepted at a loaded Campfire: " + orderId);
                require(helper, action.complete(), "Vanilla fish smoking recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void librarianLecternValidatesNonEnchantedVanillaMerchandise(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.LECTERN);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String orderId : java.util.List.of(
                    "totem:librarian_bookshelf",
                    "totem:librarian_lantern",
                    "totem:librarian_glass",
                    "totem:librarian_clock",
                    "totem:librarian_compass",
                    "totem:librarian_red_candle",
                    "totem:librarian_yellow_candle"
            )) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Librarian order was not accepted at a loaded Lectern: " + orderId);
                require(helper, action.complete(), "Vanilla Librarian recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void clericBrewingStandValidatesTheVanillaGlowstoneRecipe(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.BREWING_STAND);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            WorkOrder order = WorkOrderDefinitions.catalog().require("totem:cleric_glowstone");
            RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
            require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                    "Cleric glowstone order was not accepted at a loaded Brewing Stand");
            require(helper, action.complete(), "Vanilla Cleric glowstone recipe was not validated");
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fishermanWorldWorkUsesVanillaFishingLootAndOnlyCreditsCookableFish(GameTestHelper helper) {
        BlockPos relativeWater = new BlockPos(4, 2, 4);
        BlockPos water = helper.absolutePos(relativeWater);
        helper.setBlock(relativeWater, Blocks.WATER);
        Villager villager = spawnVillager(helper, new BlockPos(2, 2, 2));
        try {
            java.util.List<ItemStack> catches = FishermanWorldWorkAction.catches(helper.getLevel(), villager, water);
            require(helper, !catches.isEmpty() && catches.stream().noneMatch(ItemStack::isEmpty),
                    "Vanilla fishing table returned no usable catch");
            WorkOrder cod = WorkOrderDefinitions.catalog().require("totem:fisherman_cooked_cod");
            WorkOrder salmon = WorkOrderDefinitions.catalog().require("totem:fisherman_cooked_salmon");
            WorkOrder codBucket = WorkOrderDefinitions.catalog().require("totem:fisherman_cod_bucket");
            require(helper, FishermanWorldWorkAction.matchesCookedCatch(helper.getLevel(), new ItemStack(Items.COD), cod),
                    "A raw cod catch was not validated through the vanilla Campfire recipe");
            require(helper, FishermanWorldWorkAction.matchesCookedCatch(helper.getLevel(), new ItemStack(Items.SALMON), salmon),
                    "A raw salmon catch was not validated through the vanilla Campfire recipe");
            require(helper, !FishermanWorldWorkAction.matchesCookedCatch(helper.getLevel(), new ItemStack(Items.STICK), cod),
                    "Fishing junk incorrectly credited the cooked-cod order");
            ItemStack bucketedCod = FishermanWorldWorkAction.bucketForCatch(new ItemStack(Items.COD), codBucket).orElse(null);
            require(helper, bucketedCod != null && bucketedCod.is(Items.COD_BUCKET),
                    "A real cod catch was not converted to the Fisherman's cod bucket");
            require(helper, FishermanWorldWorkAction.bucketForCatch(new ItemStack(Items.SALMON), codBucket).isEmpty(),
                    "A non-cod catch incorrectly filled the Fisherman's cod bucket");
            ItemStack freshRod = new ItemStack(Items.FISHING_ROD);
            ItemStack wornRod = FishingRodUse.wearOnce(freshRod);
            require(helper, wornRod.is(Items.FISHING_ROD) && wornRod.getDamageValue() == 1,
                    "A successful autonomous catch did not consume one real fishing-rod durability point");
            ItemStack finalUseRod = new ItemStack(Items.FISHING_ROD);
            finalUseRod.setDamageValue(finalUseRod.getMaxDamage() - 1);
            require(helper, FishingRodUse.bestAvailable(java.util.List.of(finalUseRod)).isPresent()
                            && FishingRodUse.wearOnce(finalUseRod).isEmpty(),
                    "The fishing rod's final use did not break it and trigger replacement demand");
            require(helper, FishingRodUse.bestAvailable(java.util.List.of(new ItemStack(Items.STICK))).isEmpty(),
                    "A Fisherman without a physical fishing rod was incorrectly considered ready to fish");
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fletcherTableValidatesTheVanillaRangedWeaponRecipes(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.FLETCHING_TABLE);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String orderId : java.util.List.of(
                    "totem:fletcher_arrows",
                    "totem:fletcher_bow",
                    "totem:fletcher_crossbow"
            )) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Fletcher order was not accepted at a loaded Fletching Table: " + orderId);
                require(helper, action.complete(), "Vanilla Fletcher recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void shepherdLoomValidatesEveryVanillaCarpetAndBedRecipe(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.LOOM);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String colour : java.util.List.of(
                    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
            )) {
                for (String product : java.util.List.of("carpet", "bed", "banner", "dye")) {
                    String orderId = "totem:shepherd_" + colour + "_" + product;
                    WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                    RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                    require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                            "Shepherd order was not accepted at a loaded Loom: " + orderId);
                    require(helper, action.complete(), "Vanilla Shepherd recipe was not validated: " + orderId);
                }
            }
            for (String orderId : java.util.List.of("totem:shepherd_shears", "totem:shepherd_painting")) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Shepherd order was not accepted at a loaded Loom: " + orderId);
                require(helper, action.complete(), "Vanilla Shepherd recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void smithWorkstationsValidateTheSupportedVanillaEquipmentRecipes(GameTestHelper helper) {
        BlockPos relativeBlastFurnace = new BlockPos(1, 2, 2);
        BlockPos relativeGrindstone = new BlockPos(3, 2, 2);
        BlockPos relativeSmithingTable = new BlockPos(5, 2, 2);
        helper.setBlock(relativeBlastFurnace, Blocks.BLAST_FURNACE);
        helper.setBlock(relativeGrindstone, Blocks.GRINDSTONE);
        helper.setBlock(relativeSmithingTable, Blocks.SMITHING_TABLE);
        Villager armorer = spawnVillager(helper, relativeBlastFurnace.above());
        Villager weaponsmith = spawnVillager(helper, relativeGrindstone.above());
        Villager toolsmith = spawnVillager(helper, relativeSmithingTable.above());
        try {
            validateWorkshopOrders(helper, armorer, helper.absolutePos(relativeBlastFurnace), java.util.List.of(
                    "totem:armorer_iron_helmet", "totem:armorer_iron_chestplate", "totem:armorer_iron_leggings",
                    "totem:armorer_iron_boots", "totem:armorer_shield"
            ));
            validateWorkshopOrders(helper, weaponsmith, helper.absolutePos(relativeGrindstone), java.util.List.of(
                    "totem:weaponsmith_iron_axe", "totem:weaponsmith_iron_sword"
            ));
            validateWorkshopOrders(helper, toolsmith, helper.absolutePos(relativeSmithingTable), java.util.List.of(
                    "totem:toolsmith_stone_axe", "totem:toolsmith_stone_shovel", "totem:toolsmith_stone_pickaxe",
                    "totem:toolsmith_stone_hoe", "totem:toolsmith_iron_axe", "totem:toolsmith_iron_shovel",
                    "totem:toolsmith_iron_pickaxe", "totem:toolsmith_diamond_hoe", "totem:toolsmith_bucket",
                    "totem:toolsmith_fishing_rod"
            ));
            helper.succeed();
        } finally {
            armorer.discard();
            weaponsmith.discard();
            toolsmith.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void masonStonecutterValidatesVanillaMasonryRecipes(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.STONECUTTER);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            java.util.List<String> orderIds = new java.util.ArrayList<>(java.util.List.of(
                    "totem:mason_brick",
                    "totem:mason_chiseled_stone_bricks",
                    "totem:mason_polished_andesite",
                    "totem:mason_polished_diorite",
                    "totem:mason_polished_granite",
                    "totem:mason_quartz_pillar",
                    "totem:mason_quartz_block",
                    "totem:mason_dripstone_block"
            ));
            // The colour-specific Mason offers must be backed by their exact
            // vanilla dyeing and smelting recipes, not a generic item-ID rule.
            for (String colour : java.util.List.of(
                    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
            )) {
                orderIds.add("totem:mason_" + colour + "_terracotta");
                orderIds.add("totem:mason_" + colour + "_glazed_terracotta");
            }
            for (String orderId : orderIds) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Mason order was not accepted at a loaded Stonecutter: " + orderId);
                require(helper, action.complete(), "Vanilla Mason recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void leatherworkerCauldronValidatesTheVanillaSaddleRecipe(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.CAULDRON);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            WorkOrder order = WorkOrderDefinitions.catalog().require("totem:leatherworker_saddle");
            RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
            require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                    "Leatherworker saddle order was not accepted at a loaded Cauldron");
            require(helper, action.complete(), "Vanilla Leatherworker saddle recipe was not validated");
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void cartographerTableValidatesTheVanillaEmptyMapRecipe(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.CARTOGRAPHY_TABLE);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            java.util.List<String> orderIds = new java.util.ArrayList<>(java.util.List.of(
                    "totem:cartographer_empty_map", "totem:cartographer_item_frame"
            ));
            for (String colour : java.util.List.of(
                    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
            )) {
                orderIds.add("totem:cartographer_" + colour + "_banner");
            }
            for (String orderId : orderIds) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Cartographer order was not accepted at a loaded Cartography Table: " + orderId);
                require(helper, action.complete(), "Vanilla Cartographer recipe was not validated: " + orderId);
            }
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void farmerComposterValidatesSupportedVanillaFoodRecipes(GameTestHelper helper) {
        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        Villager villager = spawnVillager(helper, relativeJobSite.above());
        try {
            for (String orderId : java.util.List.of(
                    "totem:farmer_bread",
                    "totem:farmer_cake",
                    "totem:farmer_pumpkin_pie",
                    "totem:farmer_cookie",
                    "totem:farmer_golden_carrot",
                    "totem:farmer_glistering_melon_slice"
            )) {
                WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
                RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
                require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                        "Farmer order was not accepted at a Composter: " + orderId);
                require(helper, action.complete(), "Vanilla Farmer recipe was not validated: " + orderId);
                if ("totem:farmer_cake".equals(orderId)) {
                    ItemStack returned = action.returnedItem();
                    require(helper, returned.is(Items.BUCKET) && returned.getCount() == 3,
                            "Cake work did not preserve all three vanilla empty buckets");
                }
            }
            WorkOrder cake = WorkOrderDefinitions.catalog().require("totem:farmer_cake");
            MapWorkChestInventory inventory = new MapWorkChestInventory(Map.of(
                    "minecraft:milk_bucket", 3, "minecraft:sugar", 2, "minecraft:egg", 1, "minecraft:wheat", 3
            ));
            MerchantStock stock = new MerchantStock();
            WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, cake, stock,
                    new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, cake));
            require(helper, result == WorkshopCommitResult.COMPLETED,
                    "Cake workshop commit did not accept its validated vanilla remainder: " + result);
            require(helper, inventory.snapshot().equals(Map.of("minecraft:bucket", 3)),
                    "Cake workshop commit did not restore exactly three empty buckets");
            require(helper, stock.available("minecraft:cake") == 1,
                    "Cake workshop commit did not credit exactly one cake");
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private static void validateWorkshopOrders(
            GameTestHelper helper, Villager villager, BlockPos jobSite, java.util.List<String> orderIds
    ) {
        for (String orderId : orderIds) {
            WorkOrder order = WorkOrderDefinitions.catalog().require(orderId);
            RecipeBackedWorkshopAction action = new RecipeBackedWorkshopAction(helper.getLevel(), villager, jobSite, order);
            require(helper, RecipeBackedWorkshopAction.supports(order, helper.getLevel(), jobSite),
                    "Smith order was not accepted at its loaded workstation: " + orderId);
            require(helper, action.complete(), "Vanilla smith recipe was not validated: " + orderId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }
}
