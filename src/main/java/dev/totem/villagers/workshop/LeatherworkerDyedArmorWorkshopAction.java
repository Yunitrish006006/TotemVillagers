package dev.totem.villagers.workshop;

import dev.totem.villagers.work.LeatherworkerDyedArmorOrders;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cauldron-bound two-stage leather work. It first verifies the base leather
 * equipment recipe, then the vanilla armour-dye recipe, and only accepts the
 * exact component-bearing stack from the Leatherworker's live offer.
 */
public final class LeatherworkerDyedArmorWorkshopAction implements ValidatedWorkshopAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos jobSite;
    private final WorkOrder order;
    private final MerchantOffers offers;

    public LeatherworkerDyedArmorWorkshopAction(
            ServerLevel level, Villager villager, BlockPos jobSite, WorkOrder order, MerchantOffers offers
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.jobSite = Objects.requireNonNull(jobSite, "jobSite");
        this.order = Objects.requireNonNull(order, "order");
        this.offers = Objects.requireNonNull(offers, "offers");
    }

    public static boolean supports(WorkOrder order, ServerLevel level, BlockPos jobSite, MerchantOffers offers) {
        return LeatherworkerDyedArmorOrders.isOfferBoundDyedOrder(order)
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.CAULDRON)
                && matchingOffer(order, offers, level) != null;
    }

    @Override
    public boolean complete() {
        if (!villager.isAlive() || !isLeatherworker(villager)
                || villager.distanceToSqr(Vec3.atCenterOf(jobSite)) > WORK_REACH_SQUARED
                || !supports(order, level, jobSite, offers)) {
            return false;
        }
        MerchantOffer offer = matchingOffer(order, offers, level);
        List<DyeColor> dyes = offer == null ? null : LeatherworkerDyedArmorOrders.dyesFor(offer.getResult()).orElse(null);
        CraftingInput armourInput = armorInput(order.output().itemId());
        if (dyes == null || armourInput == null) {
            return false;
        }
        var armourRecipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, armourInput, level).orElse(null);
        if (armourRecipe == null || !armourRecipe.value().matches(armourInput, level)) {
            return false;
        }
        ItemStack base = armourRecipe.value().assemble(armourInput);
        if (!order.output().itemId().equals(BuiltInRegistries.ITEM.getKey(base.getItem()).toString())) {
            return false;
        }
        CraftingInput dyeInput = dyeInput(base, dyes);
        var dyeRecipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, dyeInput, level).orElse(null);
        if (dyeRecipe == null || !dyeRecipe.value().matches(dyeInput, level)) {
            return false;
        }
        ItemStack produced = dyeRecipe.value().assemble(dyeInput);
        if (!order.matchesOutput(produced, level.registryAccess())) {
            return false;
        }
        villager.playWorkSound();
        return true;
    }

    private static MerchantOffer matchingOffer(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        for (MerchantOffer offer : offers) {
            if (LeatherworkerDyedArmorOrders.orderFor(offer, level.registryAccess())
                    .filter(candidate -> candidate.equals(order)).isPresent()
                    && StockVariantKey.fromStack(offer.getResult(), level.registryAccess()).equals(order.outputKey())) {
                return offer;
            }
        }
        return null;
    }

    private static CraftingInput armorInput(String outputId) {
        ItemStack leather = new ItemStack(Items.LEATHER);
        return switch (outputId) {
            case "minecraft:leather_helmet" -> CraftingInput.of(3, 3, List.of(
                    leather.copy(), leather.copy(), leather.copy(),
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
            ));
            case "minecraft:leather_chestplate" -> CraftingInput.of(3, 3, List.of(
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    leather.copy(), leather.copy(), leather.copy(),
                    leather.copy(), leather.copy(), leather.copy()
            ));
            case "minecraft:leather_leggings" -> CraftingInput.of(3, 3, List.of(
                    leather.copy(), leather.copy(), leather.copy(),
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    leather.copy(), ItemStack.EMPTY, leather.copy()
            ));
            case "minecraft:leather_boots" -> CraftingInput.of(3, 3, List.of(
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
            ));
            case "minecraft:leather_horse_armor" -> CraftingInput.of(3, 3, List.of(
                    leather.copy(), ItemStack.EMPTY, leather.copy(),
                    leather.copy(), leather.copy(), leather.copy(),
                    leather.copy(), ItemStack.EMPTY, leather.copy()
            ));
            default -> null;
        };
    }

    private static CraftingInput dyeInput(ItemStack base, List<DyeColor> dyes) {
        List<ItemStack> grid = new ArrayList<>(9);
        grid.add(base);
        for (DyeColor dye : dyes) {
            grid.add(LeatherworkerDyedArmorOrders.dyeStack(dye));
        }
        while (grid.size() < 9) {
            grid.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static boolean isLeatherworker(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:leatherworker".equals(id.toString());
    }
}
