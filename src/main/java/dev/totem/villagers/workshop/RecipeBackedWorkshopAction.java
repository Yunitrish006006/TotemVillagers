package dev.totem.villagers.workshop;

import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.RemnantBackpackOrders;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.world.FishermanWorkstation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Linked-job-site recipe action for the supported vanilla profession work
 * stations. It verifies exact raw inputs with Minecraft's own recipe system
 * before a personal-inventory reservation may be committed. The live server
 * recipe registry is authoritative: removing or replacing a recipe through a
 * data pack immediately prevents the matching villager work from producing
 * stock; item identifiers alone can never authorise a credit.
 */
public final class RecipeBackedWorkshopAction implements ValidatedWorkshopAction {
    private static final double WORK_REACH_SQUARED = 16.0D;
    private static final Map<String, String> MASON_TERRACOTTA_DYES = Map.ofEntries(
            Map.entry("minecraft:white_terracotta", "minecraft:white_dye"),
            Map.entry("minecraft:orange_terracotta", "minecraft:orange_dye"),
            Map.entry("minecraft:magenta_terracotta", "minecraft:magenta_dye"),
            Map.entry("minecraft:light_blue_terracotta", "minecraft:light_blue_dye"),
            Map.entry("minecraft:yellow_terracotta", "minecraft:yellow_dye"),
            Map.entry("minecraft:lime_terracotta", "minecraft:lime_dye"),
            Map.entry("minecraft:pink_terracotta", "minecraft:pink_dye"),
            Map.entry("minecraft:gray_terracotta", "minecraft:gray_dye"),
            Map.entry("minecraft:light_gray_terracotta", "minecraft:light_gray_dye"),
            Map.entry("minecraft:cyan_terracotta", "minecraft:cyan_dye"),
            Map.entry("minecraft:purple_terracotta", "minecraft:purple_dye"),
            Map.entry("minecraft:blue_terracotta", "minecraft:blue_dye"),
            Map.entry("minecraft:brown_terracotta", "minecraft:brown_dye"),
            Map.entry("minecraft:green_terracotta", "minecraft:green_dye"),
            Map.entry("minecraft:red_terracotta", "minecraft:red_dye"),
            Map.entry("minecraft:black_terracotta", "minecraft:black_dye")
    );
    private static final Map<String, String> MASON_GLAZED_TERRACOTTA_INPUTS = Map.ofEntries(
            Map.entry("minecraft:white_glazed_terracotta", "minecraft:white_terracotta"),
            Map.entry("minecraft:orange_glazed_terracotta", "minecraft:orange_terracotta"),
            Map.entry("minecraft:magenta_glazed_terracotta", "minecraft:magenta_terracotta"),
            Map.entry("minecraft:light_blue_glazed_terracotta", "minecraft:light_blue_terracotta"),
            Map.entry("minecraft:yellow_glazed_terracotta", "minecraft:yellow_terracotta"),
            Map.entry("minecraft:lime_glazed_terracotta", "minecraft:lime_terracotta"),
            Map.entry("minecraft:pink_glazed_terracotta", "minecraft:pink_terracotta"),
            Map.entry("minecraft:gray_glazed_terracotta", "minecraft:gray_terracotta"),
            Map.entry("minecraft:light_gray_glazed_terracotta", "minecraft:light_gray_terracotta"),
            Map.entry("minecraft:cyan_glazed_terracotta", "minecraft:cyan_terracotta"),
            Map.entry("minecraft:purple_glazed_terracotta", "minecraft:purple_terracotta"),
            Map.entry("minecraft:blue_glazed_terracotta", "minecraft:blue_terracotta"),
            Map.entry("minecraft:brown_glazed_terracotta", "minecraft:brown_terracotta"),
            Map.entry("minecraft:green_glazed_terracotta", "minecraft:green_terracotta"),
            Map.entry("minecraft:red_glazed_terracotta", "minecraft:red_terracotta"),
            Map.entry("minecraft:black_glazed_terracotta", "minecraft:black_terracotta")
    );
    private static final Set<String> SHEPHERD_COLOURS = Set.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );
    private static final Map<String, List<String>> SHEPHERD_DYE_INPUTS = Map.ofEntries(
            Map.entry("minecraft:white_dye", List.of("minecraft:bone_meal")),
            Map.entry("minecraft:black_dye", List.of("minecraft:ink_sac")),
            Map.entry("minecraft:gray_dye", List.of("minecraft:black_dye", "minecraft:white_dye")),
            Map.entry("minecraft:light_blue_dye", List.of("minecraft:blue_orchid")),
            Map.entry("minecraft:lime_dye", List.of("minecraft:green_dye", "minecraft:white_dye")),
            Map.entry("minecraft:yellow_dye", List.of("minecraft:dandelion")),
            Map.entry("minecraft:light_gray_dye", List.of("minecraft:azure_bluet")),
            Map.entry("minecraft:orange_dye", List.of("minecraft:orange_tulip")),
            Map.entry("minecraft:red_dye", List.of("minecraft:poppy")),
            Map.entry("minecraft:pink_dye", List.of("minecraft:pink_tulip")),
            Map.entry("minecraft:brown_dye", List.of("minecraft:cocoa_beans")),
            Map.entry("minecraft:purple_dye", List.of("minecraft:blue_dye", "minecraft:red_dye")),
            Map.entry("minecraft:blue_dye", List.of("minecraft:lapis_lazuli")),
            Map.entry("minecraft:magenta_dye", List.of("minecraft:allium")),
            Map.entry("minecraft:cyan_dye", List.of("minecraft:blue_dye", "minecraft:green_dye"))
    );

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos jobSite;
    private final WorkOrder order;
    private ItemStack returnedItem = ItemStack.EMPTY;

    public RecipeBackedWorkshopAction(ServerLevel level, Villager villager, BlockPos jobSite, WorkOrder order) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.jobSite = Objects.requireNonNull(jobSite, "jobSite");
        this.order = Objects.requireNonNull(order, "order");
    }

    public static boolean supports(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return supportsFarmer(order, level, jobSite)
                || supportsFletcher(order, level, jobSite)
                || supportsButcher(order, level, jobSite)
                || supportsFisherman(order, level, jobSite)
                || supportsShepherd(order, level, jobSite)
                || supportsMason(order, level, jobSite)
                || supportsLibrarian(order, level, jobSite)
                || supportsCleric(order, level, jobSite)
                || supportsArmorer(order, level, jobSite)
                || supportsWeaponsmith(order, level, jobSite)
                || supportsToolsmith(order, level, jobSite)
                || supportsLeatherworker(order, level, jobSite)
                || supportsCartographer(order, level, jobSite);
    }

    private static boolean supportsFarmer(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:farmer".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.COMPOSTER)
                && farmerInput(order) != null;
    }

    private static boolean supportsFletcher(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:fletcher".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.FLETCHING_TABLE)
                && fletcherInput(order) != null;
    }

    private static boolean supportsButcher(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:butcher".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.SMOKER)
                && (singleItemInput(order.requiredInputs()) != null || butcherCraftingInput(order) != null);
    }

    private static boolean supportsFisherman(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:fisherman".equals(order.professionId())
                && FishermanWorkstation.isSupportedJobSite(level, jobSite)
                && (singleItemInput(order.requiredInputs()) != null || fishermanCraftingInput(order) != null);
    }

    private static boolean supportsShepherd(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:shepherd".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.LOOM)
                && (shepherdInput(order) != null || shepherdSmeltingInput(order));
    }

    private static boolean supportsMason(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:mason".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.STONECUTTER)
                && (masonCraftingInput(order) != null || masonSingleInput(order));
    }

    private static boolean supportsLibrarian(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:librarian".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.LECTERN)
                && (librarianCraftingInput(order) != null || librarianSmeltingInput(order));
    }

    private static boolean supportsCleric(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:cleric".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.BREWING_STAND)
                && clericCraftingInput(order) != null;
    }

    private static boolean supportsArmorer(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:armorer".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.BLAST_FURNACE)
                && smithCraftingInput(order) != null;
    }

    private static boolean supportsWeaponsmith(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:weaponsmith".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.GRINDSTONE)
                && smithCraftingInput(order) != null;
    }

    private static boolean supportsToolsmith(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:toolsmith".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.SMITHING_TABLE)
                && (smithCraftingInput(order) != null || backpackSmithingInput(order) != null);
    }

    private static boolean supportsLeatherworker(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:leatherworker".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.CAULDRON)
                && leatherworkerInput(order) != null;
    }

    private static boolean supportsCartographer(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return "minecraft:cartographer".equals(order.professionId())
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.CARTOGRAPHY_TABLE)
                && (cartographyMapInput(order.requiredInputs()) != null || cartographerCraftingInput(order) != null);
    }

    @Override
    public boolean complete() {
        returnedItem = ItemStack.EMPTY;
        if (!villager.isAlive()
                || villager.distanceToSqr(Vec3.atCenterOf(jobSite)) > WORK_REACH_SQUARED
                || !supports(order, level, jobSite)) {
            return false;
        }
        if ("minecraft:farmer".equals(order.professionId())) {
            return completeCrafting(farmerInput(order));
        }
        if ("minecraft:fletcher".equals(order.professionId())) {
            return completeCrafting(fletcherInput(order));
        }
        if ("minecraft:butcher".equals(order.professionId())) {
            CraftingInput input = butcherCraftingInput(order);
            if (input != null) {
                return completeCrafting(input);
            }
            return completeCooking(RecipeType.SMOKING);
        }
        if ("minecraft:fisherman".equals(order.professionId())) {
            CraftingInput input = fishermanCraftingInput(order);
            if (input != null) {
                return completeCrafting(input);
            }
            return completeCooking(RecipeType.CAMPFIRE_COOKING);
        }
        if ("minecraft:shepherd".equals(order.professionId())) {
            CraftingInput input = shepherdInput(order);
            if (input != null) {
                return completeCrafting(input);
            }
            return shepherdSmeltingInput(order) && completeSingleInputRecipe(RecipeType.SMELTING);
        }
        if ("minecraft:mason".equals(order.professionId())) {
            CraftingInput crafting = masonCraftingInput(order);
            if (crafting != null) {
                return completeCrafting(crafting);
            }
            if ("minecraft:brick".equals(order.output().itemId())
                    || MASON_GLAZED_TERRACOTTA_INPUTS.containsKey(order.output().itemId())) {
                return completeSingleInputRecipe(RecipeType.SMELTING);
            }
            return completeStonecutting();
        }
        if ("minecraft:librarian".equals(order.professionId())) {
            CraftingInput input = librarianCraftingInput(order);
            if (input != null) {
                return completeCrafting(input);
            }
            return librarianSmeltingInput(order) && completeSingleInputRecipe(RecipeType.SMELTING);
        }
        if ("minecraft:cleric".equals(order.professionId())) {
            return completeCrafting(clericCraftingInput(order));
        }
        if ("minecraft:armorer".equals(order.professionId())
                || "minecraft:weaponsmith".equals(order.professionId())
                || "minecraft:toolsmith".equals(order.professionId())) {
            if (RemnantBackpackOrders.isBackpackOrder(order)) {
                return completeSmithing(backpackSmithingInput(order));
            }
            return completeCrafting(smithCraftingInput(order));
        }
        if ("minecraft:leatherworker".equals(order.professionId())) {
            return completeCrafting(leatherworkerInput(order));
        }
        if ("minecraft:cartographer".equals(order.professionId())) {
            CraftingInput input = cartographerCraftingInput(order);
            return completeCrafting(input != null ? input : cartographyMapInput(order.requiredInputs()));
        }
        return false;
    }

    @Override
    public ItemStack returnedItem() {
        return returnedItem.copy();
    }

    private boolean completeCrafting(CraftingInput input) {
        if (input == null) {
            return false;
        }
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.value().assemble(input);
        if (!order.matchesOutput(output, level.registryAccess())) {
            return false;
        }
        ItemStack remainder = combinedRemainder(recipe.value().getRemainingItems(input));
        if (remainder == null) {
            return false;
        }
        returnedItem = remainder;
        return completeOutput(output);
    }

    private <T extends Recipe<SingleRecipeInput>> boolean completeCooking(RecipeType<T> recipeType) {
        return completeSingleInputRecipe(recipeType);
    }

    private <T extends Recipe<SingleRecipeInput>> boolean completeSingleInputRecipe(RecipeType<T> recipeType) {
        SingleRecipeInput input = singleItemInput(order.requiredInputs());
        if (input == null) {
            return false;
        }
        var recipe = level.recipeAccess().getRecipeFor(recipeType, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        return completeOutput(recipe.value().assemble(input));
    }

    /** Validates the currently loaded Smithing Transform recipe, including data-pack replacements/removals. */
    private boolean completeSmithing(SmithingRecipeInput input) {
        if (input == null) {
            return false;
        }
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMITHING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        return completeOutput(recipe.value().assemble(input));
    }

    /**
     * Several Stonecutter recipes can accept the same input. Select the actual
     * recipe whose vanilla output matches this exact order rather than accepting
     * the first matching recipe-map entry.
     */
    private boolean completeStonecutting() {
        SingleRecipeInput input = singleItemInput(order.requiredInputs());
        if (input == null) {
            return false;
        }
        return level.recipeAccess().stonecutterRecipes().selectByInput(input.item()).entries().stream()
                .map(entry -> entry.recipe().recipe())
                .flatMap(Optional::stream)
                .map(holder -> holder.value())
                .filter(recipe -> recipe.matches(input, level))
                .map(recipe -> recipe.assemble(input))
                .filter(output -> order.matchesOutput(output, level.registryAccess()))
                .findFirst()
                .map(this::completeOutput)
                .orElse(false);
    }

    private boolean completeOutput(ItemStack output) {
        if (!order.matchesOutput(output, level.registryAccess())) {
            return false;
        }
        villager.playWorkSound();
        return true;
    }

    /**
     * Workshop reservations can restore one stack. Accept a vanilla crafting
     * remainder only when every non-empty slot is the same item and component
     * variant; cake's three empty buckets are the first such supported case.
     */
    private static ItemStack combinedRemainder(List<ItemStack> remainders) {
        ItemStack combined = ItemStack.EMPTY;
        for (ItemStack remainder : remainders) {
            if (remainder.isEmpty()) {
                continue;
            }
            if (combined.isEmpty()) {
                combined = remainder.copy();
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(combined, remainder)
                    || combined.getCount() + remainder.getCount() > combined.getMaxStackSize()) {
                return null;
            }
            combined.grow(remainder.getCount());
        }
        return combined;
    }

    private static CraftingInput craftingInput(List<ItemAmount> requiredInputs) {
        List<ItemStack> grid = new ArrayList<>(9);
        for (int index = 0; index < 9; index++) {
            grid.add(ItemStack.EMPTY);
        }
        int slot = 0;
        for (ItemAmount input : requiredInputs) {
            Identifier id = Identifier.tryParse(input.itemId());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item == null || slot + input.count() > grid.size()) {
                return null;
            }
            for (int count = 0; count < input.count(); count++) {
                grid.set(slot++, new ItemStack(item));
            }
        }
        return CraftingInput.of(3, 3, grid);
    }

    /** Builds a top-left-aligned crafting grid from exact registered item IDs. */
    private static CraftingInput gridInput(List<String> itemIds) {
        if (itemIds.size() > 9) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (String itemId : itemIds) {
            if (itemId.isBlank()) {
                grid.add(ItemStack.EMPTY);
                continue;
            }
            Identifier id = Identifier.tryParse(itemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
            if (item == null) {
                return null;
            }
            grid.add(new ItemStack(item));
        }
        while (grid.size() < 9) {
            grid.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, grid);
    }

    /**
     * The supported Farmer products include shaped recipes whose ingredient IDs
     * alone are insufficient evidence. Lay out their real grids explicitly so a
     * custom data-pack order cannot turn the same materials into a different item.
     */
    private static CraftingInput farmerInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:cake" -> exactFarmerInput(order.requiredInputs(), List.of(
                    new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                    new ItemStack(Items.SUGAR), new ItemStack(Items.EGG), new ItemStack(Items.SUGAR),
                    new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT), new ItemStack(Items.WHEAT)
            ), java.util.Map.of(
                    "minecraft:milk_bucket", 3, "minecraft:sugar", 2, "minecraft:egg", 1, "minecraft:wheat", 3));
            case "minecraft:cookie" -> exactFarmerInput(order.requiredInputs(),
                    List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.COCOA_BEANS), new ItemStack(Items.WHEAT)),
                    java.util.Map.of("minecraft:wheat", 2, "minecraft:cocoa_beans", 1));
            case "minecraft:golden_carrot" -> surroundedFarmerInput(order.requiredInputs(), Items.CARROT,
                    java.util.Map.of("minecraft:carrot", 1, "minecraft:gold_nugget", 8));
            case "minecraft:glistering_melon_slice" -> surroundedFarmerInput(order.requiredInputs(), Items.MELON_SLICE,
                    java.util.Map.of("minecraft:melon_slice", 1, "minecraft:gold_nugget", 8));
            default -> craftingInput(order.requiredInputs());
        };
    }

    /**
     * Fletcher recipes are shaped, so the path lays out their actual grids
     * instead of accepting a bag of ingredients with matching identifiers.
     */
    private static CraftingInput fletcherInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:arrow" -> exactFletcherInput(order.requiredInputs(), java.util.Map.of(
                    "minecraft:flint", 1, "minecraft:stick", 1, "minecraft:feather", 1), List.of(
                    ItemStack.EMPTY, new ItemStack(Items.FLINT), ItemStack.EMPTY,
                    ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY,
                    ItemStack.EMPTY, new ItemStack(Items.FEATHER), ItemStack.EMPTY
            ));
            case "minecraft:bow" -> exactFletcherInput(order.requiredInputs(), java.util.Map.of(
                    "minecraft:stick", 3, "minecraft:string", 3), List.of(
                    ItemStack.EMPTY, new ItemStack(Items.STICK), new ItemStack(Items.STRING),
                    new ItemStack(Items.STICK), ItemStack.EMPTY, new ItemStack(Items.STRING),
                    ItemStack.EMPTY, new ItemStack(Items.STICK), new ItemStack(Items.STRING)
            ));
            case "minecraft:crossbow" -> exactFletcherInput(order.requiredInputs(), java.util.Map.of(
                    "minecraft:stick", 3, "minecraft:string", 2, "minecraft:iron_ingot", 1,
                    "minecraft:tripwire_hook", 1), List.of(
                    new ItemStack(Items.STICK), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.STICK),
                    new ItemStack(Items.STRING), new ItemStack(Items.TRIPWIRE_HOOK), new ItemStack(Items.STRING),
                    ItemStack.EMPTY, new ItemStack(Items.STICK), ItemStack.EMPTY
            ));
            default -> null;
        };
    }

    private static CraftingInput exactFletcherInput(
            List<ItemAmount> inputs, java.util.Map<String, Integer> expected, List<ItemStack> grid
    ) {
        return exactInputCounts(inputs, expected) ? CraftingInput.of(3, 3, grid) : null;
    }

    /**
     * Shepherd workshop products retain the colour of their physical wool. The
     * colour appears in both the exact material list and the true vanilla grid,
     * so a white-wool order cannot be relabelled into another colour's bed or
     * carpet merely because both outputs share a recipe family.
     */
    private static CraftingInput shepherdInput(WorkOrder order) {
        String outputId = order.output().itemId();
        String colour;
        List<String> dyeInputs = SHEPHERD_DYE_INPUTS.get(outputId);
        if (dyeInputs != null) {
            return exactInputCounts(order.requiredInputs(), inputCounts(dyeInputs)) ? gridInput(dyeInputs) : null;
        }
        if (outputId.endsWith("_carpet")) {
            colour = outputId.substring("minecraft:".length(), outputId.length() - "_carpet".length());
            if (!SHEPHERD_COLOURS.contains(colour)
                    || !exactInputCounts(order.requiredInputs(), Map.of("minecraft:" + colour + "_wool", 2))) {
                return null;
            }
            return gridInput(List.of("minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool"));
        }
        if (outputId.endsWith("_bed")) {
            colour = outputId.substring("minecraft:".length(), outputId.length() - "_bed".length());
            if (!SHEPHERD_COLOURS.contains(colour)
                    || !exactInputCounts(order.requiredInputs(), Map.of(
                    "minecraft:" + colour + "_wool", 3, "minecraft:oak_planks", 3))) {
                return null;
            }
            return gridInput(List.of(
                    "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool",
                    "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks"
            ));
        }
        if (outputId.endsWith("_banner")) {
            colour = outputId.substring("minecraft:".length(), outputId.length() - "_banner".length());
            if (!SHEPHERD_COLOURS.contains(colour)
                    || !exactInputCounts(order.requiredInputs(), Map.of(
                    "minecraft:" + colour + "_wool", 6, "minecraft:stick", 1))) {
                return null;
            }
            return gridInput(List.of(
                    "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool",
                    "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool", "minecraft:" + colour + "_wool",
                    "", "minecraft:stick", ""
            ));
        }
        if ("minecraft:shears".equals(outputId)
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:iron_ingot", 2))) {
            return gridInput(List.of("", "minecraft:iron_ingot", "", "minecraft:iron_ingot"));
        }
        if ("minecraft:painting".equals(outputId)
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:stick", 8, "minecraft:white_wool", 1))) {
            return gridInput(List.of(
                    "minecraft:stick", "minecraft:stick", "minecraft:stick",
                    "minecraft:stick", "minecraft:white_wool", "minecraft:stick",
                    "minecraft:stick", "minecraft:stick", "minecraft:stick"
            ));
        }
        return null;
    }

    /** Fisherman equipment uses real shaped recipes at the linked Campfire. */
    private static CraftingInput fishermanCraftingInput(WorkOrder order) {
        if ("minecraft:campfire".equals(order.output().itemId())
                && exactInputCounts(order.requiredInputs(), Map.of(
                "minecraft:stick", 3, "minecraft:coal", 1, "minecraft:oak_log", 3))) {
            return gridInput(List.of(
                    "", "minecraft:stick", "",
                    "minecraft:stick", "minecraft:coal", "minecraft:stick",
                    "minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log"
            ));
        }
        String planks = switch (order.output().itemId()) {
            case "minecraft:oak_boat" -> "minecraft:oak_planks";
            case "minecraft:spruce_boat" -> "minecraft:spruce_planks";
            case "minecraft:jungle_boat" -> "minecraft:jungle_planks";
            case "minecraft:acacia_boat" -> "minecraft:acacia_planks";
            case "minecraft:dark_oak_boat" -> "minecraft:dark_oak_planks";
            default -> null;
        };
        if (planks == null || !exactInputCounts(order.requiredInputs(), Map.of(planks, 5))) {
            return null;
        }
        return gridInput(List.of(planks, "", planks, planks, planks, planks));
    }

    /**
     * Non-enchanted Librarian merchandise is produced from its actual vanilla
     * recipes. Enchanted books remain offer-bound and use their dedicated action.
     */
    private static CraftingInput librarianCraftingInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:bookshelf" -> exactSmithInput(order, Map.of(
                    "minecraft:oak_planks", 6, "minecraft:book", 3), List.of(
                    "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
                    "minecraft:book", "minecraft:book", "minecraft:book",
                    "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks"
            ));
            case "minecraft:lantern" -> exactSmithInput(order, Map.of(
                    "minecraft:iron_nugget", 8, "minecraft:torch", 1), List.of(
                    "minecraft:iron_nugget", "minecraft:iron_nugget", "minecraft:iron_nugget",
                    "minecraft:iron_nugget", "minecraft:torch", "minecraft:iron_nugget",
                    "minecraft:iron_nugget", "minecraft:iron_nugget", "minecraft:iron_nugget"
            ));
            case "minecraft:clock" -> exactSmithInput(order, Map.of(
                    "minecraft:gold_ingot", 4, "minecraft:redstone", 1), List.of(
                    "", "minecraft:gold_ingot", "",
                    "minecraft:gold_ingot", "minecraft:redstone", "minecraft:gold_ingot",
                    "", "minecraft:gold_ingot", ""
            ));
            case "minecraft:compass" -> exactSmithInput(order, Map.of(
                    "minecraft:iron_ingot", 4, "minecraft:redstone", 1), List.of(
                    "", "minecraft:iron_ingot", "",
                    "minecraft:iron_ingot", "minecraft:redstone", "minecraft:iron_ingot",
                    "", "minecraft:iron_ingot", ""
            ));
            case "minecraft:red_candle" -> librarianDyedCandleInput(order, "minecraft:red_dye");
            case "minecraft:yellow_candle" -> librarianDyedCandleInput(order, "minecraft:yellow_dye");
            default -> null;
        };
    }

    private static CraftingInput librarianDyedCandleInput(WorkOrder order, String dyeId) {
        return exactInputCounts(order.requiredInputs(), Map.of("minecraft:candle", 1, dyeId, 1))
                ? gridInput(List.of("minecraft:candle", dyeId)) : null;
    }

    private static boolean librarianSmeltingInput(WorkOrder order) {
        return "minecraft:glass".equals(order.output().itemId())
                && exactSingleItem(order.requiredInputs(), "minecraft:sand");
    }

    /** A Cleric may produce glowstone only from its true four-dust recipe. */
    private static CraftingInput clericCraftingInput(WorkOrder order) {
        return "minecraft:glowstone".equals(order.output().itemId())
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:glowstone_dust", 4))
                ? gridInput(List.of(
                "minecraft:glowstone_dust", "minecraft:glowstone_dust", "",
                "minecraft:glowstone_dust", "minecraft:glowstone_dust", ""
        )) : null;
    }

    /** Cartographer plain banners and item frames use their real crafting grids. */
    private static CraftingInput cartographerCraftingInput(WorkOrder order) {
        if ("minecraft:item_frame".equals(order.output().itemId())
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:stick", 8, "minecraft:leather", 1))) {
            return gridInput(List.of(
                    "minecraft:stick", "minecraft:stick", "minecraft:stick",
                    "minecraft:stick", "minecraft:leather", "minecraft:stick",
                    "minecraft:stick", "minecraft:stick", "minecraft:stick"
            ));
        }
        String outputId = order.output().itemId();
        if (!outputId.endsWith("_banner")) {
            return null;
        }
        String colour = outputId.substring("minecraft:".length(), outputId.length() - "_banner".length());
        String wool = "minecraft:" + colour + "_wool";
        if (!SHEPHERD_COLOURS.contains(colour)
                || !exactInputCounts(order.requiredInputs(), Map.of(wool, 6, "minecraft:stick", 1))) {
            return null;
        }
        return gridInput(List.of(
                wool, wool, wool,
                wool, wool, wool,
                "", "minecraft:stick", ""
        ));
    }

    /** Both vanilla rabbit-stew mushroom recipes are explicit, never a raw-item relabel. */
    private static CraftingInput butcherCraftingInput(WorkOrder order) {
        if (!"minecraft:rabbit_stew".equals(order.output().itemId())) {
            return null;
        }
        for (String mushroom : List.of("minecraft:brown_mushroom", "minecraft:red_mushroom")) {
            List<String> inputs = List.of(
                    "minecraft:baked_potato", "minecraft:cooked_rabbit", "minecraft:bowl", "minecraft:carrot", mushroom
            );
            if (exactInputCounts(order.requiredInputs(), inputCounts(inputs))) {
                return gridInput(inputs);
            }
        }
        return null;
    }

    /**
     * The first smith paths intentionally cover only unenchanted, reproducible
     * offers. Each grid is written out so a data-pack cannot turn the same ingot
     * count into a different tool or armour slot.
     */
    private static CraftingInput smithCraftingInput(WorkOrder order) {
        return switch (order.professionId()) {
            case "minecraft:armorer" -> switch (order.output().itemId()) {
                case "minecraft:iron_helmet" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 5), List.of(
                        "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot"
                ));
                case "minecraft:iron_chestplate" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 8), List.of(
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot"
                ));
                case "minecraft:iron_leggings" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 7), List.of(
                        "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot"
                ));
                case "minecraft:iron_boots" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 4), List.of(
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot",
                        "minecraft:iron_ingot", "", "minecraft:iron_ingot"
                ));
                case "minecraft:shield" -> exactSmithInput(order, Map.of(
                        "minecraft:oak_planks", 6, "minecraft:iron_ingot", 1), List.of(
                        "minecraft:oak_planks", "minecraft:iron_ingot", "minecraft:oak_planks",
                        "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
                        "", "minecraft:oak_planks", ""
                ));
                default -> null;
            };
            case "minecraft:weaponsmith" -> weaponsmithCraftingInput(order);
            case "minecraft:toolsmith" -> toolsmithCraftingInput(order);
            default -> null;
        };
    }

    private static CraftingInput toolsmithCraftingInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:fishing_rod" -> exactSmithInput(order, Map.of(
                    "minecraft:stick", 3, "minecraft:string", 2), List.of(
                    "", "", "minecraft:stick",
                    "", "minecraft:stick", "minecraft:string",
                    "minecraft:stick", "", "minecraft:string"
            ));
            case "minecraft:bucket" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 3), List.of(
                    "minecraft:iron_ingot", "", "minecraft:iron_ingot",
                    "", "minecraft:iron_ingot", ""
            ));
            case "minecraft:stone_axe" -> exactSmithInput(order, Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), List.of(
                    "minecraft:cobblestone", "minecraft:cobblestone", "",
                    "minecraft:cobblestone", "minecraft:stick", "",
                    "", "minecraft:stick"
            ));
            case "minecraft:iron_axe" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), List.of(
                    "minecraft:iron_ingot", "minecraft:iron_ingot", "",
                    "minecraft:iron_ingot", "minecraft:stick", "",
                    "", "minecraft:stick"
            ));
            case "minecraft:copper_axe" -> exactSmithInput(order, Map.of("minecraft:copper_ingot", 3, "minecraft:stick", 2), List.of(
                    "minecraft:copper_ingot", "minecraft:copper_ingot", "",
                    "minecraft:copper_ingot", "minecraft:stick", "",
                    "", "minecraft:stick"
            ));
            case "minecraft:stone_shovel" -> exactSmithInput(order, Map.of("minecraft:cobblestone", 1, "minecraft:stick", 2), List.of(
                    "minecraft:cobblestone", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:iron_shovel" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 1, "minecraft:stick", 2), List.of(
                    "minecraft:iron_ingot", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:stone_pickaxe" -> exactSmithInput(order, Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2), List.of(
                    "minecraft:cobblestone", "minecraft:cobblestone", "minecraft:cobblestone",
                    "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:iron_pickaxe" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), List.of(
                    "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
                    "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:copper_pickaxe" -> exactSmithInput(order, Map.of("minecraft:copper_ingot", 3, "minecraft:stick", 2), List.of(
                    "minecraft:copper_ingot", "minecraft:copper_ingot", "minecraft:copper_ingot",
                    "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:iron_hoe" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 2, "minecraft:stick", 2), List.of(
                    "minecraft:iron_ingot", "minecraft:iron_ingot", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:stone_hoe" -> exactSmithInput(order, Map.of("minecraft:cobblestone", 2, "minecraft:stick", 2), List.of(
                    "minecraft:cobblestone", "minecraft:cobblestone", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:copper_hoe" -> exactSmithInput(order, Map.of("minecraft:copper_ingot", 2, "minecraft:stick", 2), List.of(
                    "minecraft:copper_ingot", "minecraft:copper_ingot", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            case "minecraft:diamond_hoe" -> exactSmithInput(order, Map.of("minecraft:diamond", 2, "minecraft:stick", 2), List.of(
                    "minecraft:diamond", "minecraft:diamond", "", "", "minecraft:stick", "", "", "minecraft:stick"
            ));
            default -> null;
        };
    }

    private static SmithingRecipeInput backpackSmithingInput(WorkOrder order) {
        List<ItemStack> stacks = RemnantBackpackOrders.pristineSmithingStacks(order).orElse(null);
        return stacks == null ? null : new SmithingRecipeInput(stacks.get(0), stacks.get(1), stacks.get(2));
    }

    private static CraftingInput weaponsmithCraftingInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:iron_axe" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2), List.of(
                    "minecraft:iron_ingot", "minecraft:iron_ingot", "",
                    "minecraft:iron_ingot", "minecraft:stick", "",
                    "", "minecraft:stick"
            ));
            case "minecraft:iron_sword" -> exactSmithInput(order, Map.of("minecraft:iron_ingot", 2, "minecraft:stick", 1), List.of(
                    "minecraft:iron_ingot", "", "",
                    "minecraft:iron_ingot", "", "",
                    "minecraft:stick", "", ""
            ));
            default -> null;
        };
    }

    private static CraftingInput exactSmithInput(WorkOrder order, Map<String, Integer> expected, List<String> grid) {
        return exactInputCounts(order.requiredInputs(), expected) ? gridInput(grid) : null;
    }

    /** Green dye is the sole supported Shepherd dye that needs the vanilla furnace recipe. */
    private static boolean shepherdSmeltingInput(WorkOrder order) {
        return "minecraft:green_dye".equals(order.output().itemId())
                && exactSingleItem(order.requiredInputs(), "minecraft:cactus");
    }

    /** Mason outputs use actual Stonecutter, furnace, or shaped crafting inputs. */
    private static CraftingInput masonCraftingInput(WorkOrder order) {
        if ("minecraft:quartz_block".equals(order.output().itemId())
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:quartz", 4))) {
            return CraftingInput.of(3, 3, List.of(
                    new ItemStack(Items.QUARTZ), new ItemStack(Items.QUARTZ), ItemStack.EMPTY,
                    new ItemStack(Items.QUARTZ), new ItemStack(Items.QUARTZ), ItemStack.EMPTY,
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
            ));
        }
        if ("minecraft:dripstone_block".equals(order.output().itemId())
                && exactInputCounts(order.requiredInputs(), Map.of("minecraft:pointed_dripstone", 4))) {
            return CraftingInput.of(3, 3, List.of(
                    new ItemStack(Items.POINTED_DRIPSTONE), new ItemStack(Items.POINTED_DRIPSTONE), ItemStack.EMPTY,
                    new ItemStack(Items.POINTED_DRIPSTONE), new ItemStack(Items.POINTED_DRIPSTONE), ItemStack.EMPTY,
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
            ));
        }
        String dyeId = MASON_TERRACOTTA_DYES.get(order.output().itemId());
        Item dye = dyeId == null ? null : BuiltInRegistries.ITEM.getValue(Identifier.tryParse(dyeId));
        if (dye == null || !exactInputCounts(order.requiredInputs(), Map.of(
                "minecraft:terracotta", 8, dyeId, 1))) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            grid.add(slot == 4 ? new ItemStack(dye) : new ItemStack(Items.TERRACOTTA));
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static boolean masonSingleInput(WorkOrder order) {
        return switch (order.output().itemId()) {
            case "minecraft:brick" -> exactSingleItem(order.requiredInputs(), "minecraft:clay_ball");
            case "minecraft:chiseled_stone_bricks" -> exactSingleItem(order.requiredInputs(), "minecraft:stone");
            case "minecraft:polished_andesite" -> exactSingleItem(order.requiredInputs(), "minecraft:andesite");
            case "minecraft:polished_diorite" -> exactSingleItem(order.requiredInputs(), "minecraft:diorite");
            case "minecraft:polished_granite" -> exactSingleItem(order.requiredInputs(), "minecraft:granite");
            case "minecraft:quartz_pillar" -> exactSingleItem(order.requiredInputs(), "minecraft:quartz_block");
            default -> {
                String glazedInput = MASON_GLAZED_TERRACOTTA_INPUTS.get(order.output().itemId());
                yield glazedInput != null && exactSingleItem(order.requiredInputs(), glazedInput);
            }
        };
    }

    /** The first Leatherworker path uses the exact shaped vanilla saddle recipe. */
    private static CraftingInput leatherworkerInput(WorkOrder order) {
        if (!"minecraft:saddle".equals(order.output().itemId())
                || !exactInputCounts(order.requiredInputs(), java.util.Map.of(
                "minecraft:leather", 3, "minecraft:iron_ingot", 1))) {
            return null;
        }
        return CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(Items.LEATHER), ItemStack.EMPTY,
                new ItemStack(Items.LEATHER), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.LEATHER),
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
        ));
    }

    private static CraftingInput exactFarmerInput(
            List<ItemAmount> inputs, List<ItemStack> row, java.util.Map<String, Integer> expected
    ) {
        return exactInputCounts(inputs, expected) ? CraftingInput.of(3, 3, paddedGrid(row)) : null;
    }

    private static CraftingInput surroundedFarmerInput(
            List<ItemAmount> inputs, Item center, java.util.Map<String, Integer> expected
    ) {
        if (!exactInputCounts(inputs, expected)) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (int index = 0; index < 9; index++) {
            grid.add(index == 4 ? new ItemStack(center) : new ItemStack(Items.GOLD_NUGGET));
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static List<ItemStack> paddedGrid(List<ItemStack> row) {
        List<ItemStack> grid = new ArrayList<>(9);
        grid.addAll(row);
        while (grid.size() < 9) {
            grid.add(ItemStack.EMPTY);
        }
        return grid;
    }

    private static boolean exactInputCounts(List<ItemAmount> inputs, java.util.Map<String, Integer> expected) {
        java.util.Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (ItemAmount input : inputs) {
            actual.merge(input.itemId(), input.count(), Math::addExact);
        }
        return actual.equals(expected);
    }

    private static Map<String, Integer> inputCounts(List<String> itemIds) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String itemId : itemIds) {
            counts.merge(itemId, 1, Math::addExact);
        }
        return Map.copyOf(counts);
    }

    /**
     * The empty-map recipe is shaped: paper surrounds the central compass. Keeping
     * this layout explicit means the Cartography Table path validates the same
     * vanilla recipe rather than merely recognising paper and a compass by ID.
     */
    private static CraftingInput cartographyMapInput(List<ItemAmount> requiredInputs) {
        int paper = 0;
        int compass = 0;
        for (ItemAmount input : requiredInputs) {
            if ("minecraft:paper".equals(input.itemId())) {
                paper += input.count();
            } else if ("minecraft:compass".equals(input.itemId())) {
                compass += input.count();
            } else {
                return null;
            }
        }
        if (paper != 8 || compass != 1) {
            return null;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (int index = 0; index < 9; index++) {
            grid.add(index == 4 ? new ItemStack(Items.COMPASS) : new ItemStack(Items.PAPER));
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static SingleRecipeInput singleItemInput(List<ItemAmount> requiredInputs) {
        if (requiredInputs.size() != 1 || requiredInputs.getFirst().count() != 1) {
            return null;
        }
        Identifier id = Identifier.tryParse(requiredInputs.getFirst().itemId());
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        return item == null ? null : new SingleRecipeInput(new ItemStack(item));
    }

    private static boolean exactSingleItem(List<ItemAmount> inputs, String itemId) {
        return inputs.size() == 1 && inputs.getFirst().count() == 1 && itemId.equals(inputs.getFirst().itemId());
    }
}
